package com.klischa.slowmocamera.ai.interpolation

import android.graphics.Bitmap
import android.util.Log

/**
 * JNI-обёртка вокруг библиотеки rife-ncnn-vulkan.
 * Обеспечивает аппаратный инференс RIFE на GPU через Vulkan 1.3 (Mali-G57 MP2).
 */
class RifeNcnnWrapper {

    private val tag = "RifeNcnnWrapper"
    var isNativeLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("rife_ncnn_jni")
            isNativeLoaded = true
            Log.i(tag, "Нативная библиотека librife_ncnn_jni успешно загружена с поддержкой Vulkan!")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(tag, "librife_ncnn_jni не найдена в системе, используется встроенный GPU/CPU движок: ${e.message}")
            isNativeLoaded = false
        }
    }

    external fun nativeInit(modelPath: String, gpuid: Int, ttaMode: Boolean, numThreads: Int): Boolean
    external fun nativeProcess(bitmapA: Bitmap, bitmapB: Bitmap, bitmapOut: Bitmap, timestep: Float): Boolean
    external fun nativeRelease()

    fun isVulkanSupported(): Boolean {
        return isNativeLoaded
    }
}
