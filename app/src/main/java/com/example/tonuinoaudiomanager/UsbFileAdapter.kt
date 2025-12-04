package com.example.tonuinoaudiomanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tonuinoaudiomanager.databinding.ItemUsbFileBinding
import androidx.documentfile.provider.DocumentFile

data class UsbFile(val document: DocumentFile)

class UsbFileAdapter(
    private val onDirectoryClick: (DocumentFile) -> Unit
) : RecyclerView.Adapter<UsbFileAdapter.UsbFileViewHolder>() {

    private val items = mutableListOf<UsbFile>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsbFileViewHolder {
        val binding = ItemUsbFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsbFileViewHolder(binding, onDirectoryClick)
    }

    override fun onBindViewHolder(holder: UsbFileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    fun submitList(files: List<UsbFile>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    class UsbFileViewHolder(
        private val binding: ItemUsbFileBinding,
        private val onDirectoryClick: (DocumentFile) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UsbFile) {
            binding.name.text = item.document.name ?: "(unnamed)"
            val isDirectory = item.document.isDirectory
            val iconRes = if (isDirectory) {
                R.drawable.ic_folder_24
            } else {
                R.drawable.ic_file_24
            }
            binding.icon.setImageResource(iconRes)
            binding.root.isClickable = isDirectory
            binding.root.isFocusable = isDirectory
            binding.root.setOnClickListener {
                if (isDirectory) {
                    onDirectoryClick(item.document)
                }
            }
        }
    }
}
