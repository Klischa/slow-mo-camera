package com.klischa.slowmocamera.stabilization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.klischa.slowmocamera.ai.interpolation.FrameExtractor
import com.klischa.slowmocamera.ai.interpolation.VideoAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Гироскопический стабилизатор видео (Gyroflow IMU Smoothing).
 * Компенсирует физическое вращение камеры на основе данных аппаратного гироскопа.
 */
class GyroflowStabilizer(private val context: Context) {

    private val tag = "GyroflowStabilizer"

    suspend fun stabilizeWithTelemetry(
        videoUri: Uri,
        telemetryFile: File?,
        params: StabilizationParams,
        onProgress: suspend (Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val frameExtractor = FrameExtractor(context)
        val videoInfo = frameExtractor.getVideoInfo(videoUri)
        val durationMs = videoInfo.durationMs
        val baseFps = videoInfo.estimatedFps.toInt().coerceIn(15, 240)
        val stepMs = (1000f / baseFps).toLong().coerceAtLeast(16L)

        onProgress(15)

        val frames = frameExtractor.extractFramesChunk(
            uri = videoUri,
            startMs = 0,
            chunkDurationMs = durationMs,
            stepMs = stepMs,
            maxResolutionHeight = 720
        )

        if (frames.size < 3) return@withContext null

        onProgress(40)

        // Читаем телеметрию если доступна
        val gyroAngles = mutableListOf<Float>()
        if (telemetryFile != null && telemetryFile.exists()) {
            telemetryFile.readLines().drop(1).forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 4) {
                    val gz = parts[3].toFloatOrNull() ?: 0f
                    gyroAngles.add(gz * 57.2958f) // радиан в градусы
                }
            }
        }

        onProgress(60)

        val stabilizedFrames = mutableListOf<Bitmap>()
        val cropFactor = params.cropPercentage.coerceIn(0.85f, 0.96f)
        val scaleFactor = 1.0f / cropFactor

        for ((i, frame) in frames.withIndex()) {
            val angle = if (i < gyroAngles.size) (-gyroAngles[i] * 0.05f).coerceIn(-4f, 4f) else 0f
            val output = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val matrix = Matrix().apply {
                postRotate(angle, frame.width / 2f, frame.height / 2f)
                postScale(scaleFactor, scaleFactor, frame.width / 2f, frame.height / 2f)
            }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(frame, matrix, paint)
            stabilizedFrames.add(output)
        }

        onProgress(85)

        val assembler = VideoAssembler(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputName = "SlowMo_Stabilized_Gyroflow_${timestamp}.mp4"

        val outputUri = assembler.assembleFramesToMp4(
            frames = stabilizedFrames,
            targetFps = baseFps,
            outputFileName = outputName
        )

        onProgress(100)
        outputUri
    }
}
