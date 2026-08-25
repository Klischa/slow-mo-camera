package com.klischa.slowmocamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.klischa.slowmocamera.data.HighSpeedProfile
import com.klischa.slowmocamera.data.VideoConfig
import com.klischa.slowmocamera.recorder.MediaRecorderHelper
import java.util.concurrent.Executor

/**
 * Управляет жизненным циклом Camera2, Constrained High Speed сессиями и записью Slow-Mo.
 */
class CameraManagerHelper(
    private val context: Context,
    private val listener: CameraEventListener
) {
    private val tag = "CameraManagerHelper"

    interface CameraEventListener {
        fun onStateChanged(state: CameraState)
        fun onProfilesAvailable(profiles: List<HighSpeedProfile>, isHighSpeedSupported: Boolean)
        fun onSessionConfigured(previewSize: Size)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val halChecker = HalCapabilityChecker(context)
    private val mediaRecorderHelper = MediaRecorderHelper(context)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var standardSession: CameraCaptureSession? = null

    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null

    var currentCameraId: String = "0"
        private set

    var currentConfig: VideoConfig? = null
        private set

    var isConstrainedHighSpeedSupported: Boolean = false
        private set

    private var supportedProfiles: List<HighSpeedProfile> = emptyList()
    private var isRecording = false

    init {
        findDefaultCamera()
    }

    private fun findDefaultCamera() {
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    currentCameraId = id
                    break
                }
            }
            if (currentCameraId.isEmpty() && cameraIds.isNotEmpty()) {
                currentCameraId = cameraIds[0]
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка при определении камеры по умолчанию: ${e.message}")
        }
    }

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("SlowMoCameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(1000)
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(tag, "Ошибка остановки backgroundThread: ${e.message}")
        }
    }

    fun switchCamera() {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.size <= 1) return

            val currentIndex = cameraIds.indexOf(currentCameraId)
            val nextIndex = (currentIndex + 1) % cameraIds.size
            currentCameraId = cameraIds[nextIndex]

            closeCamera()
            previewSurfaceTexture?.let { openCamera(it) }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка переключения камеры: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture) {
        previewSurfaceTexture = surfaceTexture
        startBackgroundThread()

        listener.onStateChanged(CameraState.Initializing)

        try {
            isConstrainedHighSpeedSupported = halChecker.isHighSpeedConstrainedSupported(currentCameraId)
            supportedProfiles = halChecker.getSupportedProfilesForCamera(currentCameraId)
            listener.onProfilesAvailable(supportedProfiles, isConstrainedHighSpeedSupported)

            val selectedProfile = supportedProfiles.firstOrNull() ?: HighSpeedProfile(
                size = Size(1280, 720),
                fpsRange = Range(30, 30),
                isConstrainedSupported = false
            )
            currentConfig = VideoConfig(profile = selectedProfile)

            cameraManager.openCamera(currentCameraId, cameraDeviceCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(tag, "Ошибка CameraAccessException при открытии: ${e.message}", e)
            listener.onStateChanged(
                CameraState.Error("Не удалось открыть камеру: ${e.message}", isHalRestriction = false, exception = e)
            )
        } catch (e: SecurityException) {
            Log.e(tag, "Ошибка прав доступа: ${e.message}", e)
            listener.onStateChanged(
                CameraState.Error("Отсутствуют разрешения на камеру", isHalRestriction = false, exception = e)
            )
        } catch (e: Exception) {
            Log.e(tag, "Непредвиденная ошибка открытия камеры: ${e.message}", e)
            listener.onStateChanged(
                CameraState.Error("Ошибка открытия камеры: ${e.message}", isHalRestriction = false, exception = e)
            )
        }
    }

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.i(tag, "Камера $currentCameraId успешно открыта")
            startHighSpeedSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(tag, "Камера $currentCameraId отключена")
            camera.close()
            cameraDevice = null
            listener.onStateChanged(CameraState.Uninitialized)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_IN_USE -> "Камера занята другим процессом"
                ERROR_MAX_CAMERAS_IN_USE -> "Превышен лимит открытых камер"
                ERROR_CAMERA_DISABLED -> "Камера отключена политикой устройства"
                ERROR_CAMERA_DEVICE -> "Критический сбой устройства камеры"
                ERROR_CAMERA_SERVICE -> "Сбой службы CameraService"
                else -> "Неизвестная ошибка камеры ($error)"
            }
            Log.e(tag, "Ошибка CameraDevice: $errorMsg ($error)")
            camera.close()
            cameraDevice = null
            listener.onStateChanged(
                CameraState.Error("Ошибка камеры: $errorMsg", isHalRestriction = false)
            )
        }
    }

    fun updateConfig(config: VideoConfig) {
        currentConfig = config
        if (cameraDevice != null && previewSurfaceTexture != null && !isRecording) {
            startHighSpeedSession()
        }
    }

    /**
     * Создает Constrained High Speed Capture Session для камеры.
     */
    private fun startHighSpeedSession() {
        val device = cameraDevice ?: return
        val texture = previewSurfaceTexture ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            // Очистка предыдущих сессий
            highSpeedSession?.close()
            highSpeedSession = null
            standardSession?.close()
            standardSession = null

            // Важнейшее требование Camera2 Constrained High Speed:
            // Буфер SurfaceTexture ДОЛЖЕН строго соответствовать выбранному High Speed размеру (например 1280x720)
            val profileSize = config.profile.size
            texture.setDefaultBufferSize(profileSize.width, profileSize.height)

            previewSurface?.release()
            val newPreviewSurface = Surface(texture)
            previewSurface = newPreviewSurface

            listener.onSessionConfigured(profileSize)

            // Подготовка MediaRecorder Surface заранее, так как Constrained High Speed сессия
            // требует все целевые поверхности (surfaces) при создании сессии!
            val newRecorderSurface = mediaRecorderHelper.setupRecorder(config)
            recorderSurface = newRecorderSurface

            val surfaces = listOf(newPreviewSurface, newRecorderSurface)

            if (isConstrainedHighSpeedSupported && config.profile.isConstrainedSupported) {
                createConstrainedSession(device, surfaces, handler)
            } else {
                createStandardSession(device, surfaces, handler)
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка настройки сессии: ${e.message}", e)
            handleSessionCreationFailure(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun createConstrainedSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val executor = Executor { command -> handler.post(command) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_HIGH_SPEED,
                    outputConfigs,
                    executor,
                    highSpeedSessionCallback
                )
                device.createCaptureSession(sessionConfig)
            } else {
                device.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    highSpeedSessionCallback,
                    handler
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "ConstrainedHighSpeedSession создание не удалось: ${e.message}", e)
            handleSessionCreationFailure(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun createStandardSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler
    ) {
        try {
            device.createCaptureSession(
                surfaces,
                standardSessionCallback,
                handler
            )
        } catch (e: Exception) {
            Log.e(tag, "Ошибка создания стандартной сессии: ${e.message}", e)
            handleSessionCreationFailure(e)
        }
    }

    private val highSpeedSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            if (session is CameraConstrainedHighSpeedCaptureSession) {
                highSpeedSession = session
                Log.i(tag, "High-Speed сессия успешно сконфигурирована!")
                startHighSpeedPreview()
                listener.onStateChanged(CameraState.PreviewReady(currentCameraId, isHighSpeedCapable = true))
            } else {
                standardSession = session
                startStandardPreview()
                listener.onStateChanged(CameraState.PreviewReady(currentCameraId, isHighSpeedCapable = false))
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(tag, "onConfigureFailed в HighSpeedCaptureSession")
            handleSessionCreationFailure(
                IllegalStateException("HAL вендора (Infinix/MTK) отклонил сессию высокой скорости")
            )
        }
    }

    private val standardSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            standardSession = session
            Log.i(tag, "Стандартная сессия сконфигурирована (Fallback)")
            startStandardPreview()
            listener.onStateChanged(CameraState.PreviewReady(currentCameraId, isHighSpeedCapable = false))
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(tag, "onConfigureFailed в CameraCaptureSession")
            listener.onStateChanged(
                CameraState.Error("Ошибка конфигурации стандартной сессии камеры", isHalRestriction = false)
            )
        }
    }

    /**
     * Запуск предпросмотра в режиме High-Speed через createHighSpeedRequestList и setRepeatingBurst.
     */
    private fun startHighSpeedPreview() {
        val session = highSpeedSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            // Для High-Speed сессий создается TEMPLATE_RECORD запрос
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                // Установка целевого диапазона частоты кадров
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
            }

            // Создаем список высокоскоростных запросов (burst)
            val highSpeedRequestList = session.createHighSpeedRequestList(builder.build())
            session.setRepeatingBurst(highSpeedRequestList, null, handler)
            Log.i(tag, "High-Speed превью запущено с FPS ${config.profile.fps}")
        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска High-Speed превью: ${e.message}", e)
        }
    }

    private fun startStandardPreview() {
        val session = standardSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
            }
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска стандартного превью: ${e.message}", e)
        }
    }

    fun startRecording() {
        if (isRecording) return
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val recorder = recorderSurface ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            mediaRecorderHelper.start()
            isRecording = true

            if (highSpeedSession != null) {
                // High-Speed запись: добавляем И preview surface, И recorder surface
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(recorder)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
                }

                val requestList = highSpeedSession!!.createHighSpeedRequestList(builder.build())
                highSpeedSession!!.setRepeatingBurst(requestList, null, handler)
            } else if (standardSession != null) {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(recorder)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                standardSession!!.setRepeatingRequest(builder.build(), null, handler)
            }

            listener.onStateChanged(CameraState.Recording(durationSeconds = 0))
            Log.i(tag, "Запись видео успешно начата (Режим: ${config.mode}, FPS: ${config.profile.fps})")
        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска записи: ${e.message}", e)
            isRecording = false
            listener.onStateChanged(
                CameraState.Error("Ошибка старта записи: ${e.message}", isHalRestriction = false, exception = e)
            )
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        listener.onStateChanged(CameraState.FinalizingRecording)

        try {
            val savedUri = mediaRecorderHelper.stop()
            isRecording = false

            // Возврат к обычному превью без записи на рекордер
            if (highSpeedSession != null) {
                startHighSpeedPreview()
            } else if (standardSession != null) {
                startStandardPreview()
            }

            if (savedUri != null) {
                listener.onStateChanged(CameraState.Saved(uri = savedUri, path = savedUri.toString()))
            } else {
                listener.onStateChanged(
                    CameraState.Error("Не удалось сохранить видеофайл", isHalRestriction = false)
                )
            }

            // Пересоздаем сессию для готовности к следующей записи
            startHighSpeedSession()
        } catch (e: Exception) {
            Log.e(tag, "Ошибка остановки записи: ${e.message}", e)
            isRecording = false
            listener.onStateChanged(
                CameraState.Error("Ошибка при остановке записи: ${e.message}", isHalRestriction = false, exception = e)
            )
        }
    }

    private fun handleSessionCreationFailure(cause: Throwable) {
        val isMtkOrInfinix = Build.MANUFACTURER.contains("Infinix", ignoreCase = true) ||
                Build.HARDWARE.contains("mt", ignoreCase = true)

        val message = if (isMtkOrInfinix) {
            "Ограничение HAL: Чипсет MediaTek Helio G99 / прошивка Infinix блокирует доступ к высокоскоростным сессиям Camera2 для сторонних приложений. Используется режим совместимости."
        } else {
            "Устройство не смогло инициализировать Constrained High Speed сессию (${cause.message})."
        }

        listener.onStateChanged(
            CameraState.Error(
                message = message,
                isHalRestriction = true,
                exception = cause
            )
        )
    }

    fun closeCamera() {
        try {
            if (isRecording) {
                mediaRecorderHelper.stop()
                isRecording = false
            }
            highSpeedSession?.close()
            highSpeedSession = null
            standardSession?.close()
            standardSession = null

            cameraDevice?.close()
            cameraDevice = null

            previewSurface?.release()
            previewSurface = null

            mediaRecorderHelper.release()
            recorderSurface = null

            listener.onStateChanged(CameraState.Uninitialized)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка закрытия камеры: ${e.message}")
        }
    }
}
