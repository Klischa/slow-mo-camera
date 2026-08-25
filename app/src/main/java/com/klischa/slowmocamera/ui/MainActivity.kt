package com.klischa.slowmocamera.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
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

class MainActivity : AppCompatActivity(), CameraManagerHelper.CameraEventListener {

    private val tag = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraManagerHelper

    private var selectedMode = RecordingMode.HFR
    private var selectedFormat = OutputFormatType.MP4_H264
    private var selectedProfile: HighSpeedProfile? = null
    private var availableProfiles: List<HighSpeedProfile> = emptyList()

    private var lastSavedVideoUri: Uri? = null
    private var isCurrentlyRecording = false
    private var timerJob: Job? = null
    private var recordingSeconds = 0L

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

    private fun setupListeners() {
        binding.btnRecord.setOnClickListener {
            if (isCurrentlyRecording) {
                cameraHelper.stopRecording()
            } else {
                cameraHelper.startRecording()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (!isCurrentlyRecording) {
                cameraHelper.switchCamera()
            }
        }

        binding.btnDiagnostics.setOnClickListener {
            showDiagnosticsDialog()
        }

        binding.cardHalWarning.setOnClickListener {
            showDiagnosticsDialog()
        }

        binding.btnPlayLastVideo.setOnClickListener {
            lastSavedVideoUri?.let { uri ->
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, uri.toString())
                }
                startActivity(intent)
            }
        }

        // Mode selector
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedMode = if (checkedIds.contains(R.id.chipModeHsr)) {
                RecordingMode.HSR
            } else {
                RecordingMode.HFR
            }
            applyConfigUpdate()
        }

        // Codec selector
        binding.chipGroupCodec.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFormat = if (checkedIds.contains(R.id.chipCodecWebm)) {
                OutputFormatType.WEBM_VP9
            } else {
                OutputFormatType.MP4_H264
            }
            applyConfigUpdate()
        }
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

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Buffer size is configured dynamically per high-speed profile
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            cameraHelper.closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    override fun onProfilesAvailable(profiles: List<HighSpeedProfile>, isHighSpeedSupported: Boolean) {
        runOnUiThread {
            availableProfiles = profiles
            updateHalBadge(isHighSpeedSupported)
            populateProfileChips(profiles)
        }
    }

    private fun updateHalBadge(isHighSpeedSupported: Boolean) {
        if (isHighSpeedSupported) {
            binding.tvHalStatusBadge.text = getString(R.string.hal_status_supported)
            binding.tvHalStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_supported))
            binding.cardHalWarning.visibility = View.GONE
        } else {
            binding.tvHalStatusBadge.text = getString(R.string.hal_status_restricted)
            binding.tvHalStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_restricted))
            binding.cardHalWarning.visibility = View.VISIBLE
        }
    }

    private fun populateProfileChips(profiles: List<HighSpeedProfile>) {
        binding.chipGroupProfiles.removeAllViews()

        if (profiles.isEmpty()) return

        // Выбираем первый профиль по умолчанию
        if (selectedProfile == null || !profiles.contains(selectedProfile)) {
            selectedProfile = profiles.first()
        }

        for ((index, profile) in profiles.withIndex()) {
            val chip = Chip(this).apply {
                text = profile.label
                isCheckable = true
                isChecked = profile == selectedProfile || (selectedProfile == null && index == 0)
                setOnClickListener {
                    selectedProfile = profile
                    applyConfigUpdate()
                }
            }
            binding.chipGroupProfiles.addView(chip)
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
                    binding.tvStatus.text = getString(R.string.status_initializing)
                }
                is CameraState.PreviewReady -> {
                    isCurrentlyRecording = false
                    stopTimer()
                    binding.btnRecord.setImageResource(R.drawable.ic_record_start)
                    binding.btnRecord.isEnabled = true
                    binding.recordingHud.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.status_ready)
                    enableControls(true)
                }
                is CameraState.Recording -> {
                    isCurrentlyRecording = true
                    startTimer()
                    binding.btnRecord.setImageResource(R.drawable.ic_record_stop)
                    binding.recordingHud.visibility = View.VISIBLE
                    binding.tvLiveFps.text = "${selectedProfile?.fps ?: 120} FPS"
                    binding.tvStatus.text = getString(R.string.status_recording)
                    enableControls(false)
                }
                is CameraState.FinalizingRecording -> {
                    binding.tvStatus.text = getString(R.string.status_saving)
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
                    binding.tvStatus.text = "Ошибка"
                    enableControls(true)

                    if (state.isHalRestriction) {
                        binding.cardHalWarning.visibility = View.VISIBLE
                        binding.tvWarningMessage.text = state.message
                    } else {
                        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                    }
                }
                is CameraState.Uninitialized -> {
                    binding.tvStatus.text = "Камера выключена"
                }
            }
        }
    }

    private fun enableControls(enable: Boolean) {
        binding.btnSwitchCamera.isEnabled = enable
        binding.chipGroupMode.isEnabled = enable
        binding.chipGroupProfiles.isEnabled = enable
        binding.chipGroupCodec.isEnabled = enable
        for (i in 0 until binding.chipGroupMode.childCount) {
            binding.chipGroupMode.getChildAt(i).isEnabled = enable
        }
        for (i in 0 until binding.chipGroupProfiles.childCount) {
            binding.chipGroupProfiles.getChildAt(i).isEnabled = enable
        }
        for (i in 0 until binding.chipGroupCodec.childCount) {
            binding.chipGroupCodec.getChildAt(i).isEnabled = enable
        }
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
                // Мигание индикатора записи
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
