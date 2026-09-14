package com.offlinevoicerelay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.offlinevoicerelay.databinding.ActivityDeviceListBinding
import com.offlinevoicerelay.mesh.DiscoveredDevice
import com.offlinevoicerelay.vad.VadForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private var service: VadForegroundService? = null
    private var bound = false

    private lateinit var connectedAdapter: DeviceListAdapter
    private lateinit var availableAdapter: DeviceListAdapter

    private var currentFilter = FILTER_ALL

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VadForegroundService.LocalBinder).getService()
            bound = true
            observePeers()
            startPeriodicDiscoveryRefresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialTab = intent.getStringExtra(EXTRA_INITIAL_TAB) ?: FILTER_ALL
        currentFilter = initialTab

        setupRecyclerViews()
        setupListeners()
        updateTabStyles()

        val serviceIntent = Intent(this, VadForegroundService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun setupRecyclerViews() {
        connectedAdapter = DeviceListAdapter(
            onDeviceClicked = { device -> openChat(device) },
            onConnectClicked = { device -> openChat(device) }
        )
        binding.connectedRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.connectedRecyclerView.adapter = connectedAdapter

        availableAdapter = DeviceListAdapter(
            onDeviceClicked = { device -> attemptConnect(device) },
            onConnectClicked = { device -> attemptConnect(device) }
        )
        binding.availableRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.availableRecyclerView.adapter = availableAdapter
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener { finish() }

        binding.refreshScanButton.setOnClickListener {
            service?.triggerScan()
            binding.scanStatusText.text = "Actively discovering Bluetooth & Wi-Fi peers..."
            Toast.makeText(this, "Scanning for emergency nodes...", Toast.LENGTH_SHORT).show()
        }

        binding.tabAll.setOnClickListener {
            currentFilter = FILTER_ALL
            updateTabStyles()
            refreshDeviceLists()
        }

        binding.tabBluetooth.setOnClickListener {
            currentFilter = FILTER_BLUETOOTH
            updateTabStyles()
            refreshDeviceLists()
        }

        binding.tabWifi.setOnClickListener {
            currentFilter = FILTER_WIFI
            updateTabStyles()
            refreshDeviceLists()
        }
    }

    private fun updateTabStyles() {
        fun styleTab(button: MaterialButton, active: Boolean) {
            if (active) {
                button.backgroundTintList = ColorStateList.valueOf(0xFF121417.toInt())
                button.setTextColor(0xFFFFFFFF.toInt())
                button.strokeWidth = 0
            } else {
                button.backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                button.setTextColor(0xFF6B7280.toInt())
                button.strokeColor = ColorStateList.valueOf(0xFFE5E7EB.toInt())
                button.strokeWidth = 1
            }
        }

        styleTab(binding.tabAll, currentFilter == FILTER_ALL)
        styleTab(binding.tabBluetooth, currentFilter == FILTER_BLUETOOTH)
        styleTab(binding.tabWifi, currentFilter == FILTER_WIFI)
    }

    private var statusJob: kotlinx.coroutines.Job? = null

    private fun observePeers() {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service?.status?.collect {
                    refreshDeviceLists()
                }
            }
        }
    }

    private fun startPeriodicDiscoveryRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                refreshDeviceLists()
                delay(2000)
            }
        }
    }

    private fun refreshDeviceLists() {
        val s = service ?: return
        val connectedPeers = s.status.value.connectedPeers
        val btPeers = s.getConnectedBtPeers().map { it.deviceId }.toSet()
        val wifiPeers = s.getConnectedWifiPeers().map { it.deviceId }.toSet()
        val connectedIds = connectedPeers.map { it.deviceId }.toSet()
        val connectedNames = connectedPeers.map { it.displayName.lowercase().trim() }.toSet()

        // 1. Connected Devices (check BLE vs Wi-Fi Direct)
        val connectedDevices = connectedPeers.map { peer ->
            val hasBt = btPeers.contains(peer.deviceId)
            val hasWifi = wifiPeers.contains(peer.deviceId)
            val transportType = when {
                hasBt && hasWifi -> "BLE & Wi-Fi Direct"
                hasBt -> "Connected with BLE"
                hasWifi -> "Connected with Wi-Fi Direct"
                else -> "Mesh Relay Peer"
            }
            DiscoveredDevice(
                addressOrId = peer.deviceId,
                name = peer.displayName,
                transport = transportType,
                isConnected = true
            )
        }.filter { matchesFilter(it) }

        connectedAdapter.setDevices(connectedDevices)
        binding.noConnectedText.visibility = if (connectedDevices.isEmpty()) View.VISIBLE else View.GONE

        // 2. Available Devices (Discovered via Bluetooth / Wi-Fi Direct, not connected yet)
        val discovered = s.getDiscoveredDevices()
        val availableDevices = discovered.filter { dev ->
            val isAlreadyConnected = dev.isConnected ||
                    connectedIds.contains(dev.addressOrId) ||
                    connectedNames.contains(dev.name.lowercase().trim()) ||
                    connectedPeers.any { it.displayName.equals(dev.name, ignoreCase = true) }
            !isAlreadyConnected && matchesFilter(dev)
        }

        availableAdapter.setDevices(availableDevices)
        binding.noAvailableText.visibility = if (availableDevices.isEmpty()) View.VISIBLE else View.GONE
        
        binding.scanStatusText.text = if (connectedDevices.isNotEmpty()) {
            "Connected to ${connectedDevices.size} peer(s) • BLE: ${btPeers.size}, Wi-Fi: ${wifiPeers.size}"
        } else {
            "Scanning for Bluetooth & Wi-Fi Direct peers..."
        }
    }

    private fun matchesFilter(device: DiscoveredDevice): Boolean {
        return when (currentFilter) {
            FILTER_BLUETOOTH -> device.transport.contains("Bluetooth", ignoreCase = true) || device.transport.contains("BLE", ignoreCase = true)
            FILTER_WIFI -> device.transport.contains("WiFi", ignoreCase = true) || device.transport.contains("Wi-Fi", ignoreCase = true)
            else -> true
        }
    }

    private fun openChat(device: DiscoveredDevice) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_ID, device.addressOrId)
            putExtra(ChatActivity.EXTRA_PEER_NAME, device.name)
        }
        startActivity(intent)
    }

    private fun attemptConnect(device: DiscoveredDevice) {
        service?.connectToDevice(device)
        Toast.makeText(this, "Connecting to ${device.name} via ${device.transport}...", Toast.LENGTH_SHORT).show()
        refreshDeviceLists()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        const val FILTER_ALL = "ALL"
        const val FILTER_BLUETOOTH = "BLUETOOTH"
        const val FILTER_WIFI = "WIFI"
    }
}
