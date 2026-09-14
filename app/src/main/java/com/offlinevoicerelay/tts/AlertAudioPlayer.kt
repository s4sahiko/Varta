package com.offlinevoicerelay.tts

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays synthesized speech. Two modes per Section 4 TTS row:
 *  - Normal message: plays as an ordinary voice note, respects silent/DND.
 *  - ALERT message: forces max volume on loudspeaker, bypasses silent/DND,
 *    generates 3 warning beeps first, and is non-interruptible until completed.
 */
class AlertAudioPlayer(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var currentFocusRequest: AudioFocusRequest? = null

    suspend fun play(result: SynthResult, isAlert: Boolean) {
        if (isAlert) playAsAlert(result) else playNormal(result)
    }

    private suspend fun playNormal(result: SynthResult) {
        audioManager.mode = AudioManager.MODE_NORMAL
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        playTrack(result, attrs, requestFocus = true, gainType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
    }

    private suspend fun playAsAlert(result: SynthResult) {
        val previousRingerMode = audioManager.ringerMode
        val previousAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val previousMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val previousNotifVol = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(0)

        // Force maximum volume across alarm and media streams
        val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val maxNotif = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) }.getOrDefault(maxAlarm)

        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxNotif, 0)
        }

        // Bypass DND and Silent Mode
        if (notificationManager.isNotificationPolicyAccessGranted) {
            runCatching {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        runCatching {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }

        // Prepend 3 sharp emergency beeps before speech synthesis
        val beepsPcm = generate3Beeps(result.sampleRateHz)
        val pauseSamples = (result.sampleRateHz * 0.15).toInt()
        val pausePcm = ShortArray(pauseSamples)

        val combinedPcm = ShortArray(beepsPcm.size + pausePcm.size + result.pcm16.size)
        System.arraycopy(beepsPcm, 0, combinedPcm, 0, beepsPcm.size)
        System.arraycopy(pausePcm, 0, combinedPcm, beepsPcm.size, pausePcm.size)
        System.arraycopy(result.pcm16, 0, combinedPcm, beepsPcm.size + pausePcm.size, result.pcm16.size)

        val alertPayload = SynthResult(combinedPcm, result.sampleRateHz)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM) // Alarm category bypasses DND and silent mode
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        try {
            // AUDIOFOCUS_GAIN (exclusive + no ducking): non-interruptible until completed
            playTrack(alertPayload, attrs, requestFocus = true, gainType = AudioManager.AUDIOFOCUS_GAIN)
        } finally {
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVol, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVol, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, previousNotifVol, 0)
                audioManager.ringerMode = previousRingerMode
            }
        }
    }

    private suspend fun playTrack(
        result: SynthResult,
        attrs: AudioAttributes,
        requestFocus: Boolean,
        gainType: Int
    ) = suspendCancellableCoroutine<Unit> { cont ->
        var focusRequest: AudioFocusRequest? = null
        if (requestFocus) {
            focusRequest = AudioFocusRequest.Builder(gainType)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .build()
            currentFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(result.sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(result.pcm16.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(result.pcm16, 0, result.pcm16.size)
        track.setNotificationMarkerPosition(result.pcm16.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                track.release()
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track.play()

        cont.invokeOnCancellation {
            runCatching { track.stop(); track.release() }
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
    }

    companion object {
        /**
         * Generates 3 distinct emergency warning beeps (1200Hz sine waves) as PCM16 mono samples.
         */
        fun generate3Beeps(sampleRateHz: Int = 22050): ShortArray {
            val beepDurationMs = 160
            val silenceDurationMs = 80
            val beepsCount = 3
            val freqHz = 1200.0

            val beepSamples = (sampleRateHz * beepDurationMs) / 1000
            val silenceSamples = (sampleRateHz * silenceDurationMs) / 1000
            val totalSamples = (beepSamples + silenceSamples) * beepsCount
            val pcm = ShortArray(totalSamples)

            var offset = 0
            val attackDecay = (beepSamples * 0.08).toInt().coerceAtLeast(1)

            for (b in 0 until beepsCount) {
                // Beep tone with smooth attack and decay
                for (i in 0 until beepSamples) {
                    val angle = 2.0 * PI * i * freqHz / sampleRateHz
                    var amplitude = sin(angle)
                    if (i < attackDecay) {
                        amplitude *= (i.toDouble() / attackDecay)
                    } else if (i > beepSamples - attackDecay) {
                        amplitude *= ((beepSamples - i).toDouble() / attackDecay)
                    }
                    pcm[offset++] = (amplitude * 32000).toInt().coerceIn(-32768, 32767).toShort()
                }
                // Silence between beeps
                for (i in 0 until silenceSamples) {
                    pcm[offset++] = 0
                }
            }
            return pcm
        }
    }
}
