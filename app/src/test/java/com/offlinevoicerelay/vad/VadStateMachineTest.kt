package com.offlinevoicerelay.vad

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VadStateMachineTest {

    @Test
    fun `speech start fires on first speech frame`() {
        var starts = 0
        val sm = VadStateMachine(frameDurationMs = 256, trailingSilenceMs = 500,
            onSpeechStart = { starts++ }, onSpeechEnd = {})

        sm.onFrame(VadFrameResult(isSpeech = true, confidence = 0.9f))

        assertThat(starts).isEqualTo(1)
        assertThat(sm.inSpeech).isTrue()
    }

    @Test
    fun `speech end fires only after trailing silence threshold`() {
        var ends = 0
        // 256ms frames, 500ms trailing silence => needs 2 silent frames (512ms) to finalize
        val sm = VadStateMachine(frameDurationMs = 256, trailingSilenceMs = 500,
            onSpeechStart = {}, onSpeechEnd = { ends++ })

        sm.onFrame(VadFrameResult(true, 0.9f))   // speech starts
        sm.onFrame(VadFrameResult(false, 0.1f))  // 256ms silence - not enough yet
        assertThat(ends).isEqualTo(0)
        assertThat(sm.inSpeech).isTrue()

        sm.onFrame(VadFrameResult(false, 0.1f))  // 512ms silence - finalize
        assertThat(ends).isEqualTo(1)
        assertThat(sm.inSpeech).isFalse()
    }

    @Test
    fun `brief silence mid-utterance does not falsely finalize`() {
        var ends = 0
        val sm = VadStateMachine(frameDurationMs = 256, trailingSilenceMs = 500,
            onSpeechStart = {}, onSpeechEnd = { ends++ })

        sm.onFrame(VadFrameResult(true, 0.9f))
        sm.onFrame(VadFrameResult(false, 0.1f)) // brief pause between words
        sm.onFrame(VadFrameResult(true, 0.9f))  // speech resumes - silence counter resets

        assertThat(ends).isEqualTo(0)
        assertThat(sm.inSpeech).isTrue()
    }

    @Test
    fun `silence frames before any speech are no-ops`() {
        var starts = 0
        var ends = 0
        val sm = VadStateMachine(onSpeechStart = { starts++ }, onSpeechEnd = { ends++ })

        repeat(10) { sm.onFrame(VadFrameResult(false, 0f)) }

        assertThat(starts).isEqualTo(0)
        assertThat(ends).isEqualTo(0)
    }

    @Test
    fun `reset clears in-progress speech state without firing callbacks`() {
        var ends = 0
        val sm = VadStateMachine(onSpeechStart = {}, onSpeechEnd = { ends++ })
        sm.onFrame(VadFrameResult(true, 0.9f))

        sm.reset()

        assertThat(sm.inSpeech).isFalse()
        assertThat(ends).isEqualTo(0)
    }
}
