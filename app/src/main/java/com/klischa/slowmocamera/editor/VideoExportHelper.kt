package com.klischa.slowmocamera.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.klischa.slowmocamera.data.OutputFormatType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сервис экспорта и обработки видео через AndroidX Media3 Transformer:
 * обрезка (Trim), изменение скорости (Slow-Mo / Speedup), наложение музыки, выбор кодека MP4/WebM.
 */
class VideoExportHelper(private val context: Context) {

    private val tag = "VideoExportHelper"
    private var currentTransformer: Transformer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface ExportProgressListener {
        fun onProgress(percentage: Int)
        fun onCompleted(outputUri: Uri)
        fun onError(error: String)
    }

    data class EditOptions(
        val inputUri: Uri,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val speedFactor: Float = 1.0f,
        val isMuteOriginalAudio: Boolean = false,
        val backgroundMusicUri: Uri? = null,
        val musicVolume: Float = 1.0f,
        val format: OutputFormatType = OutputFormatType.MP4_H264,
        val targetResolutionHeight: Int = 1080 // 1080p, 720p, 480p
    )

    fun exportVideo(options: EditOptions, listener: ExportProgressListener) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outputFileName = "SlowMo_Edited_${timestamp}.${options.format.extension}"
            val tempOutputFile = File(context.cacheDir, outputFileName)
            if (tempOutputFile.exists()) tempOutputFile.delete()

            // 1. Формирование медиа-элемента с обрезкой (Trim)
            val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(options.trimStartMs)
                .apply {
                    if (options.trimEndMs > options.trimStartMs) {
                        setEndPositionMs(options.trimEndMs)
                    }
                }
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(options.inputUri)
                .setClippingConfiguration(clippingConfiguration)
                .build()

            // 2. Настройка видеоэффектов и аудиопроцессора
            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()

            // Изменение скорости звука при Slow-Mo
            if (options.speedFactor != 1.0f && !options.isMuteOriginalAudio) {
                val speedAudioProcessor = SpeedChangingAudioProcessor()
                speedAudioProcessor.setSpeed(options.speedFactor)
                audioProcessors.add(speedAudioProcessor)
            }

            val effects = Effects(audioProcessors, videoEffects)

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(options.isMuteOriginalAudio)
                .setEffects(effects)
                .build()

            // 3. Выбор кодека и контейнера
            val videoMimeType = if (options.format == OutputFormatType.WEBM_VP9) {
                MimeTypes.VIDEO_VP9
            } else {
                MimeTypes.VIDEO_H264
            }

            val transformerListener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mainHandler.post {
                        val savedUri = saveToMediaStore(tempOutputFile, outputFileName, options.format.mimeType)
                        tempOutputFile.delete()
                        if (savedUri != null) {
                            listener.onCompleted(savedUri)
                        } else {
                            listener.onError("Не удалось опубликовать видео в MediaStore")
                        }
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    mainHandler.post {
                        Log.e(tag, "Ошибка Media3 Transformer: ${exportException.message}", exportException)
                        tempOutputFile.delete()
                        listener.onError("Ошибка экспорта: ${exportException.message}")
                    }
                }
            }

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .addListener(transformerListener)
                .build()

            currentTransformer = transformer

            // Запуск трансформации
            transformer.start(editedMediaItem, tempOutputFile.absolutePath)

            // Отслеживание прогресса
            trackProgress(transformer, listener)

        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска экспорта: ${e.message}", e)
            listener.onError("Ошибка запуска экспорта: ${e.message}")
        }
    }

    private fun trackProgress(transformer: Transformer, listener: ExportProgressListener) {
        val progressHolder = ProgressHolder()
        val progressRunnable = object : Runnable {
            override fun run() {
                val progressState = transformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    listener.onProgress(progressHolder.progress)
                    mainHandler.postDelayed(this, 300)
                } else if (progressState == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                    mainHandler.postDelayed(this, 300)
                }
            }
        }
        mainHandler.postDelayed(progressRunnable, 300)
    }

    private fun saveToMediaStore(file: File, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SlowMoCamera_Edited")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        return uri
    }

    fun cancelExport() {
        currentTransformer?.cancel()
        currentTransformer = null
    }
}
