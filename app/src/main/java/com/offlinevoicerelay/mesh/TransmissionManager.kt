package com.offlinevoicerelay.mesh

import android.util.Log
import com.offlinevoicerelay.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TransmissionManager(
    private val wifiDirect: Transport,
    private val bluetooth: Transport,
    private val meshRouter: MeshRouter,
    private val scope: CoroutineScope,
    private val watchdogIntervalMs: Long = 5_000L
) {
    private var onMessageForPlayback: (Message) -> Unit = {}
    private var onPeerCountChanged: (wifi: Int, bluetooth: Int) -> Unit = { _, _ -> }
    private var watchdogJob: Job? = null

    private val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers

    fun setOnMessageForPlaybackListener(listener: (Message) -> Unit) { onMessageForPlayback = listener }
    fun setOnPeerCountChangedListener(listener: (wifi: Int, bluetooth: Int) -> Unit) { onPeerCountChanged = listener }

    fun start() {
        wifiDirect.setOnBytesReceivedListener(::handleIncomingBytes)
        bluetooth.setOnBytesReceivedListener(::handleIncomingBytes)

        wifiDirect.setOnPeerCountChangedListener { notifyPeerCounts() }
        bluetooth.setOnPeerCountChangedListener { notifyPeerCounts() }

        wifiDirect.setOnPeerListChangedListener { notifyPeerCounts() }
        bluetooth.setOnPeerListChangedListener { notifyPeerCounts() }

        wifiDirect.start()
        bluetooth.start()
        startWatchdog()
    }

    fun stop() {
        watchdogJob?.cancel()
        wifiDirect.stop()
        bluetooth.stop()
    }

    /** Send a message this device originated via broadcast / walkie-talkie mode. */
    fun sendOriginated(message: Message) {
        meshRouter.markAsOriginated(message.msgId)
        broadcastOnActiveTransports(message)
    }

    /** Send a message this device originated directly to a specific connected peer. */
    fun sendToPeer(peerId: String, message: Message): Boolean {
        meshRouter.markAsOriginated(message.msgId)
        val bytes = message.toWireBytes()
        var sent = false

        if (wifiDirect.connectedPeerCount() > 0) {
            if (wifiDirect.sendToPeer(peerId, bytes)) sent = true
        }

        if (bluetooth.connectedPeerCount() > 0) {
            if (bluetooth.sendToPeer(peerId, bytes)) sent = true
        }

        return sent
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val message = runCatching { Message.fromWireBytes(bytes) }
            .getOrElse {
                Log.w(TAG, "dropped malformed payload: ${it.message}")
                return
            }

        val decision = meshRouter.onMessageReceived(message)
        if (decision.deliverLocally) onMessageForPlayback(message)
        decision.relay?.let { broadcastOnActiveTransports(it) }
    }

    private fun broadcastOnActiveTransports(message: Message) {
        val bytes = message.toWireBytes()
        if (wifiDirect.connectedPeerCount() > 0) wifiDirect.broadcast(bytes)
        if (bluetooth.connectedPeerCount() > 0) bluetooth.broadcast(bytes)
    }

    private fun startWatchdog() {
        watchdogJob = scope.launch {
            while (true) {
                delay(watchdogIntervalMs * 4)
                notifyPeerCounts()
            }
        }
    }

    private fun notifyPeerCounts() {
        val wifiPeers = wifiDirect.getConnectedPeerList()
        val btPeers = bluetooth.getConnectedPeerList()
        val combinedPeers = (wifiPeers + btPeers)
            .filter { it.deviceId.isNotBlank() }
            .distinctBy { it.deviceId }

        onPeerCountChanged(wifiPeers.size, btPeers.size)
        _connectedPeers.value = combinedPeers
    }

    fun getConnectedBtPeers(): List<PeerInfo> = bluetooth.getConnectedPeerList()
    fun getConnectedWifiPeers(): List<PeerInfo> = wifiDirect.getConnectedPeerList()

    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        val btDevices = (bluetooth as? BleTransport)?.getDiscoveredDevices() ?: emptyList()
        val wifiDevices = (wifiDirect as? WifiDirectTransport)?.getDiscoveredDevices() ?: emptyList()
        return (btDevices + wifiDevices).distinctBy { it.addressOrId }
    }

    fun connectToDevice(device: DiscoveredDevice) {
        if (device.transport.contains("Bluetooth", ignoreCase = true) || device.transport.contains("BLE", ignoreCase = true)) {
            (bluetooth as? BleTransport)?.connectToDevice(device.addressOrId)
        } else {
            (wifiDirect as? WifiDirectTransport)?.connectToDevice(device.addressOrId)
        }
    }

    fun triggerScan() {
        wifiDirect.start()
        bluetooth.start()
    }

    companion object {
        private const val TAG = "TransmissionManager"
    }
}
