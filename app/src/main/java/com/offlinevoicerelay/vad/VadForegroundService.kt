package com.offlinevoicerelay.vad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.offlinevoicerelay.R
import com.offlinevoicerelay.ui.ChatActivity
import com.offlinevoicerelay.ui.MainActivity
import com.offlinevoicerelay.mesh.BleTransport
import com.offlinevoicerelay.mesh.MeshRouter
import com.offlinevoicerelay.mesh.PeerInfo
import com.offlinevoicerelay.mesh.TransmissionManager
import com.offlinevoicerelay.mesh.WifiDirectBroadcastReceiver
import com.offlinevoicerelay.mesh.WifiDirectTransport
import com.offlinevoicerelay.model.Language
import com.offlinevoicerelay.model.Message
import com.offlinevoicerelay.model.MessageEntry
import com.offlinevoicerelay.stt.HybridSttEngine
import com.offlinevoicerelay.stt.SttEngine
import com.offlinevoicerelay.stt.SttResult
import com.offlinevoicerelay.stt.TfLiteSttEngine
import com.offlinevoicerelay.stt.VoskSttEngine
import com.offlinevoicerelay.translate.OfflineTranslationEngine
import com.offlinevoicerelay.translate.TranslationEngine
import com.offlinevoicerelay.tts.AlertAudioPlayer
import com.offlinevoicerelay.tts.AndroidSystemTtsEngine
import com.offlinevoicerelay.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class RelayStatus(
    val isListening: Boolean = false,
    val pushToTalkActive: Boolean = false,
    val currentLanguage: Language = Language.HINDI,
    val alertModeArmed: Boolean = false,
    val wifiPeerCount: Int = 0,
    val bluetoothPeerCount: Int = 0,
    val connectedPeers: List<PeerInfo> = emptyList(),
    val targetPeerId: String? = null,
    val audioAmplitude: Float = 0f,
    val lastEvent: String = "",
    val messageHistory: List<MessageEntry> = emptyList()
)

/**
 * Owns the full pipeline for one device: idle VAD listening,
 * transmission (via [TransmissionManager]), and playback of
 * incoming messages, plus broadcast and 1-to-1 targeted talk modes.
 */
