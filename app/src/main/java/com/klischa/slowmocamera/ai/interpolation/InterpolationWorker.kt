package com.klischa.slowmocamera.ai.interpolation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Фоновый исполнитель WorkManager для нейросетевой интерполяции RIFE/NCNN.
 * Обеспечивает фоновую обработку, отслеживание прогресса и устойчивость к закрытию UI.
 */
class InterpolationWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val tag = "InterpolationWorker"

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        val targetFps = inputData.getInt(KEY_TARGET_FPS, 120)
        val modeName = inputData.getString(KEY_MODE) ?: InterpolationMode.QUALITY.name
        val mode = InterpolationMode.valueOf(modeName)
        val multiplier = inputData.getInt(KEY_MULTIPLIER, 4)

        val inputUri = Uri.parse(inputUriString)
        val frameExtractor = FrameExtractor(appContext)
        val assembler = VideoAssembler(appContext)
        val engine = InterpolationStrategyFactory.createEngine(appContext, mode)
        val thermalMonitor = ThermalMonitor(appContext)

        try {
            setProgress(workDataOf(KEY_PROGRESS to 5, KEY_STATUS_TEXT to "Инициализация AI-движка ${engine.engineName}..."))
            engine.initialize(appContext)

            val videoInfo = frameExtractor.getVideoInfo(inputUri)
            val durationMs = videoInfo.durationMs
            val baseFps = videoInfo.estimatedFps

            val chunkDurationMs = 2000L // Чанки по 2 секунды
            val stepMs = (1000f / baseFps).toLong().coerceAtLeast(16L)

            val totalOutputFrames = mutableListOf<Bitmap>()
            var processedTimeMs = 0L

            while (processedTimeMs < durationMs) {
                if (isStopped) {
                    engine.release()
                    return Result.failure()
                }

                // Проверка температуры
                if (!thermalMonitor.isSafeToRunNeuralInference()) {
                    setProgress(workDataOf(KEY_STATUS_TEXT to "⚠️ Охлаждение чипсета Helio G99 (пауза 2 сек)..."))
                    kotlinx.coroutines.delay(2000)
                }

                val originalFrames = frameExtractor.extractFramesChunk(
                    uri = inputUri,
                    startMs = processedTimeMs,
                    chunkDurationMs = chunkDurationMs,
                    stepMs = stepMs
                )

                if (originalFrames.isEmpty()) break

                // Нейросетевая интерполяция кадров в чанке
                for (i in 0 until originalFrames.size - 1) {
                    val frameA = originalFrames[i]
                    val frameB = originalFrames[i + 1]
                    totalOutputFrames.add(frameA)

                    for (step in 1 until multiplier) {
                        val timestep = step.toFloat() / multiplier.toFloat()
                        val interpolated = engine.interpolate(frameA, frameB, timestep)
                        totalOutputFrames.add(interpolated)
                    }
                }

                if (originalFrames.isNotEmpty()) {
                    totalOutputFrames.add(originalFrames.last())
                }

                processedTimeMs += chunkDurationMs
                val progressPercent = ((processedTimeMs.toFloat() / durationMs.toFloat()) * 80).toInt().coerceIn(5, 80)
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to progressPercent,
                        KEY_STATUS_TEXT to "Интерполяция кадров: $progressPercent% (${totalOutputFrames.size} кадров)",
                        KEY_CURRENT_FRAME to totalOutputFrames.size
                    )
                )
            }

            setProgress(workDataOf(KEY_PROGRESS to 85, KEY_STATUS_TEXT to "Кодирование MP4 ($targetFps FPS)..."))

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "SlowMo_AI_${targetFps}fps_${timestamp}.mp4"

            val outputUri = assembler.assembleFramesToMp4(
                frames = totalOutputFrames,
                targetFps = targetFps,
                outputFileName = outputFileName
            )

            engine.release()

            return if (outputUri != null) {
                setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS_TEXT to "Готово!"))
                Result.success(workDataOf(KEY_OUTPUT_URI to outputUri.toString()))
            } else {
                Result.failure(workDataOf(KEY_STATUS_TEXT to "Ошибка сборки видео"))
            }

        } catch (e: Exception) {
            Log.e(tag, "Ошибка в InterpolationWorker: ${e.message}", e)
            engine.release()
            return Result.failure(workDataOf(KEY_STATUS_TEXT to "Ошибка: ${e.message}"))
        }
    }

    companion object {
        const val KEY_INPUT_URI = "key_input_uri"
        const val KEY_TARGET_FPS = "key_target_fps"
        const val KEY_MODE = "key_mode"
        const val KEY_MULTIPLIER = "key_multiplier"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_STATUS_TEXT = "key_status_text"
        const val KEY_CURRENT_FRAME = "key_current_frame"
        const val KEY_OUTPUT_URI = "key_output_uri"
    }
}
