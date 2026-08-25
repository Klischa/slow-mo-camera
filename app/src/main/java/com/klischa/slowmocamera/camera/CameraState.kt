package com.klischa.slowmocamera.camera

import android.net.Uri

/**
 * Состояния жизненного цикла камеры и процесса записи.
 */
sealed class CameraState {
    object Uninitialized : CameraState()
    object Initializing : CameraState()
    data class PreviewReady(val cameraId: String, val isHighSpeedCapable: Boolean) : CameraState()
    data class Recording(val durationSeconds: Long) : CameraState()
    object FinalizingRecording : CameraState()
    data class Saved(val uri: Uri, val path: String) : CameraState()
    data class Error(val message: String, val isHalRestriction: Boolean = false, val exception: Throwable? = null) : CameraState()
}