class VadForegroundService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): VadForegroundService = this@VadForegroundService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status

    private lateinit var vadEngine: VadEngine
    private lateinit var vadStateMachine: VadStateMachine
    private lateinit var sttEngine: SttEngine
    private lateinit var ttsEngine: TtsEngine
    private lateinit var translationEngine: TranslationEngine
    private lateinit var alertAudioPlayer: AlertAudioPlayer
    private lateinit var transmissionManager: TransmissionManager

    private var audioRecord: AudioRecord? = null
    private var wifiDirectReceiver: WifiDirectBroadcastReceiver? = null
    private var testReceiver: android.content.BroadcastReceiver? = null

    private var wakeLock: PowerManager.WakeLock? = null

    val deviceId: String by lazy {
        applicationContext.getSharedPreferences("relay_prefs", MODE_PRIVATE)
            .let { prefs ->
                prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString("device_id", it).apply()
                }
            }
    }

    private val utteranceMutex = Mutex()
    private val utteranceBuffer = mutableListOf<Short>()
    private var pushToTalkHeld = false
    private var activeChatPeerId: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification("Mesh Active"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Mesh Active"))
        }

        vadEngine = VadEngineFactory.create(applicationContext)
        vadStateMachine = VadStateMachine(
            onSpeechStart = { updateStatus { it.copy(lastEvent = "Speech detected") } },
            onSpeechEnd = { onUtteranceFinalized() }
        )

        sttEngine = HybridSttEngine(applicationContext)
        ttsEngine = AndroidSystemTtsEngine(applicationContext)
        translationEngine = OfflineTranslationEngine(applicationContext)
        alertAudioPlayer = AlertAudioPlayer(applicationContext)

        val meshRouter = MeshRouter(localDeviceId = deviceId)
        val deviceName = Build.MODEL + "-" + deviceId.take(4)

        val wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val wifiChannel = wifiP2pManager.initialize(this, mainLooper, null)
        val wifiTransport = WifiDirectTransport(
            applicationContext, wifiP2pManager, wifiChannel,
            localDeviceId = deviceId, localDisplayName = deviceName
        )
        val receiver = WifiDirectBroadcastReceiver(wifiP2pManager, wifiChannel, wifiTransport)
        registerReceiver(receiver, receiver.intentFilter)
        wifiDirectReceiver = receiver

        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bluetoothTransport = BleTransport(
            applicationContext, btAdapter,
            localDeviceId = deviceId, localDisplayName = deviceName
        )

        transmissionManager = TransmissionManager(
            wifiDirect = wifiTransport,
            bluetooth = bluetoothTransport,
            meshRouter = meshRouter,
            scope = serviceScope
        )
        transmissionManager.setOnMessageForPlaybackListener { onIncomingMessage(it) }
        transmissionManager.setOnPeerCountChangedListener { wifi, bt ->
            val peers = transmissionManager.connectedPeers.value
            updateStatus { it.copy(wifiPeerCount = wifi, bluetoothPeerCount = bt, connectedPeers = peers) }
        }

        serviceScope.launch {
            transmissionManager.connectedPeers.collect { peers ->
                val wifiCount = transmissionManager.getConnectedWifiPeers().size
                val btCount = transmissionManager.getConnectedBtPeers().size
                updateStatus {
                    it.copy(
                        connectedPeers = peers,
                        wifiPeerCount = wifiCount,
                        bluetoothPeerCount = btCount
                    )
                }
            }
        }

        transmissionManager.start()

        seedInitialWelcomeMessageIfFirstRun()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OfflineVoiceRelay::MicCaptureLock"
        ).also { it.acquire() }

        val tRx = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "com.offlinevoicerelay.TEST_SEND") {
                    val text = intent.getStringExtra("text") ?: "Test BLE message"
                    val target = intent.getStringExtra("targetPeerId")
                    val msg = Message.newOriginated(
                        text = text,
                        lang = _status.value.currentLanguage,
                        isAlert = _status.value.alertModeArmed,
                        originId = deviceId,
                        targetPeerId = target
                    )
                    val entry = MessageEntry(
                        text = text,
                        langCode = _status.value.currentLanguage.code,
                        isAlert = _status.value.alertModeArmed,
                        isSent = true,
                        targetPeerId = target,
                        originPeerId = deviceId
                    )
                    if (target != null) {
                        val sent = transmissionManager.sendToPeer(target, msg)
                        if (!sent) {
                            updateStatus { it.copy(lastEvent = "Peer not directly connected") }
                        } else {
                            updateStatus { it.copy(
                                lastEvent = "Sent 1-to-1 to $target: \"$text\"",
                                messageHistory = (listOf(entry) + it.messageHistory).take(50)
                            ) }
                        }
                    } else {
                        transmissionManager.sendOriginated(msg)
                        updateStatus { it.copy(
                            lastEvent = "Sent: \"$text\"",
                            messageHistory = (listOf(entry) + it.messageHistory).take(50)
                        ) }
                    }
                    Log.i(TAG, "Test send triggered: \"$text\" (target=$target)")
                } else if (intent?.action == "com.offlinevoicerelay.TEST_RECEIVE") {
                    val text = intent.getStringExtra("text") ?: "Test incoming message"
                    val isAlert = intent.getBooleanExtra("isAlert", false)
                    val origin = intent.getStringExtra("origin") ?: "V2575-b559"
                    val target = intent.getStringExtra("target")
                    val msg = Message.newOriginated(
                        text = text,
                        lang = _status.value.currentLanguage,
                        isAlert = isAlert,
                        originId = origin,
                        targetPeerId = target
                    )
                    onIncomingMessage(msg)
                }
            }
        }
        val tFilter = android.content.IntentFilter().apply {
            addAction("com.offlinevoicerelay.TEST_SEND")
            addAction("com.offlinevoicerelay.TEST_RECEIVE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tRx, tFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(tRx, tFilter)
        }
        testReceiver = tRx

        startIdleListening()
    }

    override fun onDestroy() {
        captureJob?.cancel()
        audioRecord?.release()
        transmissionManager.stop()
        wifiDirectReceiver?.let { runCatching { unregisterReceiver(it) } }
        testReceiver?.let { runCatching { unregisterReceiver(it) } }
        (sttEngine as? HybridSttEngine)?.releaseAll()
        (sttEngine as? VoskSttEngine)?.releaseAll()
        (sttEngine as? TfLiteSttEngine)?.releaseAll()
        (ttsEngine as? AndroidSystemTtsEngine)?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        serviceScope.cancel()
        super.onDestroy()
    }

    fun setLanguage(language: Language) {
        updateStatus { it.copy(currentLanguage = language) }
        sttEngine.preload(language)
        ttsEngine.preload(language)
    }

    fun setAlertModeArmed(armed: Boolean) {
        updateStatus { it.copy(alertModeArmed = armed) }
    }

    fun setTargetPeerId(targetId: String?) {
        updateStatus { it.copy(targetPeerId = targetId) }
    }

    private val chatAutoSpeakerMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun setChatAutoSpeakerEnabled(peerId: String, enabled: Boolean) {
        chatAutoSpeakerMap[peerId] = enabled
    }

    fun isChatAutoSpeakerEnabled(peerId: String): Boolean {
        return chatAutoSpeakerMap[peerId] ?: false
    }

    fun setActiveChatPeerId(peerId: String?) {
        activeChatPeerId = peerId
    }

    fun clearBroadcastHistory() {
        updateStatus { current ->
            current.copy(messageHistory = current.messageHistory.filter { !it.isBroadcast })
        }
    }

    fun clearChatHistory(peerId: String) {
        updateStatus { current ->
            current.copy(messageHistory = current.messageHistory.filter { entry ->
                entry.isBroadcast || (entry.targetPeerId != peerId && entry.originPeerId != peerId)
            })
        }
    }

    private fun seedInitialWelcomeMessageIfFirstRun() {
        val prefs = getSharedPreferences("varta_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("welcome_msg_v2_seeded", false)) {
            prefs.edit().putBoolean("welcome_msg_v2_seeded", true).apply()
            val welcome = MessageEntry(
                id = "welcome_broadcast_0",
                text = "Welcome to Varta",
                langCode = _status.value.currentLanguage.code,
                isAlert = false,
                isSent = false,
                targetPeerId = null,
                originPeerId = "Varta System",
                isBroadcast = true,
                timestampMs = System.currentTimeMillis()
            )
            updateStatus { current ->
                val filtered = current.messageHistory.filter { it.id != "welcome_broadcast_0" }
                current.copy(messageHistory = listOf(welcome) + filtered)
            }
        }
    }

    fun onPushToTalkDown(targetPeerId: String? = null) {
        pushToTalkHeld = true
        updateStatus { it.copy(targetPeerId = targetPeerId) }
        serviceScope.launch {
            utteranceMutex.withLock { utteranceBuffer.clear() }
        }
        updateStatus { it.copy(pushToTalkActive = true, lastEvent = "Push-to-talk: speaking") }
        vadStateMachine.reset()
    }

    fun onPushToTalkUp() {
        pushToTalkHeld = false
        updateStatus { it.copy(pushToTalkActive = false) }
        onUtteranceFinalized()
    }

    fun speakText(text: String, language: Language = _status.value.currentLanguage) {
        serviceScope.launch {
            val synth = ttsEngine.synthesize(text, language)
            if (synth != null) {
                alertAudioPlayer.play(synth, isAlert = false)
            } else {
                updateStatus { it.copy(lastEvent = "TTS unavailable for ${language.displayName}") }
            }
        }
    }

    fun sendTextMessage(targetPeerId: String?, text: String) {
        if (text.isBlank()) return
        serviceScope.launch {
            val language = _status.value.currentLanguage
            val isAlert = _status.value.alertModeArmed
            val message = Message.newOriginated(
                text = text.trim(),
                lang = language,
                isAlert = isAlert,
                originId = deviceId,
                targetPeerId = targetPeerId
            )
            val entry = MessageEntry(
                text = text.trim(),
                langCode = language.code,
                isAlert = isAlert,
                isSent = true,
                targetPeerId = targetPeerId,
                originPeerId = deviceId
            )
            if (targetPeerId != null) {
                val sent = transmissionManager.sendToPeer(targetPeerId, message)
                if (sent) {
                    updateStatus { it.copy(
                        lastEvent = "Sent 1-to-1: \"${text.trim()}\"",
                        messageHistory = (listOf(entry) + it.messageHistory).take(50)
                    ) }
                } else {
                    updateStatus { it.copy(lastEvent = "Peer not directly connected") }
                }
            } else {
                transmissionManager.sendOriginated(message)
                updateStatus { it.copy(
                    lastEvent = "Sent: \"${text.trim()}\"",
                    messageHistory = (listOf(entry) + it.messageHistory).take(50)
                ) }
            }
        }
    }

    fun getDiscoveredDevices(): List<com.offlinevoicerelay.mesh.DiscoveredDevice> {
        return transmissionManager.getDiscoveredDevices()
    }

    fun getConnectedBtPeers(): List<PeerInfo> {
        return transmissionManager.getConnectedBtPeers()
    }

    fun getConnectedWifiPeers(): List<PeerInfo> {
        return transmissionManager.getConnectedWifiPeers()
    }

    fun connectToDevice(device: com.offlinevoicerelay.mesh.DiscoveredDevice) {
        transmissionManager.connectToDevice(device)
    }

    fun triggerScan() {
        transmissionManager.triggerScan()
    }

    private fun startIdleListening() {
        updateStatus { it.copy(isListening = true) }
        val minBuf = AudioRecord.getMinBufferSize(
            VadEngine.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            Log.e(TAG, "AudioRecord.getMinBufferSize() returned error $minBuf")
            updateStatus { it.copy(lastEvent = "Microphone unavailable") }
            return
        }
        val bufferSize = maxOf(minBuf, VadEngine.FRAME_SIZE_SAMPLES * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VadEngine.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            updateStatus { it.copy(lastEvent = "Microphone initialization failed") }
            return
        }

        audioRecord = record
        record.startRecording()

        captureJob = serviceScope.launch {
            val frame = ShortArray(VadEngine.FRAME_SIZE_SAMPLES)
            var frameCounter = 0
            while (true) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue

                if (pushToTalkHeld) {
                    frameCounter++
                    if (frameCounter % 2 == 0) {
                        var sumSq = 0.0
                        for (i in 0 until read) {
                            val s = frame[i] / 32768.0
                            sumSq += s * s
                        }
                        val rms = Math.sqrt(sumSq / read).toFloat()
                        val normAmp = (rms * 4.5f).coerceIn(0f, 1f)
                        updateStatus { it.copy(audioAmplitude = normAmp) }
                    }

                    utteranceMutex.withLock {
                        for (i in 0 until read) {
                            utteranceBuffer.add(frame[i])
                        }
                    }
                } else {
                    if (_status.value.audioAmplitude > 0f) {
                        updateStatus { it.copy(audioAmplitude = 0f) }
                    }
                }
            }
        }
    }

    private fun onUtteranceFinalized() {
        serviceScope.launch {
            val pcm: ShortArray = utteranceMutex.withLock {
                if (utteranceBuffer.isEmpty()) return@launch
                utteranceBuffer.toShortArray().also { utteranceBuffer.clear() }
            }

            val language = _status.value.currentLanguage
            val targetId = _status.value.targetPeerId

            when (val result = sttEngine.transcribe(pcm, language)) {
                is SttResult.Final -> {
                    if (result.text.isBlank()) {
                        updateStatus { it.copy(lastEvent = "STT produced empty text") }
                        return@launch
                    }
                    val message = Message.newOriginated(
                        text = result.text,
                        lang = language,
                        isAlert = _status.value.alertModeArmed,
                        originId = deviceId,
                        targetPeerId = targetId
                    )
                    val entry = MessageEntry(
                        text = result.text,
                        langCode = language.code,
                        isAlert = _status.value.alertModeArmed,
                        isSent = true,
                        targetPeerId = targetId,
                        originPeerId = deviceId
                    )
                    if (targetId != null) {
                        val sent = transmissionManager.sendToPeer(targetId, message)
                        if (sent) {
                            updateStatus { it.copy(
                                lastEvent = "Sent 1-to-1: \"${result.text}\"",
                                messageHistory = (listOf(entry) + it.messageHistory).take(50)
                            ) }
                        } else {
                            updateStatus { it.copy(lastEvent = "Peer not directly connected") }
                        }
                    } else {
                        transmissionManager.sendOriginated(message)
                        updateStatus { it.copy(
                            lastEvent = "Sent: \"${result.text}\"",
                            messageHistory = (listOf(entry) + it.messageHistory).take(50)
                        ) }
                    }
                }
                is SttResult.Error -> updateStatus { it.copy(lastEvent = "STT error: ${result.message}") }
                is SttResult.Partial -> Unit
            }
        }
    }

    private fun onIncomingMessage(message: Message) {
        serviceScope.launch {
            val localLanguage = _status.value.currentLanguage
            val sourceLanguage = runCatching { Language.fromCode(message.lang) }.getOrDefault(Language.HINDI)

            // Translate incoming text to receiver's language if source and destination languages differ
            val deliveredText = if (sourceLanguage != localLanguage) {
                translationEngine.translate(message.text, from = sourceLanguage, to = localLanguage)
            } else {
                message.text
            }

            val prefix = if (message.targetPeerId != null) "Received 1-to-1" else "Received"
            val langTag = if (sourceLanguage != localLanguage) {
                "${sourceLanguage.code.uppercase()}➔${localLanguage.code.uppercase()}"
            } else {
                localLanguage.code.uppercase()
            }
            val receivedEntry = MessageEntry(
                text = deliveredText,
                langCode = langTag,
                isAlert = message.isAlert,
                isSent = false,
                targetPeerId = message.targetPeerId,
                originPeerId = message.originId
            )
            updateStatus { it.copy(
                lastEvent = "$prefix ($langTag): \"$deliveredText\"",
                messageHistory = (listOf(receivedEntry) + it.messageHistory).take(50)
            ) }

            val isInActiveChatWithSender = (message.targetPeerId != null && activeChatPeerId == message.originId)
            if (!isInActiveChatWithSender || message.isAlert) {
                showIncomingMessageNotification(message, deliveredText)
            }

            val isDirectChat = (message.targetPeerId != null)
            val shouldAutoSpeak = message.isAlert || (!isDirectChat) || (isDirectChat && (chatAutoSpeakerMap[message.originId] == true))

            if (shouldAutoSpeak) {
                // Synthesize voice output in the recipient's chosen language using the translated message through main loudspeaker
                val synth = ttsEngine.synthesize(deliveredText, localLanguage)
                if (synth != null) {
                    alertAudioPlayer.play(synth, isAlert = message.isAlert)
                } else {
                    updateStatus { it.copy(lastEvent = "TTS unavailable for ${localLanguage.displayName}") }
                }
            }
        }
    }

    private fun showIncomingMessageNotification(message: Message, deliveredText: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val isDirectChat = (message.targetPeerId != null)

        val (title, pendingIntent, notifId) = if (isDirectChat) {
            val peerInfo = _status.value.connectedPeers.firstOrNull { it.deviceId == message.originId }
            val senderName = peerInfo?.displayName ?: "Peer-${message.originId.take(4)}"
            val intent = Intent(this, ChatActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ChatActivity.EXTRA_PEER_ID, message.originId)
                putExtra(ChatActivity.EXTRA_PEER_NAME, senderName)
            }
            val pi = PendingIntent.getActivity(
                this,
                (message.originId.hashCode() and 0x7FFFFFFF),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            Triple(senderName, pi, (message.originId.hashCode() and 0x7FFFFFFF))
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val nId = NOTIFICATION_ID_BROADCAST_BASE + ((message.msgId.hashCode() and 0x7FFFFFFF) % 100)
            Triple("Broadcast Mesh Message", pi, nId)
        }

        val channelId = if (message.isAlert) CHANNEL_ALERT else CHANNEL_MESSAGES
        val notifTitle = if (message.isAlert) "🚨 EMERGENCY ALERT: $title" else title
        val notifIcon = if (message.isAlert) R.drawable.ic_notification_alert else R.drawable.ic_notification
        val notifColor = if (message.isAlert) 0xFFEF4444.toInt() else 0xFF0D1117.toInt()
        val appIcon = getAppIconBitmap()

        val personIcon = appIcon?.let { IconCompat.createWithBitmap(it) }
        val senderPerson = Person.Builder()
            .setName(title)
            .apply {
                if (personIcon != null) {
                    setIcon(personIcon)
                }
            }
            .setImportant(true)
            .build()

        val userPerson = Person.Builder().setName("Me").build()
        val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    deliveredText,
                    System.currentTimeMillis(),
                    senderPerson
                )
            )
            .setConversationTitle(notifTitle)
            .setGroupConversation(!isDirectChat)

        val notifBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(notifTitle)
            .setContentText(deliveredText)
            .setStyle(if (message.isAlert) {
                NotificationCompat.BigTextStyle()
                    .bigText(deliveredText)
                    .setBigContentTitle(notifTitle)
            } else {
                messagingStyle
            })
            .setSmallIcon(notifIcon)
            .setColor(notifColor)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .addPerson(senderPerson)
            .setCategory(if (message.isAlert) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (message.isAlert) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        if (appIcon != null) {
            notifBuilder.setLargeIcon(appIcon)
        }

        nm.notify(notifId, notifBuilder.build())
    }

    private var cachedAppIcon: Bitmap? = null

    private fun getAppIconBitmap(): Bitmap? {
        if (cachedAppIcon != null && !cachedAppIcon!!.isRecycled) {
            return cachedAppIcon
        }
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher_round)
                ?: android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
                ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.app_logo_varta_round)
                ?: android.graphics.BitmapFactory.decodeResource(resources, R.drawable.app_logo_varta)
            if (bitmap != null) {
                cachedAppIcon = bitmap
                return bitmap
            }
            val drawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher_round)
                ?: ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
                ?: ContextCompat.getDrawable(this, R.drawable.app_logo_varta)
                ?: return null
            val size = (96 * resources.displayMetrics.density).toInt().coerceAtLeast(192)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            cachedAppIcon = bmp
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render app icon bitmap", e)
            null
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.deleteNotificationChannel("relay_service")
            nm.deleteNotificationChannel("relay_messages")
            nm.deleteNotificationChannel("relay_alert")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete legacy notification channels", e)
        }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Varta Mesh Relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Offline mesh network service"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Varta Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming 1-to-1 and mesh broadcast messages"
                enableVibration(true)
                setShowBadge(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Varta Emergency Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical emergency mesh alerts"
                setBypassDnd(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
                setShowBadge(true)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val appIcon = getAppIconBitmap()
        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text)
                    .setBigContentTitle(getString(R.string.app_name))
            )
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setColor(0xFF10B981.toInt())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)

        if (appIcon != null) {
            builder.setLargeIcon(appIcon)
        }

        return builder.build()
    }

    private fun updateStatus(transform: (RelayStatus) -> RelayStatus) {
        _status.value = transform(_status.value)
    }

    companion object {
        private const val TAG = "VadForegroundService"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_ID_BROADCAST_BASE = 2000
        private const val CHANNEL_SERVICE = "varta_mesh_service_v2"
        private const val CHANNEL_MESSAGES = "varta_messages_v2"
        private const val CHANNEL_ALERT = "varta_alerts_v2"
    }
}
