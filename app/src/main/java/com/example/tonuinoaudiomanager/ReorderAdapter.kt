package com.example.tonuinoaudiomanager

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tonuinoaudiomanager.databinding.ItemReorderFileBinding

data class ReorderItem(
    val document: androidx.documentfile.provider.DocumentFile,
    val label: String
)

class ReorderAdapter(
    items: List<ReorderItem>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ReorderAdapter.ReorderViewHolder>() {

    private val data = items.toMutableList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReorderViewHolder {
        val binding =
            ItemReorderFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ReorderViewHolder(binding, onStartDrag)
    }

    override fun onBindViewHolder(holder: ReorderViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun getItemCount(): Int = data.size

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        val item = data.removeAt(fromPosition)
        data.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun currentOrder(): List<ReorderItem> = data.toList()

    class ReorderViewHolder(
        private val binding: ItemReorderFileBinding,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ReorderItem) {
            binding.fileName.text = item.label
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }
    }
}
