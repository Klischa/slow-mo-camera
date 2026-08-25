package com.klischa.slowmocamera.stabilization

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.databinding.ActivityVideoStabilizerBinding
import com.klischa.slowmocamera.editor.VideoEditorActivity
import com.klischa.slowmocamera.util.FileUtils
import com.klischa.slowmocamera.util.ShareUtils
import kotlinx.coroutines.launch

class VideoStabilizerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoStabilizerBinding
    private var inputVideoUri: Uri? = null
    private var stabilizedVideoUri: Uri? = null
    private var player: ExoPlayer? = null

    private val stabilizationManager by lazy { StabilizationManager(this) }
    private val params = StabilizationParams()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoStabilizerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriString != null) {
            inputVideoUri = Uri.parse(uriString)
            loadVideoPreview(inputVideoUri!!)
        } else {
            Toast.makeText(this, "Видео не выбрано", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
    }

    private fun loadVideoPreview(uri: Uri) {
        val fileName = FileUtils.getFileNameFromUri(this, uri)
        binding.tvStabTitle.text = "Стабилизация: $fileName"

        player?.release()
        player = ExoPlayer.Builder(this).build().apply {
            binding.stabPlayerView.player = this
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Выбор режима
        binding.chipGroupStabMode.setOnCheckedStateChangeListener { _, checkedIds ->
            params.mode = when {
                checkedIds.contains(R.id.chipModeOpenCv) -> StabilizationMode.OPENCV_TRACKING
                checkedIds.contains(R.id.chipModeGyro) -> StabilizationMode.GYROFLOW
                else -> StabilizationMode.FFMPEG_VIDSTAB
            }
        }

        // Сглаживание
        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            params.smoothing = value.toInt()
            binding.tvSmoothingLabel.text = "Степень сглаживания (Smoothing): ${params.smoothing}"
        }

        // Запуск стабилизации
        binding.btnStartStabilization.setOnClickListener {
            val uri = inputVideoUri ?: return@setOnClickListener

            binding.btnStartStabilization.isEnabled = false
            binding.progressStab.visibility = View.VISIBLE
            binding.tvProgressStatus.visibility = View.VISIBLE
            binding.progressStab.progress = 0
            binding.tvProgressStatus.text = "Стабилизация видео: 0%"
            binding.layoutResult.visibility = View.GONE

            lifecycleScope.launch {
                val resultUri = stabilizationManager.processStabilization(
                    videoUri = uri,
                    params = params
                ) { progress ->
                    binding.progressStab.progress = progress
                    binding.tvProgressStatus.text = "Стабилизация видео: $progress%"
                }

                binding.btnStartStabilization.isEnabled = true
                binding.progressStab.visibility = View.GONE
                binding.tvProgressStatus.visibility = View.GONE

                if (resultUri != null) {
                    stabilizedVideoUri = resultUri
                    binding.layoutResult.visibility = View.VISIBLE
                    loadVideoPreview(resultUri)
                    Toast.makeText(this@VideoStabilizerActivity, "Видео успешно стабилизировано!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@VideoStabilizerActivity, "Ошибка стабилизации видео", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Поделиться
        binding.btnShareResult.setOnClickListener {
            stabilizedVideoUri?.let { uri ->
                ShareUtils.shareVideo(this, uri)
            }
        }

        // В редактор
        binding.btnOpenEditor.setOnClickListener {
            stabilizedVideoUri?.let { uri ->
                val intent = Intent(this, VideoEditorActivity::class.java).apply {
                    putExtra(VideoEditorActivity.EXTRA_VIDEO_URI, uri.toString())
                }
                startActivity(intent)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
    }
}
