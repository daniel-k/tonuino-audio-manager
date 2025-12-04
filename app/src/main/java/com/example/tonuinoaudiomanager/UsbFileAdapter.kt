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

data class UsbFile(
    val document: DocumentFile,
    val isHidden: Boolean = false,
    val metadata: AudioMetadata? = null
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
            if (!isDirectory && item.metadata?.albumArt != null) {
                binding.icon.setImageBitmap(item.metadata.albumArt)
            } else {
                binding.icon.setImageResource(iconRes)
            }

            if (isDirectory) {
                binding.title.text = name
                binding.subtitle.text = ""
                binding.fileName.text = ""
                binding.subtitle.isVisible = false
                binding.fileName.isVisible = false
            } else {
                val meta = item.metadata
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
