package com.klischa.slowmocamera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.klischa.slowmocamera.data.CaptureMode
import com.klischa.slowmocamera.data.HighSpeedProfile
import com.klischa.slowmocamera.data.VideoConfig
import com.klischa.slowmocamera.recorder.MediaRecorderHelper
import com.klischa.slowmocamera.stabilization.CameraStabilizer
import com.klischa.slowmocamera.stabilization.StabilizationManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Управляет Camera2: High-Speed Slow-Mo, ручными PRO-настройками (ISO, Shutter, WB, Focus),
 * снимками во время записи (Snapshot), таймлапсом, детекцией движения и ориентацией.
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
        fun onPhotoCaptured(uri: Uri)
        fun onMotionTriggered()
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val halChecker = HalCapabilityChecker(context)
    private val mediaRecorderHelper = MediaRecorderHelper(context)

    val manualControls = CameraManualControls()
    val stabilizationManager = StabilizationManager(context)
    lateinit var motionDetector: MotionDetector
    private var stabCapabilities = CameraStabilizer.StabilizationCapability(false, false, false)
    private var currentGyroFile: java.io.File? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var highSpeedSession: CameraConstrainedHighSpeedCaptureSession? = null
    private var standardSession: CameraCaptureSession? = null

    private var previewSurfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var motionAnalysisReader: ImageReader? = null

    var currentCameraId: String = "0"
        private set

    var currentConfig: VideoConfig? = null
        private set

    var currentMode: CaptureMode = CaptureMode.SLOW_MO_VIDEO
        private set

    var isConstrainedHighSpeedSupported: Boolean = false
        private set

    var isFrontCamera: Boolean = false
        private set

    var sensorOrientation: Int = 90
        private set

    var currentPreviewSize: Size? = null
        private set

    private var supportedProfiles: List<HighSpeedProfile> = emptyList()
    var isRecording = false
        private set

    // Zoom parameters
    var currentZoomRatio: Float = 1.0f
        private set
    var minZoomRatio: Float = 1.0f
        private set
    var maxZoomRatio: Float = 10.0f
        private set
    private var sensorActiveArray: Rect? = null

    init {
        motionDetector = MotionDetector(isEnabled = false) {
            listener.onMotionTriggered()
        }
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
            updateCameraMetadata(currentCameraId)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка при определении камеры по умолчанию: ${e.message}")
        }
    }

    private fun updateCameraMetadata(id: String) {
        try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            isFrontCamera = facing == CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            sensorActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            // Диапазоны ISO, выдержки и фокуса
            chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                manualControls.isoRange = it
            }
            chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                manualControls.exposureTimeRange = it
            }
            chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
                manualControls.exposureCompensationRange = it
            }
            chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.let {
                manualControls.minFocusDistance = it
            }

            // Стабилизация
            stabCapabilities = stabilizationManager.inspectHardwareCapabilities(chars)

            setupZoomCapabilities(chars)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка чтения метаданных камеры: ${e.message}")
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
            updateCameraMetadata(currentCameraId)
            currentZoomRatio = 1.0f

            closeCamera()
            previewSurfaceTexture?.let { openCamera(it) }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка переключения камеры: ${e.message}")
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (currentMode == mode) return
        currentMode = mode
        if (cameraDevice != null && previewSurfaceTexture != null && !isRecording) {
            startSession()
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture) {
        previewSurfaceTexture = surfaceTexture
        startBackgroundThread()

        listener.onStateChanged(CameraState.Initializing)

        try {
            updateCameraMetadata(currentCameraId)
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

    private fun setupZoomCapabilities(chars: CameraCharacteristics) {
        var maxZoom = 6.0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                minZoomRatio = zoomRange.lower
                maxZoom = zoomRange.upper
            }
        }

        val maxDigital = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        if (maxDigital > 1.0f && maxDigital > maxZoom) {
            maxZoom = maxDigital
        }

        maxZoomRatio = maxZoom.coerceAtMost(10.0f)
        currentZoomRatio = minZoomRatio
    }

    fun setZoomRatio(targetRatio: Float): Float {
        val clamped = targetRatio.coerceIn(minZoomRatio, maxZoomRatio)
        if (Math.abs(clamped - currentZoomRatio) < 0.01f) return currentZoomRatio

        currentZoomRatio = clamped
        refreshPreview()
        return currentZoomRatio
    }

    fun refreshPreview() {
        if (cameraDevice != null && !isRecording) {
            if (highSpeedSession != null) {
                startHighSpeedPreview()
            } else if (standardSession != null) {
                startStandardPreview()
            }
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
                return
            } catch (ignored: Exception) {}
        }

        val active = sensorActiveArray ?: return
        val cropWidth = (active.width() / currentZoomRatio).toInt()
        val cropHeight = (active.height() / currentZoomRatio).toInt()
        val cropLeft = (active.width() - cropWidth) / 2
        val cropTop = (active.height() - cropHeight) / 2
        val cropRect = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }

    /**
     * Тап по экрану для точечной фокусировки и экспозамера (Tap-to-Focus).
     */
    fun tapToFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val meteringRect = CameraManualControls.calculateFocusArea(
            PointF(x, y),
            Size(viewWidth, viewHeight),
            sensorActiveArray,
            sensorOrientation,
            isFrontCamera
        )
        manualControls.focusMeteringArea = meteringRect
        manualControls.exposureMeteringArea = meteringRect
        manualControls.isAutoFocus = true
        refreshPreview()
    }

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            Log.i(tag, "Камера $currentCameraId успешно открыта (Фронтальная: $isFrontCamera)")
            startSession()
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
            startSession()
        }
    }

    private fun startSession() {
        val device = cameraDevice ?: return
        val texture = previewSurfaceTexture ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            highSpeedSession?.close()
            highSpeedSession = null
            standardSession?.close()
            standardSession = null
            imageReader?.close()
            imageReader = null
            motionAnalysisReader?.close()
            motionAnalysisReader = null

            val profileSize = config.profile.size
            currentPreviewSize = profileSize
            texture.setDefaultBufferSize(profileSize.width, profileSize.height)

            previewSurface?.release()
            val newPreviewSurface = Surface(texture)
            previewSurface = newPreviewSurface

            listener.onSessionConfigured(profileSize)

            // Настройка ImageReader для фото/снапшотов
            setupImageReader(handler)

            if (currentMode == CaptureMode.PHOTO) {
                val surfaces = listOfNotNull(newPreviewSurface, imageReader?.surface)
                createStandardSession(device, surfaces, handler)
            } else {
                setupVideoSession(device, newPreviewSurface, config, handler)
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка настройки сессии: ${e.message}", e)
            handleSessionCreationFailure(e)
        }
    }

    private fun setupImageReader(handler: Handler) {
        val chars = cameraManager.getCameraCharacteristics(currentCameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
        val largestJpeg = jpegSizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

        val reader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            saveCapturedPhoto(bytes)
        }, handler)
        imageReader = reader
    }

    private fun saveCapturedPhoto(bytes: ByteArray) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "SlowMo_Photo_${timestamp}.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SlowMoCamera")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }
                listener.onPhotoCaptured(uri)
            }
        } catch (e: Exception) {
            Log.e(tag, "Ошибка сохранения фото: ${e.message}", e)
        }
    }

    private fun setupVideoSession(device: CameraDevice, preview: Surface, config: VideoConfig, handler: Handler) {
        val newRecorderSurface = mediaRecorderHelper.setupRecorder(config)
        recorderSurface = newRecorderSurface

        val surfaces = mutableListOf(preview, newRecorderSurface)
        imageReader?.surface?.let { surfaces.add(it) }

        if (!isFrontCamera && isConstrainedHighSpeedSupported && config.profile.isConstrainedSupported) {
            createConstrainedSession(device, surfaces, handler)
        } else {
            createStandardSession(device, surfaces, handler)
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
            Log.e(tag, "onConfigureFailed в HighSpeedCaptureSession, переключаемся на стандартную сессию")
            val device = cameraDevice
            val preview = previewSurface
            val recorder = recorderSurface
            val handler = backgroundHandler
            if (device != null && preview != null && recorder != null && handler != null) {
                createStandardSession(device, listOfNotNull(preview, recorder, imageReader?.surface), handler)
            } else {
                handleSessionCreationFailure(
                    IllegalStateException("HAL вендора отклонил сессию высокой скорости")
                )
            }
        }
    }

    private val standardSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            standardSession = session
            Log.i(tag, "Стандартная сессия сконфигурирована")
            startStandardPreview()
            listener.onStateChanged(CameraState.PreviewReady(currentCameraId, isHighSpeedCapable = false))
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(tag, "onConfigureFailed в CameraCaptureSession")
            listener.onStateChanged(
                CameraState.Error("Ошибка конфигурации сессии камеры", isHalRestriction = false)
            )
        }
    }

    private fun startHighSpeedPreview() {
        val session = highSpeedSession ?: return
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val config = currentConfig ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                manualControls.applyToBuilder(this)
                stabilizationManager.applyHardwareStabilization(this, stabCapabilities, stabilizationManager.currentParams.isHardwarePreviewStabilizationEnabled)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
            }
            applyZoom(builder)
            MtkVendorTagHelper.applyMtkSlowMoVendorTags(builder, config.profile.fps)

            val highSpeedRequestList = session.createHighSpeedRequestList(builder.build())
            session.setRepeatingBurst(highSpeedRequestList, null, handler)
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
            val template = if (currentMode == CaptureMode.PHOTO) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
            val builder = device.createCaptureRequest(template).apply {
                addTarget(surface)
                manualControls.applyToBuilder(this)
                stabilizationManager.applyHardwareStabilization(this, stabCapabilities, stabilizationManager.currentParams.isHardwarePreviewStabilizationEnabled)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
            }
            applyZoom(builder)
            MtkVendorTagHelper.applyMtkSlowMoVendorTags(builder, config.profile.fps)
            session.setRepeatingRequest(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка запуска стандартного превью: ${e.message}", e)
        }
    }

    /**
     * Снимок фото во время видеозаписи (Snapshot).
     */
    fun takeSnapshotDuringRecording(deviceRotationDegrees: Int) {
        val session = standardSession ?: highSpeedSession ?: return
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
                addTarget(reader.surface)
                manualControls.applyToBuilder(this)

                val jpegOrientation = if (isFrontCamera) {
                    (sensorOrientation + deviceRotationDegrees) % 360
                } else {
                    (sensorOrientation - deviceRotationDegrees + 360) % 360
                }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            applyZoom(builder)

            session.capture(builder.build(), null, handler)
            Log.i(tag, "Снапшот во время записи отправлен на захват")
        } catch (e: Exception) {
            Log.w(tag, "Не удалось сделать снимок во время видео: ${e.message}")
        }
    }

    /**
     * Спуск затвора для создания фотоснимка в фоторежиме.
     */
    fun takePhoto(deviceRotationDegrees: Int) {
        val session = standardSession ?: return
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                manualControls.applyToBuilder(this)

                val jpegOrientation = if (isFrontCamera) {
                    (sensorOrientation + deviceRotationDegrees) % 360
                } else {
                    (sensorOrientation - deviceRotationDegrees + 360) % 360
                }
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }
            applyZoom(builder)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.i(tag, "Фотоснимок успешно захвачен")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(tag, "Ошибка фотосъёмки: ${e.message}", e)
            listener.onStateChanged(CameraState.Error("Ошибка фотосъёмки: ${e.message}"))
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

            // Запись телеметрии гироскопа (Gyroflow)
            val gyroFile = File(context.cacheDir, "temp_telemetry.gyro.csv")
            currentGyroFile = gyroFile
            stabilizationManager.sensorRecorder.startRecording()

            if (highSpeedSession != null) {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(recorder)
                    manualControls.applyToBuilder(this)
                    stabilizationManager.applyHardwareStabilization(this, stabCapabilities, stabilizationManager.currentParams.isHardwarePreviewStabilizationEnabled)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
                }
                applyZoom(builder)
                MtkVendorTagHelper.applyMtkSlowMoVendorTags(builder, config.profile.fps)

                val requestList = highSpeedSession!!.createHighSpeedRequestList(builder.build())
                highSpeedSession!!.setRepeatingBurst(requestList, null, handler)
            } else if (standardSession != null) {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(recorder)
                    manualControls.applyToBuilder(this)
                    stabilizationManager.applyHardwareStabilization(this, stabCapabilities, stabilizationManager.currentParams.isHardwarePreviewStabilizationEnabled)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.fpsRange)
                }
                applyZoom(builder)
                MtkVendorTagHelper.applyMtkSlowMoVendorTags(builder, config.profile.fps)
                standardSession!!.setRepeatingRequest(builder.build(), null, handler)
            }

            listener.onStateChanged(CameraState.Recording(durationSeconds = 0))
            Log.i(tag, "Запись видео успешно начата (Фронтальная: $isFrontCamera, FPS: ${config.profile.fps})")
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

            currentGyroFile?.let {
                stabilizationManager.sensorRecorder.stopRecording(it)
            }

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

            startSession()
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
            imageReader?.close()
            imageReader = null

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
