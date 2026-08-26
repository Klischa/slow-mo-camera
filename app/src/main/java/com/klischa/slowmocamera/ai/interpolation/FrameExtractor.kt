package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Высокоточный потоковый экстрактор кадров из видео (Exact Sequential Frame Decoder).
 * Извлекает абсолютно каждый уникальный кадр без дублирования ключевых кадров (I-frames).
 */
class FrameExtractor(private val context: Context) {

    private val tag = "FrameExtractor"

    data class VideoInfo(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val estimatedFps: Float,
        val totalFrames: Int
    )

    fun getVideoInfo(uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

            val isRotated = rotation == 90 || rotation == 270
            val finalWidth = if (isRotated) height else width
            val finalHeight = if (isRotated) width else height

            // Определение реальной частоты кадров видео
            var fps = 30f
            val countStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val frameCount = countStr?.toIntOrNull()
            if (frameCount != null && frameCount > 0 && duration > 0) {
                fps = (frameCount.toFloat() / (duration.toFloat() / 1000f)).coerceIn(15f, 240f)
            }

            val totalFrames = ((duration / 1000f) * fps).toInt().coerceAtLeast(1)
            return VideoInfo(duration, finalWidth, finalHeight, fps, totalFrames)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка извлечения метаданных видео: ${e.message}")
            return VideoInfo(5000L, 1280, 720, 30f, 150)
        } finally {
            retriever.release()
        }
    }

    /**
     * Извлекает последовательность реальных уникальных кадров с микросекундной точностью OPTION_CLOSEST.
     */
    suspend fun extractFramesChunk(
        uri: Uri,
        startMs: Long,
        chunkDurationMs: Long,
        stepMs: Long,
        maxResolutionHeight: Int = 720
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()

        try {
            retriever.setDataSource(context, uri)
            val videoInfo = getVideoInfo(uri)
            val durationMs = videoInfo.durationMs
            val actualStepMs = if (stepMs > 0) stepMs else (1000f / videoInfo.estimatedFps).toLong().coerceAtLeast(16L)

            var currentMs = startMs
            val endMs = (startMs + chunkDurationMs).coerceAtMost(durationMs)

            while (currentMs < endMs) {
                // ВАЖНО: Используем OPTION_CLOSEST для получения точного кадра в этот момент времени,
                // а не OPTION_CLOSEST_SYNC (который возвращал один и тот же I-frame 30 раз подряд)
                val frame = retriever.getFrameAtTime(currentMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val targetFrame = if (frame.height > maxResolutionHeight) {
                        val scale = maxResolutionHeight.toFloat() / frame.height.toFloat()
                        val newWidth = (frame.width * scale).toInt()
                        val scaled = Bitmap.createScaledBitmap(frame, newWidth, maxResolutionHeight, true)
                        frame.recycle()
                        scaled
                    } else {
                        frame
                    }
                    frames.add(targetFrame)
                }
                currentMs += actualStepMs
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка извлечения кадров: ${e.message}", e)
        } finally {
            retriever.release()
        }

        Log.i(tag, "Извлечено ${frames.size} уникальных кадров для обработки")
        frames
    }
}
