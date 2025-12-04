package com.example.tonuinoaudiomanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tonuinoaudiomanager.databinding.ItemUsbFileBinding

data class UsbFile(val name: String, val isDirectory: Boolean)

class UsbFileAdapter : RecyclerView.Adapter<UsbFileAdapter.UsbFileViewHolder>() {

    private val items = mutableListOf<UsbFile>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsbFileViewHolder {
        val binding = ItemUsbFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UsbFileViewHolder(binding)
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
        private val binding: ItemUsbFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UsbFile) {
            binding.name.text = item.name
            val iconRes = if (item.isDirectory) {
                android.R.drawable.ic_menu_sort_by_size
            } else {
                android.R.drawable.ic_menu_save
            }
            binding.icon.setImageResource(iconRes)
        }
    }
}
