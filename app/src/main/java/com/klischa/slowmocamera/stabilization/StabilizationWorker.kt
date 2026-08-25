package com.klischa.slowmocamera.stabilization

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Фоновый исполнитель WorkManager для стабилизации видео (OpenCV, FFmpeg vid.stab, Gyroflow).
 */
class StabilizationWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val inputUriString = inputData.getString(KEY_INPUT_URI) ?: return Result.failure()
        val modeName = inputData.getString(KEY_MODE) ?: StabilizationMode.AUTO.name
        val mode = StabilizationMode.valueOf(modeName)
        val shakiness = inputData.getInt(KEY_SHAKINESS, 6)
        val smoothing = inputData.getInt(KEY_SMOOTHING, 30)
        val crop = inputData.getFloat(KEY_CROP, 0.9f)

        val params = StabilizationParams(
            mode = mode,
            shakiness = shakiness,
            smoothing = smoothing,
            cropPercentage = crop
        )

        val inputUri = Uri.parse(inputUriString)
        val manager = StabilizationManager(appContext)

        try {
            setProgress(workDataOf(KEY_PROGRESS to 5, KEY_STATUS_TEXT to "Анализ тряски камеры..."))

            val resultUri = manager.processStabilization(
                videoUri = inputUri,
                params = params
            ) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to progress,
                        KEY_STATUS_TEXT to "Стабилизация видео: $progress%"
                    )
                )
            }

            return if (resultUri != null) {
                setProgress(workDataOf(KEY_PROGRESS to 100, KEY_STATUS_TEXT to "Готово!"))
                Result.success(workDataOf(KEY_OUTPUT_URI to resultUri.toString()))
            } else {
                Result.failure(workDataOf(KEY_STATUS_TEXT to "Ошибка стабилизации"))
            }
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_STATUS_TEXT to "Ошибка: ${e.message}"))
        }
    }

    companion object {
        const val KEY_INPUT_URI = "key_input_uri"
        const val KEY_MODE = "key_mode"
        const val KEY_SHAKINESS = "key_shakiness"
        const val KEY_SMOOTHING = "key_smoothing"
        const val KEY_CROP = "key_crop"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_STATUS_TEXT = "key_status_text"
        const val KEY_OUTPUT_URI = "key_output_uri"
    }
}
