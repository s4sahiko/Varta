package com.offlinevoicerelay.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinevoicerelay.databinding.ItemLanguagePickerBinding
import com.offlinevoicerelay.model.Language

class LanguagePickerAdapter(
    private var selectedLanguage: Language,
    private val onLanguageSelected: (Language) -> Unit
) : RecyclerView.Adapter<LanguagePickerAdapter.ViewHolder>() {

    private val items = Language.entries.toList()

    fun setSelectedLanguage(language: Language) {
        selectedLanguage = language
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLanguagePickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemLanguagePickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(lang: Language) {
            val isSelected = (lang == selectedLanguage)

            binding.nativeNameText.text = lang.displayName
            binding.englishNameText.text = lang.englishName
            binding.codeBadgeText.text = lang.code.uppercase()

            if (isSelected) {
                binding.cardContainer.setCardBackgroundColor(ColorStateList.valueOf(0xFFF0FDF4.toInt()))
                binding.cardContainer.strokeColor = 0xFF10B981.toInt()
                binding.cardContainer.strokeWidth = 2
                binding.selectedCheckIcon.visibility = View.VISIBLE
                binding.codeBadgeText.backgroundTintList = ColorStateList.valueOf(0xFF10B981.toInt())
                binding.codeBadgeText.setTextColor(0xFFFFFFFF.toInt())
            } else {
                binding.cardContainer.setCardBackgroundColor(ColorStateList.valueOf(0xFFFFFFFF.toInt()))
                binding.cardContainer.strokeColor = 0xFFE5E7EB.toInt()
                binding.cardContainer.strokeWidth = 1
                binding.selectedCheckIcon.visibility = View.GONE
                binding.codeBadgeText.backgroundTintList = ColorStateList.valueOf(0xFFF1F3F5.toInt())
                binding.codeBadgeText.setTextColor(0xFF6B7280.toInt())
            }

            binding.cardContainer.setOnClickListener {
                onLanguageSelected(lang)
            }
        }
    }
}
