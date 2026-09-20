#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <android/log.h>
#include <android/bitmap.h>
#include "qrcodegen.hpp"

#define LOG_TAG "NativeScanner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

bool isAllDigits(const std::string& str) {
    if (str.empty()) return false;
    for (char c : str) {
        if (!std::isdigit(static_cast<unsigned char>(c))) {
            return false;
        }
    }
    return true;
}

// Extract strictly consecutive 24 digits from text, with boundary checking.
// Space-grouped or hyphenated numbers are strictly rejected.
// Returns empty string if no strictly consecutive 24-digit sequence is found.
std::string extract24DigitsInternal(const std::string& text) {
    if (text.length() < 24) {
        return "";
    }

    size_t n = text.length();

    // Only match strictly uninterrupted consecutive 24 digits [0-9]{24}
    for (size_t i = 0; i + 24 <= n; ++i) {
        if (std::isdigit(static_cast<unsigned char>(text[i]))) {
            // Ensure boundary before: must not be preceded by a digit
            if (i > 0 && std::isdigit(static_cast<unsigned char>(text[i - 1]))) {
                continue;
            }

            // Check if the 24 characters are all consecutive digits without any interruption
            bool all24 = true;
            for (size_t j = 0; j < 24; ++j) {
                if (!std::isdigit(static_cast<unsigned char>(text[i + j]))) {
                    all24 = false;
                    break;
                }
            }

            if (all24) {
                // Ensure boundary after: must not be followed by a digit (reject 25+ digits)
                if (i + 24 < n && std::isdigit(static_cast<unsigned char>(text[i + 24]))) {
                    continue;
                }
                return text.substr(i, 24);
            }
        }
    }

    return "";
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_mynative_myeiwema_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "C++ 24位数字识别与二维码生成器就绪";
    return env->NewStringUTF(hello.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mynative_myeiwema_MainActivity_findConsecutive24Digits(
        JNIEnv* env,
        jobject /* this */,
        jstring raw_text) {

    if (!raw_text) {
        return nullptr;
    }

    const char* nativeChars = env->GetStringUTFChars(raw_text, nullptr);
    if (!nativeChars) {
        return nullptr;
    }

    std::string text(nativeChars);
    env->ReleaseStringUTFChars(raw_text, nativeChars);

    std::string result = extract24DigitsInternal(text);
    if (result.empty()) {
        return nullptr;
    }

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_mynative_myeiwema_MainActivity_is24Digits(
        JNIEnv* env,
        jobject /* this */,
        jstring text) {

    if (!text) {
        return JNI_FALSE;
    }

    const char* nativeChars = env->GetStringUTFChars(text, nullptr);
    if (!nativeChars) {
        return JNI_FALSE;
    }

    std::string str(nativeChars);
    env->ReleaseStringUTFChars(text, nativeChars);

    return (str.length() == 24 && isAllDigits(str)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_mynative_myeiwema_MainActivity_generateQrCodeBitmap(
        JNIEnv* env,
        jobject /* this */,
        jstring text,
        jint width,
        jint height) {

    if (!text || width <= 0 || height <= 0) {
        return nullptr;
    }

    const char* nativeText = env->GetStringUTFChars(text, nullptr);
    if (!nativeText) {
        return nullptr;
    }

    std::string contentStr(nativeText);
    env->ReleaseStringUTFChars(text, nativeText);

    try {
        // Generate QR code with Medium ECC (15% error correction capability)
        qrcodegen::QrCode qr = qrcodegen::QrCode::encodeText(
                contentStr.c_str(), qrcodegen::QrCode::Ecc::MEDIUM);
        int qrSize = qr.getSize();

        // Find Bitmap and Bitmap.Config classes
        jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
        if (!bitmapClass) {
            LOGE("Cannot find android/graphics/Bitmap class");
            return nullptr;
        }

        jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
        if (!configClass) {
            LOGE("Cannot find android/graphics/Bitmap$Config class");
            return nullptr;
        }

        jfieldID argb8888Field = env->GetStaticFieldID(
                configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        if (!argb8888Field) {
            LOGE("Cannot find ARGB_8888 config field");
            return nullptr;
        }

        jobject configObj = env->GetStaticObjectField(configClass, argb8888Field);
        if (!configObj) {
            LOGE("Cannot get ARGB_8888 config object");
            return nullptr;
        }

        jmethodID createBitmapMethod = env->GetStaticMethodID(
                bitmapClass, "createBitmap",
                "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (!createBitmapMethod) {
            LOGE("Cannot find Bitmap.createBitmap method");
            return nullptr;
        }

        jobject bitmap = env->CallStaticObjectMethod(
                bitmapClass, createBitmapMethod, width, height, configObj);
        if (!bitmap) {
            LOGE("Bitmap.createBitmap returned null");
            return nullptr;
        }

        // Lock bitmap pixels using Android NDK jnigraphics
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
            LOGE("AndroidBitmap_getInfo failed");
            return nullptr;
        }

        void* pixelsPtr = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixelsPtr) < 0) {
            LOGE("AndroidBitmap_lockPixels failed");
            return nullptr;
        }

        // Margin of 4 modules per QR code specification
        int margin = 4;
        int totalModules = qrSize + 2 * margin;
        float moduleSize = static_cast<float>(std::min(width, height)) / static_cast<float>(totalModules);
        int offsetX = static_cast<int>((width - totalModules * moduleSize) / 2.0f);
        int offsetY = static_cast<int>((height - totalModules * moduleSize) / 2.0f);

        uint32_t* pixelData = reinterpret_cast<uint32_t*>(pixelsPtr);
        uint32_t colorBlack = 0xFF000000;
        uint32_t colorWhite = 0xFFFFFFFF;
        uint32_t stridePixels = info.stride / 4;

        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int moduleX = static_cast<int>((x - offsetX) / moduleSize) - margin;
                int moduleY = static_cast<int>((y - offsetY) / moduleSize) - margin;

                bool isDark = false;
                if (moduleX >= 0 && moduleX < qrSize && moduleY >= 0 && moduleY < qrSize) {
                    isDark = qr.getModule(moduleX, moduleY);
                }

                pixelData[y * stridePixels + x] = isDark ? colorBlack : colorWhite;
            }
        }

        AndroidBitmap_unlockPixels(env, bitmap);
        return bitmap;
    } catch (const std::exception& e) {
        LOGE("QR Code generation exception: %s", e.what());
        return nullptr;
    }
}

} // extern "C"