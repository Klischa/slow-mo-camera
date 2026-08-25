package com.klischa.slowmocamera.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ИИ-генератор хайлайтов (Auto Highlights).
 * Анализирует видеоряд, вычисляет кривую кинетической энергии и движения,
 * и находит ключевые экшн-кульминации для замедления и нарезки.
 */
class AutoHighlightsEngine(private val context: Context) {

    private val tag = "AutoHighlightsEngine"

    data class HighlightMoment(
        val startMs: Long,
        val peakMs: Long,
        val endMs: Long,
        val motionScore: Float,
        val recommendedSpeed: Float = 0.25f // 4x slow-mo на пике
    )

    /**
     * Анализирует видео и возвращает список лучших моментов.
     */
    suspend fun detectHighlights(
        videoUri: Uri,
        onProgress: (Int) -> Unit
    ): List<HighlightMoment> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val moments = mutableListOf<HighlightMoment>()

        try {
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: return@withContext emptyList()

            if (durationMs < 2000L) {
                // Если видео слишком короткое, всё видео является одним хайлайтом
                return@withContext listOf(HighlightMoment(0, durationMs / 2, durationMs, 100f))
            }

            val stepMs = 250L // Анализ 4 раза в секунду
            val energyScores = mutableListOf<Pair<Long, Float>>()

            var prevFrame: Bitmap? = null
            var timeMs = 0L

            while (timeMs < durationMs) {
                val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                if (frame != null && prevFrame != null) {
                    val score = calculateFrameDifference(prevFrame, frame)
                    energyScores.add(Pair(timeMs, score))
                }

                prevFrame?.recycle()
                prevFrame = frame
                timeMs += stepMs

                val progress = ((timeMs.toFloat() / durationMs.toFloat()) * 100).toInt().coerceIn(0, 100)
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }
            prevFrame?.recycle()

            if (energyScores.isEmpty()) return@withContext emptyList()

            // Поиск пиков энергии (выше среднего значения на 50%)
            val avgScore = energyScores.map { it.second }.average().toFloat()
            val threshold = avgScore * 1.3f

            val peakCandidates = energyScores.filter { it.second >= threshold }
                .sortedByDescending { it.second }

            // Выбираем топ-3 непересекающихся момента
            val selectedPeaks = mutableListOf<Long>()
            for (peak in peakCandidates) {
                val peakTime = peak.first
                if (selectedPeaks.none { Math.abs(it - peakTime) < 2000L }) {
                    selectedPeaks.add(peakTime)
                    val start = (peakTime - 1000L).coerceAtLeast(0L)
                    val end = (peakTime + 1500L).coerceAtMost(durationMs)
                    moments.add(
                        HighlightMoment(
                            startMs = start,
                            peakMs = peakTime,
                            endMs = end,
                            motionScore = peak.second
                        )
                    )
                    if (moments.size >= 3) break
                }
            }

            moments.sortBy { it.startMs }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка детекции хайлайтов: ${e.message}", e)
        } finally {
            retriever.release()
        }

        moments
    }

    private fun calculateFrameDifference(b1: Bitmap, b2: Bitmap): Float {
        val sampleW = 64
        val sampleH = 64
        val scaled1 = Bitmap.createScaledBitmap(b1, sampleW, sampleH, false)
        val scaled2 = Bitmap.createScaledBitmap(b2, sampleW, sampleH, false)

        var diffSum = 0L
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val p1 = scaled1.getPixel(x, y)
                val p2 = scaled2.getPixel(x, y)
                val l1 = (Color.red(p1) + Color.green(p1) + Color.blue(p1)) / 3
                val l2 = (Color.red(p2) + Color.green(p2) + Color.blue(p2)) / 3
                diffSum += Math.abs(l1 - l2)
            }
        }
        scaled1.recycle()
        scaled2.recycle()

        return diffSum.toFloat() / (sampleW * sampleH).toFloat()
    }
}
