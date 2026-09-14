package com.offlinevoicerelay.mesh

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class WifiDirectTransport(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val localDeviceId: String = "wdf-" + UUID.randomUUID().toString().take(6),
    private val localDisplayName: String = Build.MODEL
) : Transport {

    override val name = "WiFi Direct"

    private var serverSocket: ServerSocket? = null
    private val peerSockets = CopyOnWriteArrayList<Socket>()
    private val socketToPeerInfo = ConcurrentHashMap<Socket, PeerInfo>()

    private val executor = Executors.newFixedThreadPool(8)
    private var running = false

    private var onBytesReceived: (ByteArray) -> Unit = {}
    private var onPeerCountChanged: (Int) -> Unit = {}
    private var onPeerListChanged: (List<PeerInfo>) -> Unit = {}

    private val discoveredWifiDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    private var discoveryJob: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    override fun setOnBytesReceivedListener(listener: (ByteArray) -> Unit) { onBytesReceived = listener }
    override fun setOnPeerCountChangedListener(listener: (Int) -> Unit) { onPeerCountChanged = listener }
    override fun setOnPeerListChangedListener(listener: (List<PeerInfo>) -> Unit) { onPeerListChanged = listener }

    override fun getConnectedPeerList(): List<PeerInfo> = socketToPeerInfo.values.toList()

    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        val connectedDevIds = socketToPeerInfo.values.map { it.deviceId }.toSet()
        val connectedNames = socketToPeerInfo.values.map { it.displayName.lowercase() }.toSet()
        return discoveredWifiDevices.values.map { dev ->
            val isConn = dev.isConnected || 
                         connectedDevIds.contains(dev.addressOrId) ||
                         connectedNames.contains(dev.name.lowercase())
            dev.copy(isConnected = isConn)
        }
    }

    fun updateDiscoveredWifiDevices(devices: Collection<WifiP2pDevice>) {
        discoveredWifiDevices.clear()
        devices.forEach { dev ->
            val isConn = dev.status == WifiP2pDevice.CONNECTED
            val isConnecting = dev.status == WifiP2pDevice.INVITED
            val dName = if (dev.deviceName.isNullOrBlank()) "Wi-Fi Peer (${dev.deviceAddress.take(5)})" else dev.deviceName
            discoveredWifiDevices[dev.deviceAddress] = DiscoveredDevice(
                addressOrId = dev.deviceAddress,
                name = dName,
                transport = "WiFi Direct",
                isConnected = isConn,
                isConnecting = isConnecting
            )
        }
    }


    override fun start() {
        if (running) return
        running = true
        startServer()
        setupAppServiceDiscovery()
        discoverPeers()
        startPeerDiscoveryLoop()
    }

    private fun setupAppServiceDiscovery() {
        val record = mapOf(
            "app" to "OfflineVoiceRelay",
            "deviceId" to localDeviceId,
            "name" to localDisplayName,
            "port" to PORT.toString()
        )
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE,
            SERVICE_TYPE,
            record
        )
        manager.addLocalService(channel, serviceInfo, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Registered local Wi-Fi Direct service")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "addLocalService failed: $reason")
            }
        })

        manager.setDnsSdResponseListeners(channel,
            { instanceName, registrationType, srcDevice ->
                Log.i(TAG, "Found Wi-Fi Direct service: $instanceName from ${srcDevice.deviceName}")
            },
            { fullDomainName, txtRecordMap, srcDevice ->
                val app = txtRecordMap["app"]
                if (app == "OfflineVoiceRelay" || app == "Varta") {
                    val peerName = txtRecordMap["name"] ?: srcDevice.deviceName
                    discoveredWifiDevices[srcDevice.deviceAddress] = DiscoveredDevice(
                        addressOrId = srcDevice.deviceAddress,
                        name = peerName,
                        transport = "WiFi Direct",
                        isConnected = false,
                        isConnecting = false
                    )
                    val peerDeviceId = txtRecordMap["deviceId"] ?: ""
                    val isHigherPriority = localDeviceId > peerDeviceId
                    if (connectedPeerCount() == 0) {
                        connectToDevice(srcDevice.deviceAddress, isHigherPriority)
                    }
                }
            }
        )

        val req = WifiP2pDnsSdServiceRequest.newInstance()
        serviceRequest = req
        manager.addServiceRequest(channel, req, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.discoverServices(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "discoverServices started successfully")
                    }
                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "discoverServices failed: $reason")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "addServiceRequest failed: $reason")
            }
        })
    }

    private fun startPeerDiscoveryLoop() {
        discoveryJob?.cancel(true)
        discoveryJob = scheduledExecutor.scheduleWithFixedDelay({
            if (running && !isDiscovering) {
                discoverPeers()
            }
        }, 12, 20, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun stop() {
        running = false
        isDiscovering = false
        discoveryJob?.cancel(true)
        discoveryJob = null
        runCatching { manager.clearLocalServices(channel, null) }
        runCatching { manager.clearServiceRequests(channel, null) }
        runCatching { serverSocket?.close() }
        peerSockets.forEach { runCatching { it.close() } }
        peerSockets.clear()
        socketToPeerInfo.clear()
        runCatching { manager.stopPeerDiscovery(channel, null) }
        notifyPeerCounts()
    }

    override fun broadcast(bytes: ByteArray) {
        val framedBytes = prepareFramedBytes(FRAME_MESSAGE, bytes)
        peerSockets.forEach { socket ->
            executor.submit {
                sendFramedBytesToSocket(socket, framedBytes)
            }
        }
    }

    override fun sendToPeer(peerId: String, bytes: ByteArray): Boolean {
        val entry = socketToPeerInfo.entries.firstOrNull { it.value.deviceId == peerId } ?: return false
        val socket = entry.key
        val framedBytes = prepareFramedBytes(FRAME_MESSAGE, bytes)

        executor.submit { sendFramedBytesToSocket(socket, framedBytes) }
        return true
    }

    override fun connectedPeerCount(): Int = socketToPeerInfo.size

    private fun notifyPeerCounts() {
        val list = getConnectedPeerList()
        onPeerCountChanged(list.size)
        onPeerListChanged(list)
    }

    private fun sendHandshake(socket: Socket) {
        executor.submit {
            val json = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("deviceId", localDeviceId)
                put("name", localDisplayName)
            }.toString()

            val handshakeBytes = json.toByteArray(Charsets.UTF_8)
            val framedBytes = prepareFramedBytes(FRAME_HANDSHAKE, handshakeBytes)
            sendFramedBytesToSocket(socket, framedBytes)
        }
    }

    private fun prepareFramedBytes(frameType: Byte, payload: ByteArray): ByteArray {
        val totalLen = 1 + payload.size
        val buf = java.nio.ByteBuffer.allocate(4 + totalLen).order(java.nio.ByteOrder.BIG_ENDIAN)
        buf.putInt(totalLen)
        buf.put(frameType)
        buf.put(payload)
        return buf.array()
    }

    private fun sendFramedBytesToSocket(socket: Socket, framedBytes: ByteArray) {
        runCatching {
            val out = DataOutputStream(socket.getOutputStream())
            out.write(framedBytes)
            out.flush()
        }.onFailure {
            Log.w(TAG, "socket write failed, dropping socket: ${it.message}")
            peerSockets.remove(socket)
            socketToPeerInfo.remove(socket)
            notifyPeerCounts()
        }
    }

    private fun startServer() {
        executor.submit {
            runCatching {
                val server = ServerSocket(PORT)
                server.soTimeout = 1_000
                serverSocket = server
                while (running) {
                    try {
                        val socket = server.accept()
                        peerSockets.add(socket)
                        notifyPeerCounts()
                        sendHandshake(socket)
                        listenOn(socket)
                    } catch (_: java.net.SocketTimeoutException) {
                    }
                }
            }.onFailure {
                if (running) Log.w(TAG, "server socket ended: ${it.message}")
            }
        }
    }

    fun onGroupInfoAvailable(isGroupOwner: Boolean, groupOwnerAddress: String?) {
        isConnecting = false
        val targetIp = groupOwnerAddress ?: "192.168.49.1"
        if (isGroupOwner) {
            Log.i(TAG, "This device is Group Owner. ServerSocket is listening on port $PORT.")
            return
        }
        executor.submit {
            var connected = false
            for (attempt in 1..5) {
                if (!running) break
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(targetIp, PORT), CONNECT_TIMEOUT_MS)
                    peerSockets.add(socket)
                    notifyPeerCounts()
                    sendHandshake(socket)
                    Log.i(TAG, "Connected to Wi-Fi Direct host at $targetIp:$PORT on attempt $attempt")
                    connected = true
                    listenOn(socket)
                    break
                } catch (e: Exception) {
                    Log.i(TAG, "Client connect attempt $attempt to $targetIp failed: ${e.message}")
                    if (attempt < 5) Thread.sleep(1000)
                }
            }
            if (!connected) {
                Log.w(TAG, "Failed to connect to GO at $targetIp after 5 attempts")
            }
        }
    }

    private fun listenOn(socket: Socket) {
        executor.submit {
            val input = DataInputStream(socket.getInputStream())
            while (running && !socket.isClosed) {
                val len = runCatching { input.readInt() }.getOrNull() ?: break
                if (len <= 0 || len > MAX_MESSAGE_BYTES) break
                val framedData = ByteArray(len)
                if (runCatching { input.readFully(framedData) }.isFailure) break
                processFramedPayload(socket, framedData)
            }
            peerSockets.remove(socket)
            socketToPeerInfo.remove(socket)
            notifyPeerCounts()
        }
    }

    private fun processFramedPayload(socket: Socket, framedData: ByteArray) {
        if (framedData.isEmpty()) return
        val frameType = framedData[0]
        val payload = framedData.copyOfRange(1, framedData.size)

        when (frameType) {
            FRAME_HANDSHAKE -> {
                runCatching {
                    val json = JSONObject(payload.decodeToString())
                    if (json.optString("type") == "HANDSHAKE") {
                        val devId = json.getString("deviceId")
                        val name = json.optString("name", "Phone-${devId.take(4)}")
                        val peerInfo = PeerInfo(deviceId = devId, displayName = name)
                        socketToPeerInfo[socket] = peerInfo
                        Log.i(TAG, "WiFi Direct registered peer handshake: $peerInfo")
                        notifyPeerCounts()
                    }
                }.onFailure { Log.w(TAG, "Malformed handshake from socket: ${it.message}") }
            }
            FRAME_MESSAGE -> {
                runCatching { onBytesReceived(payload) }
                    .onFailure { Log.e(TAG, "Error handling message payload: ${it.message}") }
            }
            else -> Log.w(TAG, "Unknown frame type $frameType from socket")
        }
    }

    private var isDiscovering = false
    private var isConnecting = false

    private fun discoverPeers() {
        if (isDiscovering) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                Log.i(TAG, "discoverPeers started successfully")
            }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    isDiscovering = true
                    Log.i(TAG, "discoverPeers: discovery is actively in progress (BUSY)")
                } else {
                    isDiscovering = false
                    Log.w(TAG, "discoverPeers failed: $reason")
                }
            }
        })
    }

    fun onPeersAvailable(devices: Collection<WifiP2pDevice>) {
        isDiscovering = false
        updateDiscoveredWifiDevices(devices)
        if (connectedPeerCount() == 0 && !isConnecting) {
            connectToFirstAvailablePeer(devices)
        }
    }

    fun connectToDevice(deviceAddress: String, isHigherPriority: Boolean = true) {
        if (connectedPeerCount() > 0 || isConnecting) return
        isConnecting = true
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = android.net.wifi.WpsInfo.PBC
            groupOwnerIntent = if (isHigherPriority) 10 else 3
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct connect requested for $deviceAddress")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "connect() failed: $reason")
                isConnecting = false
            }
        })
    }

    fun connectToFirstAvailablePeer(devices: Collection<WifiP2pDevice>) {
        if (connectedPeerCount() > 0 || isConnecting) return
        val target = devices.firstOrNull { it.status == WifiP2pDevice.AVAILABLE } ?: return
        Log.i(TAG, "Auto-connecting to Wi-Fi Direct peer: ${target.deviceName} (${target.deviceAddress})")
        connectToDevice(target.deviceAddress)
    }

    fun onGroupDismantled() {
        Log.i(TAG, "Wi-Fi Direct group dismantled / disconnected")
        isConnecting = false
        peerSockets.forEach { runCatching { it.close() } }
        peerSockets.clear()
        socketToPeerInfo.clear()
        notifyPeerCounts()
        if (running) {
            discoverPeers()
        }
    }

    companion object {
        private const val TAG = "WifiDirectTransport"
        private const val PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_MESSAGE_BYTES = 8 * 1024

        private const val SERVICE_INSTANCE = "_voicerelay"
        private const val SERVICE_TYPE = "_presence._tcp"

        private const val FRAME_HANDSHAKE: Byte = 0x01
        private const val FRAME_MESSAGE: Byte = 0x02
    }
}
