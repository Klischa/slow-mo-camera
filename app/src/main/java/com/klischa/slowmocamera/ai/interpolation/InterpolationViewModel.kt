package com.klischa.slowmocamera.ai.interpolation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID

class InterpolationViewModel(application: Application) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)

    private val _currentWorkId = MutableLiveData<UUID?>()
    val currentWorkId: LiveData<UUID?> = _currentWorkId

    private val _workInfo = MutableLiveData<WorkInfo?>()
    val workInfo: LiveData<WorkInfo?> = _workInfo

    fun startInterpolation(
        inputUri: Uri,
        targetFps: Int = 120,
        mode: InterpolationMode = InterpolationMode.QUALITY,
        multiplier: Int = 4
    ) {
        val inputData = Data.Builder()
            .putString(InterpolationWorker.KEY_INPUT_URI, inputUri.toString())
            .putInt(InterpolationWorker.KEY_TARGET_FPS, targetFps)
            .putString(InterpolationWorker.KEY_MODE, mode.name)
            .putInt(InterpolationWorker.KEY_MULTIPLIER, multiplier)
            .build()

        val request = OneTimeWorkRequestBuilder<InterpolationWorker>()
            .setInputData(inputData)
            .build()

        _currentWorkId.value = request.id
        workManager.enqueue(request)
    }

    fun cancelInterpolation() {
        _currentWorkId.value?.let {
            workManager.cancelWorkById(it)
        }
    }
}
