package com.offlinevoicerelay.vad

/**
 * Turns a stream of per-frame VAD decisions into utterance boundaries:
 * speech onset fires immediately, end-of-utterance fires after
 * [trailingSilenceMs] of continuous non-speech (Section 2: "finalizes on
 * ~500ms of trailing silence").
 *
 * Pure Kotlin, no Android dependency, so this is testable with plain JUnit
 * without Robolectric or a device.
 */
class VadStateMachine(
    private val frameDurationMs: Long = VadEngine.FRAME_DURATION_MS,
    private val trailingSilenceMs: Long = DEFAULT_TRAILING_SILENCE_MS,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: () -> Unit
) {
    var inSpeech = false
        private set
    private var silenceAccumMs = 0L

    fun onFrame(result: VadFrameResult) {
        if (result.isSpeech) {
            silenceAccumMs = 0
            if (!inSpeech) {
                inSpeech = true
                onSpeechStart()
            }
        } else if (inSpeech) {
            silenceAccumMs += frameDurationMs
            if (silenceAccumMs >= trailingSilenceMs) {
                inSpeech = false
                silenceAccumMs = 0
                onSpeechEnd()
            }
        }
    }

    fun reset() {
        inSpeech = false
        silenceAccumMs = 0
    }

    companion object {
        const val DEFAULT_TRAILING_SILENCE_MS = 500L
    }
}
