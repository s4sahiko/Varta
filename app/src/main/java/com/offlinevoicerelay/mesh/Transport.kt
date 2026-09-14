package com.offlinevoicerelay.mesh

/**
 * Data structure representing a connected peer node identified via handshake.
 */
data class PeerInfo(
    val deviceId: String,
    val displayName: String
)

/**
 * Common interface for the two radio transports (Section 4: "WifiP2pManager
 * (WiFi Direct) primary; BluetoothAdapter/BLE GATT fallback").
 */
interface Transport {
    /** Human-readable name for logs/UI ("WiFi Direct", "BLE"). */
    val name: String

    /** Begins peer discovery + accepts inbound connections. Idempotent. */
    fun start()

    /** Tears down connections and stops discovery. */
    fun stop()

    /** Sends raw bytes to every currently connected peer. */
    fun broadcast(bytes: ByteArray)

    /** Sends raw bytes directly to a specific connected peer by deviceId. */
    fun sendToPeer(peerId: String, bytes: ByteArray): Boolean

    /** Number of peers currently connected. */
    fun connectedPeerCount(): Int

    /** Returns list of identified connected peers. */
    fun getConnectedPeerList(): List<PeerInfo>

    fun setOnBytesReceivedListener(listener: (ByteArray) -> Unit)
    fun setOnPeerCountChangedListener(listener: (Int) -> Unit)
    fun setOnPeerListChangedListener(listener: (List<PeerInfo>) -> Unit)
}
