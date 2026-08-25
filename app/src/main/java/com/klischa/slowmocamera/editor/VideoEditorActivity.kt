package com.klischa.slowmocamera.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.klischa.slowmocamera.R
import com.klischa.slowmocamera.data.OutputFormatType
import com.klischa.slowmocamera.databinding.ActivityVideoEditorBinding
import com.klischa.slowmocamera.util.FileUtils
import com.klischa.slowmocamera.util.ShareUtils

class VideoEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoEditorBinding
    private var player: ExoPlayer? = null
    private var inputVideoUri: Uri? = null
    private var selectedMusicUri: Uri? = null

    private var currentSpeedFactor: Float = 1.0f
    private var selectedExportFormat = OutputFormatType.MP4_H264
    private lateinit var exportHelper: VideoExportHelper

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedMusicUri = uri
            val audioName = FileUtils.getFileNameFromUri(this, uri)
            binding.btnPickMusic.text = "🎵 $audioName"
            Toast.makeText(this, "Музыка выбрана: $audioName", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        exportHelper = VideoExportHelper(this)

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriString != null) {
            inputVideoUri = Uri.parse(uriString)
            setupPlayer(inputVideoUri!!)
        } else {
            Toast.makeText(this, "Видео не выбрано", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
    }

    private fun setupPlayer(uri: Uri) {
        val fileName = FileUtils.getFileNameFromUri(this, uri)
        binding.tvEditorTitle.text = "Редактор: $fileName"

        player = ExoPlayer.Builder(this).build().apply {
            binding.editorPlayerView.player = this
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ALL

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val duration = duration
                        if (duration > 0) {
                            binding.timelineView.videoDurationMs = duration
                            updateTrimLabels(0L, duration)
                        }
                    }
                }
            })
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Выбор музыки
        binding.btnPickMusic.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        // Обрезка видео (Trim)
        binding.timelineView.onTrimChangedListener = { startMs, endMs ->
            updateTrimLabels(startMs, endMs)
            player?.seekTo(startMs)
        }

        binding.timelineView.onSeekListener = { positionMs ->
            player?.seekTo(positionMs)
        }

        // Регулировка скорости
        binding.chipGroupSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            currentSpeedFactor = when {
                checkedIds.contains(R.id.chipSpeed01) -> 0.125f
                checkedIds.contains(R.id.chipSpeed025) -> 0.25f
                checkedIds.contains(R.id.chipSpeed05) -> 0.5f
                checkedIds.contains(R.id.chipSpeed20) -> 2.0f
                else -> 1.0f
            }
            player?.playbackParameters = PlaybackParameters(currentSpeedFactor)
        }

        // Выбор формата экспорта
        binding.chipGroupExportFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedExportFormat = if (checkedIds.contains(R.id.chipExportWebm)) {
                OutputFormatType.WEBM_VP9
            } else {
                OutputFormatType.MP4_H264
            }
        }

        // Экспорт видео
        binding.btnExport.setOnClickListener {
            startExport()
        }

        binding.btnCancelExport.setOnClickListener {
            exportHelper.cancelExport()
            binding.cardExportProgress.visibility = View.GONE
            Toast.makeText(this, "Экспорт отменён", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTrimLabels(startMs: Long, endMs: Long) {
        val startSec = startMs / 1000f
        val endSec = endMs / 1000f
        binding.tvTrimStart.text = String.format(java.util.Locale.US, "%02d:%04.1f", (startSec / 60).toInt(), startSec % 60)
        binding.tvTrimEnd.text = String.format(java.util.Locale.US, "%02d:%04.1f", (endSec / 60).toInt(), endSec % 60)
    }

    private fun startExport() {
        val uri = inputVideoUri ?: return
        player?.pause()

        val options = VideoExportHelper.EditOptions(
            inputUri = uri,
            trimStartMs = binding.timelineView.trimStartMs,
            trimEndMs = binding.timelineView.trimEndMs,
            speedFactor = currentSpeedFactor,
            isMuteOriginalAudio = binding.switchMuteAudio.isChecked,
            backgroundMusicUri = selectedMusicUri,
            format = selectedExportFormat
        )

        binding.cardExportProgress.visibility = View.VISIBLE
        binding.progressExport.progress = 0
        binding.tvExportPercent.text = "Экспорт видео: 0%"

        exportHelper.exportVideo(options, object : VideoExportHelper.ExportProgressListener {
            override fun onProgress(percentage: Int) {
                binding.progressExport.progress = percentage
                binding.tvExportPercent.text = "Экспорт видео: $percentage%"
            }

            override fun onCompleted(outputUri: Uri) {
                binding.cardExportProgress.visibility = View.GONE
                Toast.makeText(this@VideoEditorActivity, "Экспорт успешно завершён!", Toast.LENGTH_LONG).show()

                // Предложение поделиться или открыть
                ShareUtils.shareVideo(this@VideoEditorActivity, outputUri)
            }

            override fun onError(error: String) {
                binding.cardExportProgress.visibility = View.GONE
                Toast.makeText(this@VideoEditorActivity, error, Toast.LENGTH_LONG).show()
            }
        })
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
