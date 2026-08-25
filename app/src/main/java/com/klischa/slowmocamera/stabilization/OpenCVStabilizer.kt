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
 * Стабилизатор видео на основе алгоритмов трекинга точек и сглаживания траекторий (OpenCV-совместимый пайплайн).
 */
class OpenCVStabilizer(private val context: Context) {

    private val tag = "OpenCVStabilizer"

    data class TransformParams(var dx: Float, var dy: Float, var da: Float)
    data class TrajectoryPoint(var x: Float, var y: Float, var a: Float)

    suspend fun stabilizeVideo(
        inputUri: Uri,
        params: StabilizationParams,
        onProgress: (Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val frameExtractor = FrameExtractor(context)
        val videoInfo = frameExtractor.getVideoInfo(inputUri)
        val durationMs = videoInfo.durationMs
        val baseFps = videoInfo.estimatedFps.toInt().coerceAtLeast(30)

        val stepMs = (1000f / baseFps).toLong().coerceAtLeast(16L)

        // 1. Извлечение всех кадров
        val frames = frameExtractor.extractFramesChunk(
            uri = inputUri,
            startMs = 0,
            chunkDurationMs = durationMs,
            stepMs = stepMs,
            maxResolutionHeight = 720
        )

        if (frames.size < 2) return@withContext null

        onProgress(20)

        // 2. Оценка межпокадровых смещений (dx, dy, da)
        val transforms = mutableListOf<TransformParams>()
        for (i in 0 until frames.size - 1) {
            val trans = estimateFrameDisplacement(frames[i], frames[i + 1])
            transforms.add(trans)
        }

        onProgress(40)

        // 3. Вычисление накопленной траектории
        val trajectory = mutableListOf<TrajectoryPoint>()
        var accX = 0f
        var accY = 0f
        var accA = 0f
        trajectory.add(TrajectoryPoint(0f, 0f, 0f))

        for (t in transforms) {
            accX += t.dx
            accY += t.dy
            accA += t.da
            trajectory.add(TrajectoryPoint(accX, accY, accA))
        }

        // 4. Сглаживание траектории (Скользящее среднее с окном smoothing)
        val smoothedTrajectory = smoothTrajectory(trajectory, params.smoothing)

        onProgress(60)

        // 5. Применение стабилизирующей трансформации с кропом
        val stabilizedFrames = mutableListOf<Bitmap>()
        val scaleFactor = 1.0f / params.cropPercentage.coerceIn(0.7f, 1.0f) // Кроп для компенсации черных краев

        for (i in frames.indices) {
            val diffX = smoothedTrajectory[i].x - trajectory[i].x
            val diffY = smoothedTrajectory[i].y - trajectory[i].y
            val diffA = smoothedTrajectory[i].a - trajectory[i].a

            val stabilized = warpBitmap(frames[i], diffX, diffY, diffA, scaleFactor)
            stabilizedFrames.add(stabilized)
        }

        onProgress(80)

        // 6. Сборка стабилизированного MP4
        val assembler = VideoAssembler(context)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputName = "SlowMo_Stabilized_OpenCV_${timestamp}.mp4"

        val outputUri = assembler.assembleFramesToMp4(
            frames = stabilizedFrames,
            targetFps = baseFps,
            outputFileName = outputName
        )

        onProgress(100)
        outputUri
    }

    private fun estimateFrameDisplacement(b1: Bitmap, b2: Bitmap): TransformParams {
        val sampleW = 64
        val sampleH = 64
        val s1 = Bitmap.createScaledBitmap(b1, sampleW, sampleH, false)
        val s2 = Bitmap.createScaledBitmap(b2, sampleW, sampleH, false)

        var bestDx = 0
        var bestDy = 0
        var minDiff = Long.MAX_VALUE

        val search = 6
        for (dy in -search..search) {
            for (dx in -search..search) {
                var diff = 0L
                for (y in 8 until sampleH - 8) {
                    for (x in 8 until sampleW - 8) {
                        val p1 = s1.getPixel(x, y) and 0xFF
                        val p2 = s2.getPixel(x + dx, y + dy) and 0xFF
                        diff += Math.abs(p1 - p2)
                    }
                }
                if (diff < minDiff) {
                    minDiff = diff
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        s1.recycle()
        s2.recycle()

        val scaleBack = b1.width.toFloat() / sampleW.toFloat()
        return TransformParams(bestDx * scaleBack, bestDy * scaleBack, 0f)
    }

    private fun smoothTrajectory(trajectory: List<TrajectoryPoint>, radius: Int): List<TrajectoryPoint> {
        val smoothed = mutableListOf<TrajectoryPoint>()
        val rad = (radius / 2).coerceIn(2, 20)

        for (i in trajectory.indices) {
            var sumX = 0f
            var sumY = 0f
            var sumA = 0f
            var count = 0

            for (j in -rad..rad) {
                val idx = i + j
                if (idx in trajectory.indices) {
                    sumX += trajectory[idx].x
                    sumY += trajectory[idx].y
                    sumA += trajectory[idx].a
                    count++
                }
            }

            smoothed.add(TrajectoryPoint(sumX / count, sumY / count, sumA / count))
        }

        return smoothed
    }

    private fun warpBitmap(source: Bitmap, dx: Float, dy: Float, da: Float, scale: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val matrix = Matrix().apply {
            postTranslate(dx, dy)
            postRotate(da, source.width / 2f, source.height / 2f)
            postScale(scale, scale, source.width / 2f, source.height / 2f)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }
}
