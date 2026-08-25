package com.klischa.slowmocamera.stabilization

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log

/**
 * Управляет аппаратной (OIS) и электронной (EIS) стабилизацией камеры в реальном времени через Camera2 API.
 */
class CameraStabilizer {

    private val tag = "CameraStabilizer"

    data class StabilizationCapability(
        val isVideoStabilizationSupported: Boolean,
        val isPreviewStabilizationSupported: Boolean,
        val isOpticalStabilizationSupported: Boolean
    )

    fun inspectCapabilities(characteristics: CameraCharacteristics): StabilizationCapability {
        val videoModes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val hasVideoStab = videoModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)

        var hasPreviewStab = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPreviewStab = videoModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
        }

        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        val hasOis = oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

        Log.i(tag, "Аппаратная стабилизация: Video EIS=$hasVideoStab, Preview EIS=$hasPreviewStab, Optical OIS=$hasOis")
        return StabilizationCapability(hasVideoStab, hasPreviewStab, hasOis)
    }

    /**
     * Применяет наилучший доступный режим аппаратной стабилизации к CaptureRequest.Builder.
     */
    fun applyStabilization(
        builder: CaptureRequest.Builder,
        capabilities: StabilizationCapability,
        isEnabled: Boolean
    ) {
        if (!isEnabled) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            return
        }

        // 1. Приоритет: Preview Stabilization (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && capabilities.isPreviewStabilizationSupported) {
            try {
                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                )
                Log.d(tag, "Включен CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION")
            } catch (e: Exception) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            }
        } else if (capabilities.isVideoStabilizationSupported) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            Log.d(tag, "Включен CONTROL_VIDEO_STABILIZATION_MODE_ON")
        }

        // 2. Включение оптической стабилизации (OIS) если поддерживается сенсором
        if (capabilities.isOpticalStabilizationSupported) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            Log.d(tag, "Включен LENS_OPTICAL_STABILIZATION_MODE_ON")
        }
    }
}
