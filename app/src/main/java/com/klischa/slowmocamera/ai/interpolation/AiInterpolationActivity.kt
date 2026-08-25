package com.klischa.slowmocamera.ai.interpolation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.databinding.ActivityAiInterpolationBinding
import com.klischa.slowmocamera.editor.VideoEditorActivity
import com.klischa.slowmocamera.util.FileUtils
import com.klischa.slowmocamera.util.ShareUtils

class AiInterpolationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiInterpolationBinding
    private lateinit var viewModel: InterpolationViewModel
    private var inputVideoUri: Uri? = null
    private var resultVideoUri: Uri? = null
    private var resultPlayer: ExoPlayer? = null

    private var targetFps = 120
    private var multiplier = 4
    private var selectedMode = InterpolationMode.QUALITY

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiInterpolationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[InterpolationViewModel::class.java]

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriString != null) {
            loadVideo(Uri.parse(uriString))
        } else {
            pickVideoLauncher.launch("video/*")
        }

        setupControls()
        observeWorkManager()
    }

    private fun loadVideo(uri: Uri) {
        inputVideoUri = uri
        val fileName = FileUtils.getFileNameFromUri(this, uri)
        binding.tvVideoName.text = fileName

        val extractor = FrameExtractor(this)
        val info = extractor.getVideoInfo(uri)
        val durationSec = info.durationMs / 1000f
        binding.tvVideoDetails.text = "Исходное: ${info.width}x${info.height} • ${info.estimatedFps.toInt()} FPS • ${String.format(java.util.Locale.US, "%.1fs", durationSec)}"

        val thermalMonitor = ThermalMonitor(this)
        val thermalStatus = thermalMonitor.getCurrentThermalStatus()
        binding.tvThermalStatus.text = "🟢 Температура чипсета: ${thermalStatus.label}"
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnChangeVideo.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        // Выбор целевого FPS
        binding.chipGroupTargetFps.setOnCheckedStateChangeListener { _, checkedIds ->
            when {
                checkedIds.contains(R.id.chipFps60) -> {
                    targetFps = 60
                    multiplier = 2
                }
                checkedIds.contains(R.id.chipFps240) -> {
                    targetFps = 240
                    multiplier = 8
                }
                else -> {
                    targetFps = 120
                    multiplier = 4
                }
            }
        }

        // Выбор режима
        binding.chipGroupAiMode.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedMode = if (checkedIds.contains(R.id.chipModeSpeed)) {
                InterpolationMode.SPEED
            } else {
                InterpolationMode.QUALITY
            }
        }

        // Запуск интерполяции
        binding.btnStartInterpolation.setOnClickListener {
            val uri = inputVideoUri
            if (uri == null) {
                Toast.makeText(this, "Пожалуйста, выберите видео", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnStartInterpolation.isEnabled = false
            binding.progressInterpolation.visibility = View.VISIBLE
            binding.tvProgressStatus.visibility = View.VISIBLE
            binding.btnCancelProcess.visibility = View.VISIBLE
            binding.layoutResult.visibility = View.GONE

            viewModel.startInterpolation(uri, targetFps, selectedMode, multiplier)
        }

        // Отмена
        binding.btnCancelProcess.setOnClickListener {
            viewModel.cancelInterpolation()
            resetUi()
            Toast.makeText(this, "Интерполяция отменена", Toast.LENGTH_SHORT).show()
        }

        // Поделиться результатом
        binding.btnShareResult.setOnClickListener {
            resultVideoUri?.let { uri ->
                ShareUtils.shareVideo(this, uri)
            }
        }

        // Открыть в редакторе
        binding.btnOpenEditorResult.setOnClickListener {
            resultVideoUri?.let { uri ->
                val intent = Intent(this, VideoEditorActivity::class.java).apply {
                    putExtra(VideoEditorActivity.EXTRA_VIDEO_URI, uri.toString())
                }
                startActivity(intent)
            }
        }
    }

    private fun observeWorkManager() {
        viewModel.currentWorkId.observe(this) { uuid ->
            if (uuid != null) {
                WorkManager.getInstance(this).getWorkInfoByIdLiveData(uuid).observe(this) { workInfo ->
                    if (workInfo != null) {
                        handleWorkInfo(workInfo)
                    }
                }
            }
        }
    }

    private fun handleWorkInfo(workInfo: WorkInfo) {
        val progress = workInfo.progress.getInt(InterpolationWorker.KEY_PROGRESS, 0)
        val statusText = workInfo.progress.getString(InterpolationWorker.KEY_STATUS_TEXT) ?: "Обработка..."

        binding.progressInterpolation.progress = progress
        binding.tvProgressStatus.text = statusText

        when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> {
                resetUi()
                val outputUriString = workInfo.outputData.getString(InterpolationWorker.KEY_OUTPUT_URI)
                if (outputUriString != null) {
                    val uri = Uri.parse(outputUriString)
                    resultVideoUri = uri
                    showResultPreview(uri)
                    Toast.makeText(this, "AI-интерполяция $targetFps FPS завершена!", Toast.LENGTH_LONG).show()
                }
            }
            WorkInfo.State.FAILED -> {
                resetUi()
                Toast.makeText(this, "Ошибка AI-интерполяции", Toast.LENGTH_LONG).show()
            }
            WorkInfo.State.CANCELLED -> {
                resetUi()
            }
            else -> {}
        }
    }

    private fun showResultPreview(uri: Uri) {
        binding.layoutResult.visibility = View.VISIBLE
        resultPlayer?.release()
        resultPlayer = ExoPlayer.Builder(this).build().apply {
            binding.resultPlayerView.player = this
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun resetUi() {
        binding.btnStartInterpolation.isEnabled = true
        binding.progressInterpolation.visibility = View.GONE
        binding.tvProgressStatus.visibility = View.GONE
        binding.btnCancelProcess.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        resultPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        resultPlayer?.release()
        resultPlayer = null
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }
}
