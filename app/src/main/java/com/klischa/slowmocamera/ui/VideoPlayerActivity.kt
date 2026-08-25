package com.klischa.slowmocamera.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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

    // Лаунчер для выбора видео из системной галереи телефона (Photo Picker / Storage)
    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            playVideoUri(uri)
        }
    }

    // Фолбэк лаунчер (GetContent) для старых версий или устройств без Photo Picker
    private val getContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            playVideoUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()

        val videoUriString = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (videoUriString != null) {
            playVideoUri(Uri.parse(videoUriString))
        } else {
            // Если открыто напрямую без переданного видео — открываем выбор из галереи
            openGalleryPicker()
        }
    }

    private fun openGalleryPicker() {
        try {
            if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            } else {
                getContentLauncher.launch("video/*")
            }
        } catch (e: Exception) {
            try {
                getContentLauncher.launch("video/*")
            } catch (ex: Exception) {
                Toast.makeText(this, "Не удалось открыть выбор видео: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playVideoUri(uri: Uri) {
        val fileName = FileUtils.getFileNameFromUri(this, uri)
        binding.tvVideoTitle.text = fileName

        if (player == null) {
            player = ExoPlayer.Builder(this).build().apply {
                binding.playerView.player = this
            }
        }

        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
            currentSpeedIndex = 3 // 1.0x
            playbackParameters = PlaybackParameters(1.0f)
            binding.btnSpeed.text = "1.0x"
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPickGallery.setOnClickListener {
            openGalleryPicker()
        }

        binding.btnSpeed.setOnClickListener {
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            val selectedSpeed = speedOptions[currentSpeedIndex]
            player?.playbackParameters = PlaybackParameters(selectedSpeed)
            binding.btnSpeed.text = "${selectedSpeed}x"
            Toast.makeText(this, "Скорость: ${selectedSpeed}x", Toast.LENGTH_SHORT).show()
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
