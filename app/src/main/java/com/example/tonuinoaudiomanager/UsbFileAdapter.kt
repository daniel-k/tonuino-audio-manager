package com.example.tonuinoaudiomanager

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView
import com.example.tonuinoaudiomanager.databinding.ItemUsbFileBinding
import android.graphics.Bitmap

data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: String? = null,
    val albumArt: Bitmap? = null
)

data class DirectorySummary(
    val album: String? = null,
    val artist: String? = null,
    val albumArt: Bitmap? = null,
    val trackCount: Int = 0,
    val otherAlbumCount: Int = 0
)

data class UsbFile(
    val document: DocumentFile,
    val isHidden: Boolean = false,
    val metadata: AudioMetadata? = null,
    val directorySummary: DirectorySummary? = null
)

class UsbFileAdapter(
    private val onDirectoryClick: (DocumentFile) -> Unit
) : RecyclerView.Adapter<UsbFileAdapter.UsbFileViewHolder>() {

    private val items = mutableListOf<UsbFile>()
    private var reorderMode = false
    private var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsbFileViewHolder {
        val binding = ItemUsbFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsbFileViewHolder(binding, onDirectoryClick)
    }

    override fun onBindViewHolder(holder: UsbFileViewHolder, position: Int) {
        holder.bind(items[position], reorderMode)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(files: List<UsbFile>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun getItems(): List<UsbFile> = items.toList()

    fun setReorderMode(enabled: Boolean) {
        reorderMode = enabled
        notifyDataSetChanged()
    }

    fun setOnStartDrag(listener: ((RecyclerView.ViewHolder) -> Unit)?) {
        onStartDrag = listener
    }

    fun onItemMove(from: Int, to: Int) {
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun isReorderable(position: Int): Boolean {
        val item = items.getOrNull(position) ?: return false
        return reorderMode &&
                !item.isHidden &&
                item.document.isFile &&
                item.document.name?.endsWith(".mp3", ignoreCase = true) == true
    }

    inner class UsbFileViewHolder(
        private val binding: ItemUsbFileBinding,
        private val onDirectoryClick: (DocumentFile) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UsbFile, reorderMode: Boolean) {
            val isDirectory = item.document.isDirectory
            val name = item.document.name ?: "(unnamed)"
            val iconRes = if (isDirectory) {
                R.drawable.ic_folder_24
            } else {
                R.drawable.ic_file_24
            }

            if (isDirectory) {
                val summary = item.directorySummary
                val albumArt = summary?.albumArt
                if (albumArt != null) {
                    binding.icon.setImageBitmap(albumArt)
                } else {
                    binding.icon.setImageResource(iconRes)
                }

                if (summary != null) {
                    val baseAlbum = summary.album?.takeIf { it.isNotBlank() } ?: name
                    val albumText = if (summary.otherAlbumCount > 0 && baseAlbum.isNotBlank()) {
                        "$baseAlbum + ${summary.otherAlbumCount} more"
                    } else {
                        baseAlbum
                    }
                    val artistText = summary.artist.orEmpty()
                    val trackCount = summary.trackCount
                    val trackCountText = binding.root.resources.getQuantityString(
                        R.plurals.folder_track_count,
                        trackCount,
                        trackCount
                    )

                    binding.title.text = albumText
                    binding.subtitle.text = artistText
                    binding.subtitle.isVisible = artistText.isNotBlank()
                    binding.fileName.text = trackCountText
                    binding.fileName.isVisible = true
                } else {
                    binding.title.text = name
                    binding.subtitle.text = ""
                    binding.fileName.text = ""
                    binding.subtitle.isVisible = false
                    binding.fileName.isVisible = false
                }
            } else {
                val meta = item.metadata
                if (meta?.albumArt != null) {
                    binding.icon.setImageBitmap(meta.albumArt)
                } else {
                    binding.icon.setImageResource(iconRes)
                }

                val track = meta?.trackNumber?.takeIf { it.isNotBlank() }
                val titleText = buildString {
                    if (!track.isNullOrBlank()) append("$track · ")
                    append(meta?.title?.takeIf { it.isNotBlank() } ?: name)
                }
                val subtitleText = listOfNotNull(
                    meta?.artist?.takeIf { it.isNotBlank() },
                    meta?.album?.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                binding.title.text = titleText
                binding.subtitle.text = subtitleText
                binding.subtitle.isVisible = subtitleText.isNotEmpty()

                binding.fileName.text = name
                binding.fileName.isVisible = true
            }

            val fadedAlpha = if (item.isHidden) 0.55f else 1f
            binding.icon.alpha = fadedAlpha
            binding.title.alpha = fadedAlpha
            binding.subtitle.alpha = fadedAlpha
            binding.fileName.alpha = fadedAlpha
            binding.root.isClickable = !reorderMode && isDirectory
            binding.root.isFocusable = !reorderMode && isDirectory
            binding.root.setOnClickListener {
                if (!reorderMode && isDirectory) {
                    onDirectoryClick(item.document)
                }
            }
            binding.dragHandle.isVisible = reorderMode && !item.isHidden && !isDirectory
            binding.dragHandle.setOnTouchListener { _, event ->
                if (reorderMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                    true
                } else {
                    false
                }
            }
        }
    }
}
