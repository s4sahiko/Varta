package com.offlinevoicerelay.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinevoicerelay.databinding.ItemChatBubbleBinding
import com.offlinevoicerelay.model.MessageEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val onSpeakerClicked: (MessageEntry) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val items = mutableListOf<MessageEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setMessages(newItems: List<MessageEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatBubbleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemChatBubbleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: MessageEntry) {
            val formattedTime = timeFormat.format(Date(entry.timestampMs))

            if (entry.isSent) {
                binding.sentContainer.visibility = View.VISIBLE
                binding.receivedContainer.visibility = View.GONE

                binding.sentMessageText.text = entry.text
                binding.sentLangTag.text = "SENT • ${entry.langCode.uppercase()}"
                binding.sentTimestampText.text = formattedTime
            } else {
                binding.sentContainer.visibility = View.GONE
                binding.receivedContainer.visibility = View.VISIBLE

                binding.receivedMessageText.text = entry.text
                binding.receivedLangTag.text = "RECEIVED • ${entry.langCode.uppercase()}"
                binding.receivedTimestampText.text = formattedTime

                binding.speakerButton.setOnClickListener {
                    onSpeakerClicked(entry)
                }
            }
        }
    }
}
