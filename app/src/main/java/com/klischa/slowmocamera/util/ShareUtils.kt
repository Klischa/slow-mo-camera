package com.klischa.slowmocamera.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareUtils {

    fun shareVideo(context: Context, videoUri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться Slow-Mo видео"))
    }

    fun sharePhoto(context: Context, photoUri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться фотографией"))
    }
}
