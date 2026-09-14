package com.offlinevoicerelay.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val localDeviceId: String = "dev-" + UUID.randomUUID().toString().take(6),
    private val localDisplayName: String = Build.MODEL
) : Transport {

    override val name: String = "BLE"

    private val running = AtomicBoolean(false)

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverTxChar: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val executor = Executors.newCachedThreadPool()
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()
    private var scanDutyCycleJob: ScheduledFuture<*>? = null

    private var onBytesReceived: (ByteArray) -> Unit = {}
    private var onPeerCountChanged: (Int) -> Unit = {}
    private var onPeerListChanged: (List<PeerInfo>) -> Unit = {}

    // Central role connections
    private val pendingClientConnections = ConcurrentHashMap.newKeySet<String>()
    private val connectedClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientMtuMap = ConcurrentHashMap<String, Int>()
    private val clientTxSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val clientBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // Peripheral role connections
    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverMtuMap = ConcurrentHashMap<String, Int>()
    private val serverTxSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val serverBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    // Peer Identity Mapping (MAC Address -> PeerInfo)
    private val addressToPeerInfo = ConcurrentHashMap<String, PeerInfo>()
    private val discoveredBleDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    private val clientWriteLocks = ConcurrentHashMap<String, Any>()
    private val serverWriteLocks = ConcurrentHashMap<String, Any>()

    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        val connectedMacs = (connectedClients.keys + connectedServerDevices.keys).toSet()
        val connectedDevIds = addressToPeerInfo.values.map { it.deviceId }.toSet()
        return discoveredBleDevices.values.map { dev ->
            val isConn = connectedMacs.contains(dev.addressOrId) ||
                         addressToPeerInfo.containsKey(dev.addressOrId) ||
                         connectedDevIds.contains(dev.addressOrId)
            val peerName = addressToPeerInfo[dev.addressOrId]?.displayName ?: dev.name
            dev.copy(isConnected = isConn, name = peerName)
        }
    }

    fun connectToDevice(address: String) {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        if (!connectedClients.containsKey(address) && !connectedServerDevices.containsKey(address)) {
            connectGattClient(device)
        }
    }

    override fun setOnBytesReceivedListener(listener: (ByteArray) -> Unit) {
        onBytesReceived = listener
    }

    override fun setOnPeerCountChangedListener(listener: (Int) -> Unit) {
        onPeerCountChanged = listener
    }

    override fun setOnPeerListChangedListener(listener: (List<PeerInfo>) -> Unit) {
        onPeerListChanged = listener
    }

    override fun getConnectedPeerList(): List<PeerInfo> {
        val allAddresses = (connectedClients.keys + connectedServerDevices.keys).toSet()
        return allAddresses.mapNotNull { addressToPeerInfo[it] }
            .filter { it.deviceId.isNotBlank() && it.deviceId != localDeviceId }
            .distinctBy { it.deviceId }
    }

    override fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BluetoothAdapter disabled or null — cannot start BleTransport")
            running.set(false)
            return
        }

        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "BleTransport already running")
            return
        }

        Log.i(TAG, "Starting BleTransport dual-role GATT service...")
        runCatching {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            setupGattServer()
            startAdvertising()
            startScanning()
            startScanDutyCycle()
        }.onFailure {
            Log.e(TAG, "Failed to start BleTransport: ${it.message}", it)
            stop()
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return

        Log.i(TAG, "Stopping BleTransport...")

        scanDutyCycleJob?.cancel(true)
        scanDutyCycleJob = null

        advertiseCallback?.let { cb ->
            runCatching { advertiser?.stopAdvertising(cb) }
        }
        advertiseCallback = null
        advertiser = null

        scanCallback?.let { cb ->
            runCatching { scanner?.stopScan(cb) }
        }
        scanCallback = null
        scanner = null

        pendingClientConnections.clear()

        connectedClients.values.forEach { gatt ->
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
        connectedClients.clear()
        clientMtuMap.clear()
        clientTxSemaphores.clear()
        clientBuffers.clear()

        runCatching { gattServer?.close() }
        gattServer = null
        serverTxChar = null
        connectedServerDevices.clear()
        serverMtuMap.clear()
        serverTxSemaphores.clear()
        serverBuffers.clear()
        addressToPeerInfo.clear()

        notifyPeerCounts()
    }

    override fun broadcast(bytes: ByteArray) {
        if (!running.get() || bytes.isEmpty()) return
        val framedBytes = prepareFramedBytes(FRAME_MESSAGE, bytes)
        val sentDeviceIds = mutableSetOf<String>()

        // Send via client connections first
        for ((address, gatt) in connectedClients) {
            val peerId = addressToPeerInfo[address]?.deviceId
            if (peerId == null || sentDeviceIds.add(peerId)) {
                executor.submit { sendFramedBytesToClient(address, gatt, framedBytes) }
            }
        }

        // Send via server connections for peers not already reached via client
        for ((address, device) in connectedServerDevices) {
            val peerId = addressToPeerInfo[address]?.deviceId
            if (peerId == null || sentDeviceIds.add(peerId)) {
                executor.submit { sendFramedBytesToServer(address, device, framedBytes) }
            }
        }
    }

    override fun sendToPeer(peerId: String, bytes: ByteArray): Boolean {
        if (!running.get() || bytes.isEmpty()) return false
        val framedBytes = prepareFramedBytes(FRAME_MESSAGE, bytes)

        // Find target address for peerId
        val targetAddress = addressToPeerInfo.entries.firstOrNull { it.value.deviceId == peerId }?.key
            ?: return false

        val gatt = connectedClients[targetAddress]
        if (gatt != null) {
            executor.submit { sendFramedBytesToClient(targetAddress, gatt, framedBytes) }
            return true
        }

        val device = connectedServerDevices[targetAddress]
        if (device != null) {
            executor.submit { sendFramedBytesToServer(targetAddress, device, framedBytes) }
            return true
        }

        return false
    }

    override fun connectedPeerCount(): Int {
        return getConnectedPeerList().size
    }

    private fun notifyPeerCounts() {
        val count = connectedPeerCount()
        val peers = getConnectedPeerList()
        onPeerCountChanged(count)
        onPeerListChanged(peers)
    }

    private fun sendHandshake(address: String, isClient: Boolean) {
        executor.submit {
            val json = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("deviceId", localDeviceId)
                put("name", localDisplayName)
            }.toString()

            val handshakeBytes = json.toByteArray(Charsets.UTF_8)
            val framedBytes = prepareFramedBytes(FRAME_HANDSHAKE, handshakeBytes)

            if (isClient) {
                connectedClients[address]?.let { sendFramedBytesToClient(address, it, framedBytes) }
            } else {
                connectedServerDevices[address]?.let { sendFramedBytesToServer(address, it, framedBytes) }
            }
        }
    }

    private fun prepareFramedBytes(frameType: Byte, payload: ByteArray): ByteArray {
        val totalLength = 1 + payload.size
        return ByteBuffer.allocate(6 + totalLength)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC_0)
            .put(MAGIC_1)
            .putInt(totalLength)
            .put(frameType)
            .put(payload)
            .array()
    }

    // ---- GATT Server (Peripheral Role) ----

    private fun setupGattServer() {
        val manager = bluetoothManager ?: return
        val gattServerCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedServerDevices[device.address] = device
                    serverMtuMap[device.address] = 23
                    serverTxSemaphores[device.address] = Semaphore(1)
                    notifyPeerCounts()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedServerDevices.remove(device.address)
                    serverMtuMap.remove(device.address)
                    serverTxSemaphores.remove(device.address)
                    serverBuffers.remove(device.address)
                    addressToPeerInfo.remove(device.address)
                    serverWriteLocks.remove(device.address)
                    notifyPeerCounts()
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                serverMtuMap[device.address] = mtu
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                if (characteristic.uuid == RX_CHAR_UUID && value != null) {
                    handleIncomingChunk(device.address, value, serverBuffers)
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                serverTxSemaphores[device.address]?.release()
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (descriptor.uuid == CCCD_UUID) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }
        }

        val server = manager.openGattServer(context, gattServerCallback) ?: throw IllegalStateException("openGattServer returned null")
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        server.addService(service)
        this.serverTxChar = txChar
        gattServer = server
    }

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        val callback = object : AdvertiseCallback() {}
        advertiseCallback = callback
        adv.startAdvertising(settings, data, callback)
    }

    // ---- GATT Client (Central Role) ----

    private fun startScanning() {
        val sc = adapter?.bluetoothLeScanner ?: return
        scanner = sc

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address
                val dName = result.scanRecord?.deviceName ?: device.name ?: "BLE Node (${address.take(5)})"
                val isConnected = connectedClients.containsKey(address) || connectedServerDevices.containsKey(address)

                discoveredBleDevices[address] = DiscoveredDevice(
                    addressOrId = address,
                    name = dName,
                    transport = "Bluetooth",
                    isConnected = isConnected,
                    rssi = result.rssi
                )

                if (connectedClients.containsKey(address) ||
                    !pendingClientConnections.add(address)) {
                    return
                }

                connectGattClient(device)
            }
        }

        scanCallback = callback
        sc.startScan(listOf(filter), settings, callback)
    }

    private fun startScanDutyCycle() {
        scanDutyCycleJob = scheduledExecutor.scheduleWithFixedDelay({
            if (running.get() && scanner != null && scanCallback != null) {
                runCatching {
                    scanner?.stopScan(scanCallback)
                    Thread.sleep(200)
                    val filter = ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(SERVICE_UUID))
                        .build()
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    scanner?.startScan(listOf(filter), settings, scanCallback)
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun connectGattClient(device: BluetoothDevice) {
        val clientCallback = object : BluetoothGattCallback() {
            private val handshakeSent = AtomicBoolean(false)

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val address = gatt.device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    pendingClientConnections.remove(address)
                    connectedClients[address] = gatt
                    clientMtuMap[address] = 23
                    clientTxSemaphores[address] = Semaphore(1)
                    notifyPeerCounts()
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    pendingClientConnections.remove(address)
                    connectedClients.remove(address)
                    clientMtuMap.remove(address)
                    clientTxSemaphores.remove(address)
                    clientBuffers.remove(address)
                    addressToPeerInfo.remove(address)
                    clientWriteLocks.remove(address)
                    gatt.close()
                    notifyPeerCounts()
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (handshakeSent.compareAndSet(false, true)) {
                    sendHandshake(gatt.device.address, isClient = true)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val address = gatt.device.address
                if (status != BluetoothGatt.GATT_SUCCESS) return

                val service = gatt.getService(SERVICE_UUID) ?: return
                val txChar = service.getCharacteristic(TX_CHAR_UUID) ?: return

                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }

                executor.submit {
                    Thread.sleep(100)
                    gatt.requestMtu(517)
                    Thread.sleep(250)
                    if (handshakeSent.compareAndSet(false, true)) {
                        sendHandshake(address, isClient = true)
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                clientMtuMap[gatt.device.address] = mtu
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                clientTxSemaphores[gatt.device.address]?.release()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == TX_CHAR_UUID) {
                    val value = characteristic.value ?: return
                    handleIncomingChunk(gatt.device.address, value, clientBuffers)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == TX_CHAR_UUID) {
                    handleIncomingChunk(gatt.device.address, value, clientBuffers)
                }
            }
        }

        device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ---- Chunk Transmission & Framing ----

    private fun sendFramedBytesToClient(address: String, gatt: BluetoothGatt, framedBytes: ByteArray) {
        val semaphore = clientTxSemaphores[address] ?: return
        val mtu = clientMtuMap[address] ?: 23
        val maxChunkSize = maxOf(20, mtu - 3)

        val service = gatt.getService(SERVICE_UUID) ?: return
        val rxChar = service.getCharacteristic(RX_CHAR_UUID) ?: return

        val lock = clientWriteLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            var offset = 0
            while (offset < framedBytes.size && running.get()) {
                val length = minOf(maxChunkSize, framedBytes.size - offset)
                val chunk = framedBytes.copyOfRange(offset, offset + length)

                semaphore.drainPermits()
                val success = writeCharacteristicCompat(gatt, rxChar, chunk)
                if (!success) {
                    Thread.sleep(40)
                    writeCharacteristicCompat(gatt, rxChar, chunk)
                }

                val acquired = runCatching { semaphore.tryAcquire(250, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!acquired) {
                    Thread.sleep(25)
                }
                offset += length
            }
        }
    }

    private fun sendFramedBytesToServer(address: String, device: BluetoothDevice, framedBytes: ByteArray) {
        val server = gattServer ?: return
        val txChar = serverTxChar ?: return
        val semaphore = serverTxSemaphores[address] ?: return
        val mtu = serverMtuMap[address] ?: 23
        val maxChunkSize = maxOf(20, mtu - 3)

        val lock = serverWriteLocks.getOrPut(address) { Any() }
        synchronized(lock) {
            var offset = 0
            while (offset < framedBytes.size && running.get()) {
                val length = minOf(maxChunkSize, framedBytes.size - offset)
                val chunk = framedBytes.copyOfRange(offset, offset + length)

                semaphore.drainPermits()
                val success = notifyCharacteristicChangedCompat(server, device, txChar, chunk)
                if (!success) {
                    Thread.sleep(40)
                }

                val acquired = runCatching { semaphore.tryAcquire(250, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!acquired) {
                    Thread.sleep(25)
                }
                offset += length
            }
        }
    }

    private fun handleIncomingChunk(peerAddress: String, chunk: ByteArray, bufferMap: ConcurrentHashMap<String, ByteArrayOutputStream>) {
        val buffer = bufferMap.getOrPut(peerAddress) { ByteArrayOutputStream() }
        synchronized(buffer) {
            buffer.write(chunk)
            val bytes = buffer.toByteArray()
            var offset = 0
            while (bytes.size - offset >= 6) {
                if (bytes[offset] != MAGIC_0 || bytes[offset + 1] != MAGIC_1) {
                    offset++
                    continue
                }
                val len = ByteBuffer.wrap(bytes, offset + 2, 4).order(ByteOrder.BIG_ENDIAN).int
                if (len <= 0 || len > MAX_MESSAGE_BYTES) {
                    offset += 2
                    continue
                }
                if (bytes.size - offset - 6 >= len) {
                    val framedData = ByteArray(len)
                    System.arraycopy(bytes, offset + 6, framedData, 0, len)
                    offset += 6 + len
                    processFramedPayload(peerAddress, framedData)
                } else {
                    break
                }
            }
            if (offset > 0) {
                val remaining = bytes.copyOfRange(offset, bytes.size)
                buffer.reset()
                buffer.write(remaining)
            }
        }
    }

    private fun processFramedPayload(peerAddress: String, framedData: ByteArray) {
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
                        val prev = addressToPeerInfo.put(peerAddress, peerInfo)
                        Log.i(TAG, "Registered peer handshake from $peerAddress: $peerInfo")
                        notifyPeerCounts()

                        // If we are GATT Server and this is a new handshake from Client, reply back with Server handshake!
                        if (connectedServerDevices.containsKey(peerAddress)) {
                            sendHandshake(peerAddress, isClient = false)
                        } else if (connectedClients.containsKey(peerAddress) && prev == null) {
                            sendHandshake(peerAddress, isClient = true)
                        }
                    }
                }.onFailure { Log.w(TAG, "Malformed handshake from $peerAddress: ${it.message}") }
            }
            FRAME_MESSAGE -> {
                runCatching { onBytesReceived(payload) }
                    .onFailure { Log.e(TAG, "Error handling message payload: ${it.message}") }
            }
            else -> Log.w(TAG, "Unknown frame type $frameType from $peerAddress")
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyCharacteristicChangedCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = value
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    companion object {
        private const val TAG = "BleTransport"
        private const val MAX_MESSAGE_BYTES = 8 * 1024

        private const val MAGIC_0: Byte = 0xBE.toByte()
        private const val MAGIC_1: Byte = 0xEF.toByte()

        private const val FRAME_HANDSHAKE: Byte = 0x01
        private const val FRAME_MESSAGE: Byte = 0x02

        private val SERVICE_UUID: UUID = UUID.fromString("7a5e9b0e-7e1a-4b6a-9c2f-2f6b6a5b8e10")
        private val RX_CHAR_UUID: UUID = UUID.fromString("7a5e9b0e-7e1a-4b6a-9c2f-2f6b6a5b8e11")
        private val TX_CHAR_UUID: UUID = UUID.fromString("7a5e9b0e-7e1a-4b6a-9c2f-2f6b6a5b8e12")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
