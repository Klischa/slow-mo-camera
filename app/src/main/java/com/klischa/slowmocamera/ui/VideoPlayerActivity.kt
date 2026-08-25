package com.klischa.slowmocamera.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.klischa.slowmocamera.databinding.ActivityVideoPlayerBinding
import com.klischa.slowmocamera.util.FileUtils

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    private val speedOptions = listOf(0.125f, 0.25f, 0.5f, 1.0f, 2.0f)
    private var currentSpeedIndex = 3 // 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (videoUriString == null) {
            Toast.makeText(this, "Видео не найдено", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val videoUri = Uri.parse(videoUriString)
        val fileName = FileUtils.getFileNameFromUri(this, videoUri)
        binding.tvVideoTitle.text = fileName

        setupPlayer(videoUri)
        setupControls()
    }

    private fun setupPlayer(uri: Uri) {
        player = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
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

        binding.btnSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val selectedSpeed = speedOptions[currentSpeedIndex]
            player?.playbackParameters = PlaybackParameters(selectedSpeed)
            binding.btnSpeed.text = "Скорость: ${selectedSpeed}x"
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
