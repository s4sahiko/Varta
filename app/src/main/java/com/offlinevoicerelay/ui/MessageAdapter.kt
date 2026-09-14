package com.offlinevoicerelay.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinevoicerelay.databinding.ItemMessageBinding
import com.offlinevoicerelay.model.MessageEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

    private val items = mutableListOf<MessageEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setMessages(newItems: List<MessageEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: MessageEntry) {
            binding.messageText.text = entry.text
            binding.langTag.text = entry.langCode.uppercase()
            binding.timestampText.text = timeFormat.format(Date(entry.timestampMs))

            if (entry.isSent) {
                binding.directionTag.text = "SENT"
                binding.directionTag.setTextColor(0xFF059669.toInt())
            } else {
                binding.directionTag.text = "RECEIVED"
                binding.directionTag.setTextColor(0xFF0284C7.toInt())
            }

            binding.alertBadge.visibility = if (entry.isAlert) View.VISIBLE else View.GONE
        }
    }
}
