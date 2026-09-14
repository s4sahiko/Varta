package com.offlinevoicerelay

import android.app.Application

class OfflineVoiceRelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Intentionally empty: all pipeline setup (VAD/STT/TTS/mesh) happens in
        // VadForegroundService.onCreate(), started explicitly from MainActivity
        // once RECORD_AUDIO + location/Bluetooth/nearby-devices permissions are
        // granted. Nothing here should touch the mic or radios.
    }
}
