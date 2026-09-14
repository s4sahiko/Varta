package com.offlinevoicerelay.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.offlinevoicerelay.R
import com.offlinevoicerelay.databinding.ActivityChatBinding
import com.offlinevoicerelay.model.MessageEntry
import com.offlinevoicerelay.vad.VadForegroundService
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var service: VadForegroundService? = null
    private var bound = false

    private lateinit var chatAdapter: ChatAdapter
    private var peerId: String = ""
    private var peerName: String = ""
    private var isAutoSpeakerOn: Boolean = false
    private var micPulseAnimator: ObjectAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VadForegroundService.LocalBinder).getService()
            bound = true
            service?.setActiveChatPeerId(peerId)
            service?.setChatAutoSpeakerEnabled(peerId, isAutoSpeakerOn)
            observeMessages()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Direct Peer"

        binding.peerNameText.text = peerName
        binding.peerStatusText.text = "Connected • 1-to-1 Direct Mesh"

        isAutoSpeakerOn = getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
            .getBoolean("auto_speaker_$peerId", true)

        updateSpeakerUI()
        setupRecyclerView()
        setupListeners()

        val serviceIntent = Intent(this, VadForegroundService::class.java)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun updateSpeakerUI() {
        if (isAutoSpeakerOn) {
            binding.autoSpeakerIcon.setImageResource(R.drawable.ic_volume_up)
            binding.autoSpeakerButton.setBackgroundResource(R.drawable.bg_pill_active)
            binding.autoSpeakerIcon.setColorFilter(0xFF10B981.toInt())
        } else {
            binding.autoSpeakerIcon.setImageResource(R.drawable.ic_volume_off)
            binding.autoSpeakerButton.setBackgroundResource(R.drawable.bg_circle_button)
            binding.autoSpeakerIcon.setColorFilter(0xFF6B7280.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        if (bound) {
            service?.setActiveChatPeerId(peerId)
        }
    }

    override fun onPause() {
        if (bound) {
            service?.setActiveChatPeerId(null)
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (bound) {
            service?.setActiveChatPeerId(null)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            onSpeakerClicked = { entry ->
                service?.let { s ->
                    val currentLang = s.status.value.currentLanguage
                    Toast.makeText(this, "Speaking text in ${currentLang.displayName}...", Toast.LENGTH_SHORT).show()
                    s.speakText(entry.text, currentLang)
                }
            }
        )
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatRecyclerView.layoutManager = layoutManager
        binding.chatRecyclerView.adapter = chatAdapter
    }

    private fun setupListeners() {
        binding.chatBackButton.setOnClickListener { finish() }

        binding.clearChatButton.setOnClickListener {
            service?.clearChatHistory(peerId)
            Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show()
        }

        binding.autoSpeakerButton.setOnClickListener {
            isAutoSpeakerOn = !isAutoSpeakerOn
            getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("auto_speaker_$peerId", isAutoSpeakerOn)
                .apply()
            service?.setChatAutoSpeakerEnabled(peerId, isAutoSpeakerOn)
            updateSpeakerUI()
            val msg = if (isAutoSpeakerOn) "Auto-Speaker ON: Incoming messages will be spoken via loudspeaker" else "Auto-Speaker OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.sendTextButton.setOnClickListener {
            val text = binding.messageInput.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                service?.sendTextMessage(targetPeerId = peerId, text = text)
                binding.messageInput.setText("")
            }
        }

        binding.chatMicButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performClick()
                    binding.chatMicIcon.setColorFilter(0xFF10B981.toInt())
                    binding.chatMicButton.setBackgroundResource(R.drawable.bg_chat_mic_active)
                    startMicPulse()
                    service?.onPushToTalkDown(targetPeerId = peerId)
                    Toast.makeText(this, "Hold & speak to $peerName...", Toast.LENGTH_SHORT).show()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.chatMicIcon.setColorFilter(0xFFFFFFFF.toInt())
                    binding.chatMicButton.setBackgroundResource(R.drawable.bg_chat_mic_idle)
                    stopMicPulse()
                    service?.onPushToTalkUp()
                }
            }
            true
        }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            service?.status?.collect { status ->
                // In chat activity, show 1-to-1 conversation messages for this specific peer
                val messages = status.messageHistory
                    .filter { !it.isBroadcast && (it.targetPeerId == peerId || it.originPeerId == peerId) }
                    .reversed()
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.chatRecyclerView.scrollToPosition(messages.size - 1)
                }

                val isPeerConnected = status.connectedPeers.any { it.deviceId == peerId }
                if (isPeerConnected) {
                    binding.peerStatusText.text = "Connected • 1-to-1 Direct Mesh"
                    binding.peerStatusText.setTextColor(0xFF059669.toInt())
                } else {
                    binding.peerStatusText.text = "Connected via Mesh Relay"
                    binding.peerStatusText.setTextColor(0xFF0284C7.toInt())
                }
            }
        }
    }

    private fun startMicPulse() {
        micPulseAnimator?.cancel()
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.15f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.15f, 1.0f)
        micPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.chatMicButton, scaleX, scaleY).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            interpolator = OvershootInterpolator(1.5f)
            start()
        }
    }

    private fun stopMicPulse() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        binding.chatMicButton.scaleX = 1.0f
        binding.chatMicButton.scaleY = 1.0f
    }

    companion object {
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
    }
}
