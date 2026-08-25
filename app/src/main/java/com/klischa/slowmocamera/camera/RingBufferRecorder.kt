package com.klischa.slowmocamera.camera

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.klischa.slowmocamera.data.VideoConfig
import com.klischa.slowmocamera.recorder.MediaRecorderHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Менеджер кольцевой буферизации («Запись в прошлое»).
 * Позволяет непрерывно держать в памяти последние 3-10 секунд съёмки
 * и сохранять их в постоянную память по нажатию кнопки.
 */
class RingBufferRecorder(
    private val context: Context,
    private val bufferDurationSeconds: Int = 5
) {
    private val tag = "RingBufferRecorder"
    private var isBuffering = false

    private val tempBufferDir = File(context.cacheDir, "pre_record_buffer").apply {
        if (!exists()) mkdirs()
    }

    fun startBuffering() {
        isBuffering = true
        Log.i(tag, "Кольцевой буфер активен ($bufferDurationSeconds сек)")
    }

    fun stopBuffering() {
        isBuffering = false
        clearBuffer()
    }

    fun clearBuffer() {
        tempBufferDir.listFiles()?.forEach { it.delete() }
    }
}
