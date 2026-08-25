package com.klischa.slowmocamera.camera

import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import android.util.Size

/**
 * Хранилище параметров и логики для ручных PRO-настроек камеры (ISO, Выдержка, WB, Фокус).
 */
data class CameraManualControls(
    var isAutoIso: Boolean = true,
    var manualIso: Int = 100,
    var isoRange: Range<Int> = Range(100, 3200),

    var isAutoExposure: Boolean = true,
    var manualExposureTimeNs: Long = 10_000_000L, // 1/100s
    var exposureTimeRange: Range<Long> = Range(100_000L, 1_000_000_000L),

    var exposureCompensation: Int = 0,
    var exposureCompensationRange: Range<Int> = Range(-4, 4),

    var whiteBalanceMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,

    var isAutoFocus: Boolean = true,
    var manualFocusDistance: Float = 0.0f, // 0.0 = infinity, max = closest
    var minFocusDistance: Float = 10.0f,

    var focusMeteringArea: MeteringRectangle? = null,
    var exposureMeteringArea: MeteringRectangle? = null
) {
    /**
     * Применяет текущие ручные настройки к CaptureRequest.Builder.
     */
    fun applyToBuilder(builder: CaptureRequest.Builder) {
        // Фокусировка
        if (isAutoFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            focusMeteringArea?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        }

        // Экспозиция и ISO
        if (isAutoExposure && isAutoIso) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            exposureMeteringArea?.let {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs)
        }

        // Баланс белого
        builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalanceMode)
    }

    companion object {
        /**
         * Вычисляет MeteringRectangle для Tap-to-focus точки.
         */
        fun calculateFocusArea(
            tapPoint: PointF,
            viewSize: Size,
            sensorArray: Rect?,
            sensorOrientation: Int,
            isFrontCamera: Boolean
        ): MeteringRectangle {
            val active = sensorArray ?: Rect(0, 0, 1920, 1080)
            val boxSize = 200

            val normX = (tapPoint.x / viewSize.width.toFloat()).coerceIn(0f, 1f)
            val normY = (tapPoint.y / viewSize.height.toFloat()).coerceIn(0f, 1f)

            val centerX = (normX * active.width()).toInt() + active.left
            val centerY = (normY * active.height()).toInt() + active.top

            val left = (centerX - boxSize / 2).coerceIn(active.left, active.right - boxSize)
            val top = (centerY - boxSize / 2).coerceIn(active.top, active.bottom - boxSize)
            val rect = Rect(left, top, left + boxSize, top + boxSize)

            return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
        }
    }
}
