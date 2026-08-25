package com.klischa.slowmocamera.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.klischa.slowmocamera.data.OutputFormatType
import com.klischa.slowmocamera.data.RecordingMode
import com.klischa.slowmocamera.data.VideoConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Управляет жизненным циклом MediaRecorder для высокоскоростной записи Slow-Mo (HFR/HSR).
 */
class MediaRecorderHelper(private val context: Context) {

    private val tag = "MediaRecorderHelper"

    private var mediaRecorder: MediaRecorder? = null
    private var currentPfd: ParcelFileDescriptor? = null
    private var currentUri: Uri? = null
    private var tempFallbackFile: File? = null
    private var recorderSurface: Surface? = null

    var isRecording: Boolean = false
        private set

    /**
     * Создает и подготавливает MediaRecorder согласно VideoConfig.
     * Возвращает Surface для передачи в сессию Camera2.
     */
    fun setupRecorder(config: VideoConfig): Surface {
        release()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "SlowMo_${timestamp}_${config.mode.name}_${config.profile.fps}fps.${config.format.extension}"

        // Настройка источников
        val canIncludeAudio = config.includeAudio && config.mode == RecordingMode.HSR
        if (canIncludeAudio) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            } catch (e: Exception) {
                Log.w(tag, "Не удалось установить AudioSource CAMCORDER: ${e.message}")
            }
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        // Выходной контейнер
        recorder.setOutputFormat(config.format.outputFormat)

        // Настройка видеокодека
        recorder.setVideoEncoder(config.format.videoEncoder)
        recorder.setVideoSize(config.profile.size.width, config.profile.size.height)
        recorder.setVideoEncodingBitRate(config.profile.recommendedBitRate)

        // Настройка частоты кадров в зависимости от режима:
        when (config.mode) {
            RecordingMode.HFR -> {
                // HFR: частота контейнера 30 кадров/сек, скорость захвата сенсора = high FPS (120 или 240)
                // Результат: видео сохраняется как 30fps замедленное (slow motion)
                recorder.setVideoFrameRate(30)
                recorder.setCaptureRate(config.profile.fps.toDouble())
            }
            RecordingMode.HSR -> {
                // HSR: частота контейнера = high FPS (120 или 240), скорость захвата = high FPS
                // Результат: видео сохраняется с полной кадровой частотой 120/240 fps
                recorder.setVideoFrameRate(config.profile.fps)
                recorder.setCaptureRate(config.profile.fps.toDouble())
            }
        }

        if (canIncludeAudio) {
            if (config.format == OutputFormatType.MP4_H264) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(48_000)
            } else {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            }
        }

        // Подготовка целевого файла через Scoped Storage (Android 10 - 14)
        setupOutputFile(recorder, fileName, config.format.mimeType)

        recorder.prepare()
        mediaRecorder = recorder

        val surface = recorder.surface
        recorderSurface = surface
        return surface
    }

    private fun setupOutputFile(recorder: MediaRecorder, fileName: String, mimeType: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SlowMoCamera")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (videoUri != null) {
            currentUri = videoUri
            val pfd = resolver.openFileDescriptor(videoUri, "rw")
            if (pfd != null) {
                currentPfd = pfd
                recorder.setOutputFile(pfd.fileDescriptor)
                return
            }
        }

        // Фолбэк на запись в локальную директорию приложения при проблемах с MediaStore
        val movieDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SlowMoCamera")
        if (!movieDir.exists()) movieDir.mkdirs()
        val file = File(movieDir, fileName)
        tempFallbackFile = file
        recorder.setOutputFile(file.absolutePath)
    }

    fun start() {
        try {
            mediaRecorder?.start()
            isRecording = true
            Log.i(tag, "MediaRecorder успешно запущен")
        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска MediaRecorder: ${e.message}", e)
            throw e
        }
    }

    fun stop(): Uri? {
        if (!isRecording) return null
        var resultUri = currentUri

        try {
            mediaRecorder?.stop()
            Log.i(tag, "MediaRecorder остановлен")
        } catch (e: Exception) {
            Log.e(tag, "Ошибка при остановке MediaRecorder: ${e.message}", e)
        } finally {
            isRecording = false
        }

        // Завершение сохранения в MediaStore
        currentPfd?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(tag, "Ошибка закрытия PFD: ${e.message}")
            }
            currentPfd = null
        }

        currentUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                try {
                    context.contentResolver.update(uri, updateValues, null, null)
                } catch (e: Exception) {
                    Log.e(tag, "Ошибка обновления статуса MediaStore: ${e.message}")
                }
            }
        }

        tempFallbackFile?.let { file ->
            if (resultUri == null && file.exists()) {
                resultUri = Uri.fromFile(file)
            }
            tempFallbackFile = null
        }

        return resultUri
    }

    fun release() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (ignored: Exception) {
            }
        }
        isRecording = false

        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.w(tag, "Ошибка release MediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        currentPfd?.let {
            try {
                it.close()
            } catch (ignored: Exception) {
            }
            currentPfd = null
        }

        recorderSurface = null
    }
}
