package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Потоковый экстрактор кадров из видео (Stream Chunked Frame Extractor).
 * Читает кадры последовательно порциями, предотвращая переполнение памяти (OOM).
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

            val fps = 30f // Базовый FPS
            val totalFrames = ((duration / 1000f) * fps).toInt().coerceAtLeast(1)

            return VideoInfo(duration, finalWidth, finalHeight, fps, totalFrames)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка извлечения метаданных видео: ${e.message}")
            return VideoInfo(5000L, 1280, 720, 30f, 150)
        } finally {
            retriever.release()
        }
    }

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
            var currentMs = startMs
            val endMs = startMs + chunkDurationMs

            while (currentMs < endMs) {
                val frame = retriever.getFrameAtTime(currentMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    // Ограничиваем разрешение до 720p для Mali-G57 GPU
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
                currentMs += stepMs
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка извлечения чанка кадров: ${e.message}", e)
        } finally {
            retriever.release()
        }

        frames
    }
}
