package com.offlinevoicerelay.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.offlinevoicerelay.R
import com.offlinevoicerelay.databinding.ItemDeviceBinding
import com.offlinevoicerelay.mesh.DiscoveredDevice

class DeviceListAdapter(
    private val onDeviceClicked: (DiscoveredDevice) -> Unit,
    private val onConnectClicked: (DiscoveredDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private val items = mutableListOf<DiscoveredDevice>()

    fun setDevices(newItems: List<DiscoveredDevice>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DiscoveredDevice) {
            binding.deviceNameText.text = device.name

            val isBt = device.transport.contains("Bluetooth", ignoreCase = true)
            binding.deviceIcon.setImageResource(if (isBt) R.drawable.ic_bluetooth else R.drawable.ic_wifi)
            binding.deviceIcon.imageTintList = ColorStateList.valueOf(0xFF121417.toInt())

            if (device.isConnected) {
                val details = when {
                    device.transport.contains("BLE", ignoreCase = true) && (device.transport.contains("Wi-Fi", ignoreCase = true) || device.transport.contains("WiFi", ignoreCase = true)) -> "Connected • BLE & Wi-Fi"
                    device.transport.contains("BLE", ignoreCase = true) || device.transport.contains("Bluetooth", ignoreCase = true) -> "Connected • BLE"
                    device.transport.contains("Wi-Fi", ignoreCase = true) || device.transport.contains("WiFi", ignoreCase = true) -> "Connected • Wi-Fi Direct"
                    else -> "Connected • Mesh Relay"
                }
                binding.deviceDetailsText.text = details
                binding.deviceDetailsText.setTextColor(0xFF059669.toInt())

                binding.actionButton.text = "Chat"
                binding.actionButton.backgroundTintList = ColorStateList.valueOf(0xFF121417.toInt())
                binding.actionButton.setTextColor(0xFFFFFFFF.toInt())
                binding.actionButton.strokeWidth = 0

                binding.actionButton.setOnClickListener { onDeviceClicked(device) }
                binding.root.setOnClickListener { onDeviceClicked(device) }
            } else if (device.isConnecting) {
                binding.deviceDetailsText.text = "Connecting..."
                binding.deviceDetailsText.setTextColor(0xFFD97706.toInt())

                binding.actionButton.text = "Connecting"
                binding.actionButton.backgroundTintList = ColorStateList.valueOf(0xFFFFFBEB.toInt())
                binding.actionButton.setTextColor(0xFFD97706.toInt())
                binding.actionButton.strokeColor = ColorStateList.valueOf(0xFFFDE68A.toInt())
                binding.actionButton.strokeWidth = 1

                binding.actionButton.setOnClickListener(null)
                binding.root.setOnClickListener(null)
            } else {
                val shortTransport = when {
                    device.transport.contains("WiFi", ignoreCase = true) || device.transport.contains("Wi-Fi", ignoreCase = true) -> "Wi-Fi Direct"
                    device.transport.contains("Bluetooth", ignoreCase = true) || device.transport.contains("BLE", ignoreCase = true) -> "Bluetooth"
                    else -> device.transport
                }
                binding.deviceDetailsText.text = "In Range • $shortTransport"
                binding.deviceDetailsText.setTextColor(0xFF6B7280.toInt())

                binding.actionButton.text = "Connect"
                binding.actionButton.backgroundTintList = ColorStateList.valueOf(0xFFF1F3F5.toInt())
                binding.actionButton.setTextColor(0xFF121417.toInt())
                binding.actionButton.strokeColor = ColorStateList.valueOf(0xFFE5E7EB.toInt())
                binding.actionButton.strokeWidth = 1

                binding.actionButton.setOnClickListener { onConnectClicked(device) }
                binding.root.setOnClickListener { onConnectClicked(device) }
            }
        }
    }
}
