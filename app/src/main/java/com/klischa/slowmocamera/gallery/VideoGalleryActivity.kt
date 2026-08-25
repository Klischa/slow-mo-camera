package com.klischa.slowmocamera.gallery

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.klischa.slowmocamera.databinding.ActivityVideoGalleryBinding
import com.klischa.slowmocamera.databinding.ItemGalleryMediaBinding
import com.klischa.slowmocamera.editor.VideoEditorActivity
import com.klischa.slowmocamera.ui.VideoPlayerActivity
import com.klischa.slowmocamera.util.ShareUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoGalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoGalleryBinding
    private val mediaList = mutableListOf<VideoMediaItem>()
    private lateinit var adapter: GalleryAdapter

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, VideoEditorActivity::class.java).apply {
                putExtra(VideoEditorActivity.EXTRA_VIDEO_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadMediaFromStorage()
    }

    override fun onResume() {
        super.onResume()
        loadMediaFromStorage()
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter(mediaList,
            onPlay = { item ->
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, item.uri.toString())
                }
                startActivity(intent)
            },
            onEdit = { item ->
                val intent = Intent(this, VideoEditorActivity::class.java).apply {
                    putExtra(VideoEditorActivity.EXTRA_VIDEO_URI, item.uri.toString())
                }
                startActivity(intent)
            },
            onShare = { item ->
                if (item.isVideo) {
                    ShareUtils.shareVideo(this, item.uri)
                } else {
                    ShareUtils.sharePhoto(this, item.uri)
                }
            },
            onDelete = { item ->
                deleteMediaItem(item)
            }
        )

        binding.recyclerMediaHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerMediaHistory.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnImportFromDevice.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }
    }

    private fun loadMediaFromStorage() {
        mediaList.clear()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "video"
                    val duration = cursor.getLong(durationColumn)
                    val size = cursor.getLong(sizeColumn)
                    val date = cursor.getLong(dateColumn)

                    if (name.contains("SlowMo", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        mediaList.add(
                            VideoMediaItem(
                                id = id,
                                uri = contentUri,
                                displayName = name,
                                durationMs = duration,
                                sizeBytes = size,
                                dateAddedSec = date,
                                isVideo = true
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка чтения медиа: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        adapter.notifyDataSetChanged()
        binding.tvEmptyGallery.visibility = if (mediaList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun deleteMediaItem(item: VideoMediaItem) {
        try {
            contentResolver.delete(item.uri, null, null)
            mediaList.remove(item)
            adapter.notifyDataSetChanged()
            binding.tvEmptyGallery.visibility = if (mediaList.isEmpty()) View.VISIBLE else View.GONE
            Toast.makeText(this, "Файл удалён", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка удаления: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private class GalleryAdapter(
        private val items: List<VideoMediaItem>,
        private val onPlay: (VideoMediaItem) -> Unit,
        private val onEdit: (VideoMediaItem) -> Unit,
        private val onShare: (VideoMediaItem) -> Unit,
        private val onDelete: (VideoMediaItem) -> Unit
    ) : RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemGalleryMediaBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemGalleryMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val b = holder.binding

            b.tvMediaTitle.text = item.displayName
            b.tvMediaInfo.text = "${item.durationFormatted} • ${item.sizeFormatted}"

            val dateFormatted = SimpleDateFormat("dd MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.dateAddedSec * 1000))
            b.tvMediaDate.text = dateFormatted

            b.btnPlay.setOnClickListener { onPlay(item) }
            b.btnEdit.setOnClickListener { onEdit(item) }
            b.btnShare.setOnClickListener { onShare(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
