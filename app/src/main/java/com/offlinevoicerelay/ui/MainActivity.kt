package com.offlinevoicerelay.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.offlinevoicerelay.R
import com.offlinevoicerelay.databinding.ActivityMainBinding
import com.offlinevoicerelay.model.Language
import com.offlinevoicerelay.model.MessageEntry
import com.offlinevoicerelay.vad.VadForegroundService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: VadForegroundService? = null
    private var bound = false
    private val messageAdapter = MessageAdapter()
    private var pttPulseAnimator: ObjectAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VadForegroundService.LocalBinder).getService()
            bound = true
            observeStatus()
            service?.status?.value?.currentLanguage?.let { updateSelectedLanguageUI(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startAndBindService()
        } else {
            binding.statusText.text = "Microphone / connectivity permissions required."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.messageRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        setupLanguageSelector()

        binding.clearBroadcastButton.setOnClickListener {
            service?.clearBroadcastHistory()
            Toast.makeText(this, "Broadcast activity cleared", Toast.LENGTH_SHORT).show()
        }

        binding.alertModeSwitch.setOnCheckedChangeListener { _, checked ->
            service?.setAlertModeArmed(checked)
            if (checked) maybeRequestDndBypassAccess()
        }

        // Wi-Fi Direct and Bluetooth card click handlers to open Device Discovery
        binding.wifiCard.setOnClickListener {
            val intent = Intent(this, DeviceListActivity::class.java).apply {
                putExtra(DeviceListActivity.EXTRA_INITIAL_TAB, DeviceListActivity.FILTER_WIFI)
            }
            startActivity(intent)
        }

        binding.btCard.setOnClickListener {
            val intent = Intent(this, DeviceListActivity::class.java).apply {
                putExtra(DeviceListActivity.EXTRA_INITIAL_TAB, DeviceListActivity.FILTER_BLUETOOTH)
            }
            startActivity(intent)
        }

        binding.aboutButton.setOnClickListener {
            showAboutBottomSheet()
        }

        binding.mapButton.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            startActivity(intent)
        }

        // Single Broadcast "Hold to Talk" PTT Button
        binding.pushToTalkButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.performClick()
                    binding.pttLabel.text = getString(R.string.transmitting)
                    binding.pttMicIcon.setColorFilter(0xFFFFFFFF.toInt())
                    binding.pushToTalkButton.setBackgroundResource(R.drawable.bg_ptt_transmitting)
                    startPttPulse()
                    service?.onPushToTalkDown(targetPeerId = null)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.pttLabel.text = "Hold to Talk\n(Broadcast Mesh)"
                    binding.pttMicIcon.setColorFilter(0xFFFFFFFF.toInt())
                    binding.pushToTalkButton.setBackgroundResource(R.drawable.bg_ptt_idle)
                    stopPttPulse()
                    service?.onPushToTalkUp()
                }
            }
            true
        }

        if (hasPermissions()) {
            startAndBindService()
        } else {
            requestPermissions.launch(requiredPermissions())
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions() && !bound) {
            startAndBindService()
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun startAndBindService() {
        autoEnableRadios()
        val intent = Intent(this, VadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private var statusJob: kotlinx.coroutines.Job? = null

    private fun observeStatus() {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                service?.status?.collect { status ->
                    val displayStatus = status.lastEvent.ifBlank {
                        if (status.pushToTalkActive) getString(R.string.transmitting) else getString(R.string.idle)
                    }
                    binding.statusText.text = displayStatus

                    if (status.pushToTalkActive) {
                        binding.statusText.setTextColor(0xFF059669.toInt())
                    } else if (displayStatus.contains("Received", ignoreCase = true)) {
                        binding.statusText.setTextColor(0xFF0284C7.toInt())
                    } else if (displayStatus.contains("Failed", ignoreCase = true) || displayStatus.contains("not directly connected", ignoreCase = true)) {
                        binding.statusText.setTextColor(0xFFDC2626.toInt())
                    } else {
                        binding.statusText.setTextColor(0xFF6B7280.toInt())
                    }

                    val wifiCount = status.wifiPeerCount
                    val btCount = status.bluetoothPeerCount
                    val totalCount = status.connectedPeers.size

                    binding.wifiPeerCountText.text = "$wifiCount peers"
                    binding.wifiDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (wifiCount > 0) 0xFF10B981.toInt() else 0xFFD1D5DB.toInt()
                    )

                    binding.btPeerCountText.text = "$btCount peers"
                    binding.btDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (btCount > 0) 0xFF0284C7.toInt() else 0xFFD1D5DB.toInt()
                    )

                    if (status.connectedPeers.isEmpty()) {
                        binding.connectedPeersListText.text = "Connected Peers: None"
                    } else {
                        val names = status.connectedPeers.joinToString { it.displayName }
                        binding.connectedPeersListText.text = "Peers: $names"
                    }

                    binding.waveformView.setAmplitude(
                        status.audioAmplitude,
                        status.pushToTalkActive || displayStatus.contains("Speech", ignoreCase = true)
                    )

                    val broadcastMessages = status.messageHistory.filter { it.isBroadcast }
                    messageAdapter.setMessages(broadcastMessages)
                    if (broadcastMessages.isEmpty()) {
                        binding.emptyLogLayout.visibility = View.VISIBLE
                        binding.messageRecyclerView.visibility = View.GONE
                        binding.clearBroadcastButton.visibility = View.GONE
                    } else {
                        binding.emptyLogLayout.visibility = View.GONE
                        binding.messageRecyclerView.visibility = View.VISIBLE
                        binding.clearBroadcastButton.visibility = View.VISIBLE
                    }
                    updateSelectedLanguageUI(status.currentLanguage)
                }
            }
        }
    }

    private fun setupLanguageSelector() {
        updateSelectedLanguageUI(service?.status?.value?.currentLanguage ?: Language.HINDI)

        binding.languagePillButton.setOnClickListener {
            showLanguagePickerBottomSheet()
        }
    }

    private fun updateSelectedLanguageUI(lang: Language) {
        binding.selectedLanguageText.text = "${lang.displayName} (${lang.code.uppercase()})"
    }

    private fun showLanguagePickerBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetBinding = com.offlinevoicerelay.databinding.DialogLanguagePickerBinding.inflate(layoutInflater)
        dialog.setContentView(sheetBinding.root)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(android.R.color.transparent)
        }

        val currentLang = service?.status?.value?.currentLanguage ?: Language.HINDI
        val adapter = LanguagePickerAdapter(currentLang) { selectedLang ->
            service?.setLanguage(selectedLang)
            updateSelectedLanguageUI(selectedLang)
            dialog.dismiss()
        }

        sheetBinding.languageRecyclerView.layoutManager = LinearLayoutManager(this)
        sheetBinding.languageRecyclerView.adapter = adapter
        dialog.show()
    }

    private fun startPttPulse() {
        pttPulseAnimator?.cancel()
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.06f, 1.0f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.06f, 1.0f)
        pttPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.pushToTalkButton, scaleX, scaleY).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            interpolator = OvershootInterpolator(1.5f)
            start()
        }
    }

    private fun stopPttPulse() {
        pttPulseAnimator?.cancel()
        pttPulseAnimator = null
        binding.pushToTalkButton.scaleX = 1.0f
        binding.pushToTalkButton.scaleY = 1.0f
    }

    private fun maybeRequestDndBypassAccess() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    private fun autoEnableRadios() {
        runCatching {
            val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            if (btAdapter != null && !btAdapter.isEnabled) {
                @Suppress("DEPRECATION")
                btAdapter.enable()
            }
        }
        runCatching {
            val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifiMgr != null && !wifiMgr.isWifiEnabled) {
                @Suppress("DEPRECATION")
                wifiMgr.isWifiEnabled = true
            }
        }
        runCatching {
            val locMgr = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (locMgr?.isLocationEnabled == false) {
                    Toast.makeText(this, "Please enable Location in settings for Wi-Fi Direct", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += android.Manifest.permission.NEARBY_WIFI_DEVICES
            perms += android.Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += android.Manifest.permission.BLUETOOTH_SCAN
            perms += android.Manifest.permission.BLUETOOTH_CONNECT
            perms += android.Manifest.permission.BLUETOOTH_ADVERTISE
        }
        return perms.toTypedArray()
    }

    private fun showAboutBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_about, null)
        sheetView.findViewById<android.view.View>(R.id.dismissAboutButton)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundResource(android.R.color.transparent)
        }
        dialog.show()
    }
}
