package com.klischa.slowmocamera.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.camera.CameraManagerHelper
import com.klischa.slowmocamera.camera.CameraState
import com.klischa.slowmocamera.data.HighSpeedProfile
import com.klischa.slowmocamera.data.OutputFormatType
import com.klischa.slowmocamera.data.RecordingMode
import com.klischa.slowmocamera.data.VideoConfig
import com.klischa.slowmocamera.databinding.ActivityMainBinding
import com.klischa.slowmocamera.util.FileUtils
import com.klischa.slowmocamera.util.PermissionUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraManagerHelper.CameraEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraManagerHelper

    private var selectedMode = RecordingMode.HFR
    private var selectedFormat = OutputFormatType.MP4_H264
    private var selectedProfile: HighSpeedProfile? = null
    private var availableProfiles: List<HighSpeedProfile> = emptyList()

    private var lastSavedVideoUri: Uri? = null
    private var isCurrentlyRecording = false
    private var timerJob: Job? = null
    private var zoomFadeJob: Job? = null
    private var recordingSeconds = 0L

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[android.Manifest.permission.CAMERA] == true
        val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted && audioGranted) {
            setupCameraPreview()
        } else {
            Toast.makeText(
                this,
                getString(R.string.error_camera_permission),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraHelper = CameraManagerHelper(this, this)

        setupGestures()
        setupListeners()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (PermissionUtils.hasAllPermissions(this)) {
            setupCameraPreview()
        } else {
            permissionLauncher.launch(PermissionUtils.getRequiredPermissions())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        // Жест щипка (Pinch-to-zoom): разводим пальцы -> приближаем, сводим -> отдаляем
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val currentZoom = cameraHelper.currentZoomRatio
                val targetZoom = currentZoom * scaleFactor
                val newZoom = cameraHelper.setZoomRatio(targetZoom)
                showZoomBadge(newZoom)
                return true
            }
        })

        // Двойное нажатие для сброса зума на 1.0x
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val newZoom = cameraHelper.setZoomRatio(1.0f)
                showZoomBadge(newZoom)
                return true
            }
        })

        binding.textureView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showZoomBadge(zoom: Float) {
        binding.tvZoomBadge.visibility = View.VISIBLE
        binding.tvZoomBadge.text = String.format(Locale.US, "%.1fx", zoom)

        zoomFadeJob?.cancel()
        zoomFadeJob = lifecycleScope.launch {
            delay(1200)
            binding.tvZoomBadge.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        // Кнопка записи в центре
        binding.btnRecord.setOnClickListener {
            if (isCurrentlyRecording) {
                cameraHelper.stopRecording()
            } else {
                cameraHelper.startRecording()
            }
        }

        // Кнопка перехода в плеер / выбора из галереи (слева внизу)
        binding.btnOpenGallery.setOnClickListener {
            val intent = Intent(this, VideoPlayerActivity::class.java)
            startActivity(intent)
        }

        // Просмотр последнего записанного видео (справа внизу)
        binding.btnPlayLastVideo.setOnClickListener {
            lastSavedVideoUri?.let { uri ->
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, uri.toString())
                }
                startActivity(intent)
            }
        }

        // Кнопка настроек съёмки (шестерёнка в левом верхнем углу)
        binding.btnSettings.setOnClickListener {
            openSettingsBottomSheet()
        }

        // Клик по бейджу режима вверху также открывает настройки
        binding.tvCurrentProfileBadge.setOnClickListener {
            openSettingsBottomSheet()
        }

        // Смена камеры (справа вверху)
        binding.btnSwitchCamera.setOnClickListener {
            if (!isCurrentlyRecording) {
                cameraHelper.switchCamera()
            }
        }
    }

    private fun openSettingsBottomSheet() {
        if (isCurrentlyRecording) return

        val sheet = CameraSettingsBottomSheet(
            currentMode = selectedMode,
            currentFormat = selectedFormat,
            currentProfile = selectedProfile,
            availableProfiles = availableProfiles,
            onConfigChanged = { mode, format, profile ->
                selectedMode = mode
                selectedFormat = format
                selectedProfile = profile
                updateCurrentProfileBadge()
                applyConfigUpdate()
            },
            onOpenDiagnostics = {
                showDiagnosticsDialog()
            }
        )
        sheet.show(supportFragmentManager, CameraSettingsBottomSheet.TAG)
    }

    private fun updateCurrentProfileBadge() {
        val modeStr = if (selectedMode == RecordingMode.HSR) "HSR" else "HFR"
        val profileStr = selectedProfile?.label ?: "720p @ 240fps"
        binding.tvCurrentProfileBadge.text = "$modeStr • $profileStr"
    }

    private fun setupCameraPreview() {
        if (binding.textureView.isAvailable) {
            cameraHelper.openCamera(binding.textureView.surfaceTexture!!)
        } else {
            binding.textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            cameraHelper.openCamera(surface)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            cameraHelper.closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onProfilesAvailable(profiles: List<HighSpeedProfile>, isHighSpeedSupported: Boolean) {
        runOnUiThread {
            availableProfiles = profiles
            if (selectedProfile == null || !profiles.contains(selectedProfile)) {
                selectedProfile = profiles.firstOrNull()
            }
            updateCurrentProfileBadge()
        }
    }

    private fun applyConfigUpdate() {
        val profile = selectedProfile ?: availableProfiles.firstOrNull() ?: return
        val config = VideoConfig(
            mode = selectedMode,
            profile = profile,
            format = selectedFormat,
            includeAudio = selectedMode == RecordingMode.HSR
        )
        cameraHelper.updateConfig(config)
    }

    override fun onSessionConfigured(previewSize: Size) {
        runOnUiThread {
            binding.textureView.setAspectRatio(previewSize.height, previewSize.width)
        }
    }

    override fun onStateChanged(state: CameraState) {
        runOnUiThread {
            when (state) {
                is CameraState.Initializing -> {
                    binding.btnRecord.isEnabled = false
                }
                is CameraState.PreviewReady -> {
                    isCurrentlyRecording = false
                    stopTimer()
                    binding.btnRecord.setImageResource(R.drawable.ic_record_start)
                    binding.btnRecord.isEnabled = true
                    binding.recordingHud.visibility = View.GONE
                    enableControls(true)
                }
                is CameraState.Recording -> {
                    isCurrentlyRecording = true
                    startTimer()
                    binding.btnRecord.setImageResource(R.drawable.ic_record_stop)
                    binding.recordingHud.visibility = View.VISIBLE
                    binding.tvLiveFps.text = "${selectedProfile?.fps ?: 120} FPS"
                    enableControls(false)
                }
                is CameraState.FinalizingRecording -> {
                    binding.btnRecord.isEnabled = false
                }
                is CameraState.Saved -> {
                    isCurrentlyRecording = false
                    stopTimer()
                    lastSavedVideoUri = state.uri
                    binding.btnPlayLastVideo.visibility = View.VISIBLE
                    val fileName = FileUtils.getFileNameFromUri(this, state.uri)
                    Toast.makeText(
                        this,
                        getString(R.string.video_saved_success, fileName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                is CameraState.Error -> {
                    isCurrentlyRecording = false
                    stopTimer()
                    binding.btnRecord.setImageResource(R.drawable.ic_record_start)
                    binding.btnRecord.isEnabled = true
                    binding.recordingHud.visibility = View.GONE
                    enableControls(true)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
                is CameraState.Uninitialized -> {}
            }
        }
    }

    private fun enableControls(enable: Boolean) {
        binding.btnSettings.isEnabled = enable
        binding.btnSwitchCamera.isEnabled = enable
        binding.btnOpenGallery.isEnabled = enable
        binding.tvCurrentProfileBadge.isEnabled = enable
    }

    private fun startTimer() {
        recordingSeconds = 0L
        binding.tvTimer.text = "00:00"
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                recordingSeconds++
                binding.tvTimer.text = FileUtils.formatDuration(recordingSeconds)
                binding.recDot.visibility = if (binding.recDot.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        binding.recDot.visibility = View.VISIBLE
    }

    private fun showDiagnosticsDialog() {
        HalDiagnosticsDialogFragment.newInstance().show(
            supportFragmentManager,
            HalDiagnosticsDialogFragment.TAG
        )
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.hasAllPermissions(this)) {
            if (binding.textureView.isAvailable) {
                cameraHelper.openCamera(binding.textureView.surfaceTexture!!)
            } else {
                binding.textureView.surfaceTextureListener = surfaceTextureListener
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cameraHelper.closeCamera()
        stopTimer()
    }
}
