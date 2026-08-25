package com.klischa.slowmocamera.stabilization

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import java.io.File

/**
 * Главный фасад управления стабилизацией (Pattern Facade).
 * Объединяет все 4 режима: аппаратную OIS/EIS, OpenCV трекинг, FFmpeg vid.stab и Gyroflow.
 */
class StabilizationManager(private val context: Context) {

    val cameraStabilizer = CameraStabilizer()
    val sensorRecorder = SensorRecorder(context)
    val openCvStabilizer = OpenCVStabilizer(context)
    val ffmpegStabilizer = FFmpegStabilizer(context)
    val gyroflowStabilizer = GyroflowStabilizer(context)

    var currentParams: StabilizationParams = StabilizationParams()

    fun inspectHardwareCapabilities(characteristics: CameraCharacteristics): CameraStabilizer.StabilizationCapability {
        return cameraStabilizer.inspectCapabilities(characteristics)
    }

    fun applyHardwareStabilization(
        builder: CaptureRequest.Builder,
        capabilities: CameraStabilizer.StabilizationCapability,
        isEnabled: Boolean
    ) {
        cameraStabilizer.applyStabilization(builder, capabilities, isEnabled)
    }

    suspend fun processStabilization(
        videoUri: Uri,
        telemetryFile: File? = null,
        params: StabilizationParams,
        onProgress: suspend (Int) -> Unit
    ): Uri? {
        return when (params.mode) {
            StabilizationMode.AUTO, StabilizationMode.FFMPEG_VIDSTAB -> {
                ffmpegStabilizer.stabilizeVideo(videoUri, params, onProgress)
            }
            StabilizationMode.OPENCV_TRACKING -> {
                openCvStabilizer.stabilizeVideo(videoUri, params, onProgress)
            }
            StabilizationMode.GYROFLOW -> {
                gyroflowStabilizer.stabilizeWithTelemetry(videoUri, telemetryFile, params, onProgress)
            }
            StabilizationMode.HARDWARE_OIS_EIS -> {
                // Если вызвано для постобработки видео
                openCvStabilizer.stabilizeVideo(videoUri, params, onProgress)
            }
        }
    }
}
