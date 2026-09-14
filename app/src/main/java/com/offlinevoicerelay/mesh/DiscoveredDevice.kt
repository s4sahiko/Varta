package com.offlinevoicerelay.mesh

data class DiscoveredDevice(
    val addressOrId: String,
    val name: String,
    val transport: String, // "Bluetooth", "WiFi Direct", or "BLE & Wi-Fi Direct"
    val isConnected: Boolean,
    val isConnecting: Boolean = false,
    val rssi: Int = 0
)
