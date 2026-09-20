package com.mynative.myeiwema

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mynative.myeiwema.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    // Camera & Analysis components
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isTorchOn = false
    private var isScanningActive = true

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer

    private var currentBottomSheet: BottomSheetDialog? = null

    // Native C++ functions implemented in native-lib.cpp
    external fun stringFromJNI(): String
    external fun findConsecutive24Digits(rawText: String): String?
    external fun is24Digits(text: String): Boolean
    external fun generateQrCodeBitmap(text: String, width: Int, height: Int): Bitmap?

    companion object {
        init {
            System.loadLibrary("myapplication")
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                binding.permissionOverlay.visibility = View.GONE
                startCamera()
            } else {
                binding.permissionOverlay.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        initViews()
        checkCameraPermission()
    }

    private fun initViews() {
        // Torch button
        binding.btnTorch.setOnClickListener {
            toggleTorch()
        }

        // Camera switch button (front / back)
        binding.btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

        // Manual test button
        binding.btnManualTest.setOnClickListener {
            showManualTestDialog()
        }

        // Grant permission button
        binding.btnGrantPermission.setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )
            // Restore torch state if on
            if (isTorchOn) {
                camera?.cameraControl?.enableTorch(true)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "相机绑定失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isScanningActive) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!isScanningActive) return@addOnSuccessListener

                    // 1. Pass recognized text to C++ native engine to find strictly consecutive 24 digits
                    var matched24Digits = findConsecutive24Digits(visionText.text)

                    // 2. If not found in full text, check individual text blocks
                    if (matched24Digits == null) {
                        for (block in visionText.textBlocks) {
                            matched24Digits = findConsecutive24Digits(block.text)
                            if (matched24Digits != null) break
                        }
                    }

                    // 3. If valid 24-digit string found by C++, trigger success
                    if (matched24Digits != null && isScanningActive) {
                        isScanningActive = false
                        runOnUiThread {
                            on24DigitsFound(matched24Digits)
                        }
                    }
                }
                .addOnFailureListener {
                    // Failures in frame processing can be ignored
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun on24DigitsFound(digits: String) {
        vibrateDevice()
        binding.scanOverlay.setScanningState(false)

        // Call C++ native QR code generator to generate Android Bitmap
        val qrBitmap = generateQrCodeBitmap(digits, 600, 600)
        if (qrBitmap != null) {
            showResultBottomSheet(digits, qrBitmap)
        } else {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show()
            isScanningActive = true
            binding.scanOverlay.setScanningState(true)
        }
    }

    private fun showResultBottomSheet(digits: String, qrBitmap: Bitmap) {
        currentBottomSheet?.dismiss()

        val bottomSheetDialog = BottomSheetDialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_scan_result, null)
        bottomSheetDialog.setContentView(dialogView)

        val tvDigits = dialogView.findViewById<TextView>(R.id.tvDigits)
        val ivQrCode = dialogView.findViewById<ImageView>(R.id.ivQrCode)
        val btnRescan = dialogView.findViewById<MaterialButton>(R.id.btnRescan)
        val btnSaveQr = dialogView.findViewById<MaterialButton>(R.id.btnSaveQr)
        val btnShare = dialogView.findViewById<MaterialButton>(R.id.btnShare)

        // Display strictly continuous 24 digits without spaces
        tvDigits.text = digits

        ivQrCode.setImageBitmap(qrBitmap)

        btnRescan.setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        btnSaveQr.setOnClickListener {
            saveBitmapToGallery(qrBitmap, digits)
        }

        btnShare.setOnClickListener {
            shareDigits(digits)
        }

        bottomSheetDialog.setOnDismissListener {
            // Resume scanning on dismiss
            isScanningActive = true
            binding.scanOverlay.setScanningState(true)
        }

        currentBottomSheet = bottomSheetDialog
        bottomSheetDialog.show()
    }

    private fun toggleTorch() {
        camera?.let { cam ->
            if (cam.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                cam.cameraControl.enableTorch(isTorchOn)
                binding.tvTorchIcon.text = if (isTorchOn) "💡" else "⚡"
                binding.tvTorchLabel.text = if (isTorchOn) "已开启" else "手电筒"
            } else {
                Toast.makeText(this, "设备无闪光灯", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        isTorchOn = false
        binding.tvTorchIcon.text = "⚡"
        binding.tvTorchLabel.text = "手电筒"
        bindCameraUseCases()
    }

    private fun showManualTestDialog() {
        val editText = EditText(this).apply {
            hint = "输入无空格连续24位纯数字进行测试"
            setText("123456789012345678901234")
            setSingleLine()
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("模拟/手动测试")
            .setMessage("输入或粘贴待测试的文本，C++引擎将严格检验是否包含连续24位纯数字（不得带空格或分隔符）并生成二维码：")
            .setView(editText)
            .setPositiveButton("开始测试") { _, _ ->
                val input = editText.text.toString().trim()
                val matched = findConsecutive24Digits(input)
                if (matched != null) {
                    isScanningActive = false
                    on24DigitsFound(matched)
                } else {
                    Toast.makeText(this, "未找到连续24位数字！仅支持无空格的连续24位纯数字", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareDigits(digits: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "已扫描24位数字: $digits")
        }
        startActivity(Intent.createChooser(intent, "分享24位数字"))
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, digits: String) {
        val filename = "QR_24Digits_${digits.take(6)}_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRCode24")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
                Toast.makeText(this, "二维码已成功保存至相册", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "创建相册条目失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrateDevice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentBottomSheet?.isShowing != true) {
            isScanningActive = true
            binding.scanOverlay.setScanningState(true)
        }
    }

    override fun onPause() {
        super.onPause()
        isScanningActive = false
        binding.scanOverlay.setScanningState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBottomSheet?.dismiss()
        currentBottomSheet = null
        textRecognizer.close()
        cameraExecutor.shutdown()
        _binding = null
    }
}