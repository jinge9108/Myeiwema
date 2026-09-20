package com.mynative.myeiwema

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maskPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Semi-transparent dark overlay
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00E676") // Vibrant Accent Green
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val laserPaint = Paint().apply {
        isAntiAlias = true
    }

    private val tipTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#80000000"))
    }

    private val scanRect = RectF()
    private var laserY = 0f
    private var laserAnimator: ValueAnimator? = null
    private var isScanning = true

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScanRect(w, h)
        startLaserAnimation()
    }

    private fun updateScanRect(w: Int, h: Int) {
        // Ideal aspect ratio for 24 continuous digits / serial numbers
        val boxWidth = (w * 0.85f).coerceAtMost(520f * resources.displayMetrics.density)
        val boxHeight = boxWidth * 0.40f // wider rectangle tailored for consecutive digits

        val left = (w - boxWidth) / 2f
        val top = (h - boxHeight) / 2f - 40f * resources.displayMetrics.density // slightly above center
        val right = left + boxWidth
        val bottom = top + boxHeight

        scanRect.set(left, top, right, bottom)
    }

    fun getScanRect(): RectF = RectF(scanRect)

    fun setScanningState(scanning: Boolean) {
        isScanning = scanning
        if (scanning) {
            startLaserAnimation()
        } else {
            laserAnimator?.cancel()
        }
        invalidate()
    }

    private fun startLaserAnimation() {
        laserAnimator?.cancel()
        if (scanRect.height() <= 0) return

        laserAnimator = ValueAnimator.ofFloat(scanRect.top, scanRect.bottom).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                laserY = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scanRect.isEmpty) return

        // 1. Draw dark background mask
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)

        // 2. Cut out center scan box
        val cornerRadius = 12f * resources.displayMetrics.density
        canvas.drawRoundRect(scanRect, cornerRadius, cornerRadius, clearPaint)

        // 3. Draw thin box border
        canvas.drawRoundRect(scanRect, cornerRadius, cornerRadius, borderPaint)

        // 4. Draw stylish 4 corner brackets
        val cornerLength = 24f * resources.displayMetrics.density
        // Top-Left
        canvas.drawLine(scanRect.left, scanRect.top + cornerLength, scanRect.left, scanRect.top + 4f, cornerPaint)
        canvas.drawLine(scanRect.left, scanRect.top, scanRect.left + cornerLength, scanRect.top, cornerPaint)
        // Top-Right
        canvas.drawLine(scanRect.right - cornerLength, scanRect.top, scanRect.right, scanRect.top, cornerPaint)
        canvas.drawLine(scanRect.right, scanRect.top + 4f, scanRect.right, scanRect.top + cornerLength, cornerPaint)
        // Bottom-Left
        canvas.drawLine(scanRect.left, scanRect.bottom - cornerLength, scanRect.left, scanRect.bottom - 4f, cornerPaint)
        canvas.drawLine(scanRect.left, scanRect.bottom, scanRect.left + cornerLength, scanRect.bottom, cornerPaint)
        // Bottom-Right
        canvas.drawLine(scanRect.right - cornerLength, scanRect.bottom, scanRect.right, scanRect.bottom, cornerPaint)
        canvas.drawLine(scanRect.right, scanRect.bottom - 4f, scanRect.right, scanRect.bottom - cornerLength, cornerPaint)

        // 5. Draw animated laser line
        if (isScanning && laserY in scanRect.top..scanRect.bottom) {
            val shader = LinearGradient(
                scanRect.left, laserY, scanRect.right, laserY,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#00E676"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            laserPaint.shader = shader
            laserPaint.strokeWidth = 3f * resources.displayMetrics.density
            canvas.drawLine(scanRect.left + 8f, laserY, scanRect.right - 8f, laserY, laserPaint)
        }

        // 6. Draw hint text under the box
        val hintText = "请将连续24位数字放入框内"
        canvas.drawText(
            hintText,
            width / 2f,
            scanRect.bottom + 36f * resources.displayMetrics.density,
            tipTextPaint
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        laserAnimator?.cancel()
        laserAnimator = null
    }
}
