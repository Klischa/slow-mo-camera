package com.klischa.slowmocamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log

/**
 * Утилита для работы с проприетарными Vendor Tags чипсетов MediaTek (MT6789 / Helio G99).
 * Позволяет активировать высокоскоростной режим даже если стандартный CONSTRAINED_HIGH_SPEED_VIDEO
 * заблокирован в публичном AOSP HAL.
 */
object MtkVendorTagHelper {

    private const val TAG = "MtkVendorTagHelper"

    // Известные vendor-теги MediaTek для высокоскоростной съемки и Slow-Mo
    private val MTK_SLOW_MO_KEY_NAMES = listOf(
        "com.mediatek.streaming.vidslowmotion",
        "com.mediatek.highspeed.fps",
        "com.mediatek.configure.highspeed",
        "com.mediatek.camerago.hfps",
        "com.mediatek.control.capture.fps",
        "vendor.mediatek.slowmotion.fps",
        "com.transsion.camera.slowmotion"
    )

    /**
     * Поиск доступных вендорных ключей MediaTek в характеристиках камеры.
     */
    fun findMtkVendorKeys(characteristics: CameraCharacteristics): List<String> {
        val availableKeys = mutableListOf<String>()
        try {
            val keys = characteristics.availableCaptureRequestKeys
            for (key in keys) {
                if (MTK_SLOW_MO_KEY_NAMES.any { key.name.contains(it, ignoreCase = true) }) {
                    availableKeys.add(key.name)
                    Log.i(TAG, "Найден MTK Vendor Tag: ${key.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка сканирования Vendor Keys: ${e.message}")
        }
        return availableKeys
    }

    /**
     * Применение MediaTek Vendor Tags для принудительного включения 120/240 FPS в CaptureRequest.
     */
    fun applyMtkSlowMoVendorTags(builder: CaptureRequest.Builder, targetFps: Int) {
        // Пробуем применить динамически через Reflection CaptureRequest.Key
        for (keyName in MTK_SLOW_MO_KEY_NAMES) {
            try {
                // Попытка записать Int
                val intKey = createCaptureRequestKey<Int>(keyName, Int::class.javaPrimitiveType ?: Int::class.java)
                if (intKey != null) {
                    builder.set(intKey, targetFps)
                    Log.d(TAG, "Установлен MTK Vendor Tag [Int]: $keyName = $targetFps")
                }
            } catch (ignored: Exception) {}

            try {
                // Попытка записать IntArray [fps, fps]
                val intArrayKey = createCaptureRequestKey<IntArray>(keyName, IntArray::class.java)
                if (intArrayKey != null) {
                    builder.set(intArrayKey, intArrayOf(targetFps, targetFps))
                    Log.d(TAG, "Установлен MTK Vendor Tag [IntArray]: $keyName = [$targetFps, $targetFps]")
                }
            } catch (ignored: Exception) {}
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> createCaptureRequestKey(name: String, type: Class<*>): CaptureRequest.Key<T>? {
        return try {
            val constructor = CaptureRequest.Key::class.java.getDeclaredConstructor(String::class.java, Class::class.java)
            constructor.isAccessible = true
            constructor.newInstance(name, type) as CaptureRequest.Key<T>
        } catch (e: Exception) {
            null
        }
    }
}
