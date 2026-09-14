package com.offlinevoicerelay.vad

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

data class VadFrameResult(val isSpeech: Boolean, val confidence: Float)

/** One VAD decision per audio frame. Frame size is fixed by the caller (256ms, per PRD Section 4). */
interface VadEngine {
    /** [frame] is 16-bit PCM mono samples at [SAMPLE_RATE_HZ]. */
    fun processFrame(frame: ShortArray): VadFrameResult

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_DURATION_MS = 256L
        const val FRAME_SIZE_SAMPLES = (SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000).toInt()
    }
}

/**
 * Immediately-runnable fallback: RMS energy over a noise floor, no model file
 * required. This is what the app runs out of the box before a Silero-VAD
 * model is dropped into assets/models/vad/silero_vad.tflite — swap the
 * factory in [VadEngineFactory] once that file is present.
 *
 * This is deliberately the PRD's named fallback tier ("WebRTC VAD as
 * fallback") implemented without a native WebRTC dependency, since that
 * library isn't part of this environment's reachable package registries.
 * It is noisier than Silero/WebRTC in real disaster-noise conditions (wind,
 * crowd noise) — see README "Known limitations".
 */
class EnergyThresholdVadEngine(
    private val energyThreshold: Double = DEFAULT_ENERGY_THRESHOLD,
    private val noiseFloorAdaptRate: Double = 0.05
) : VadEngine {

    private var noiseFloor = 200.0 // running estimate, adapts to ambient noise

    override fun processFrame(frame: ShortArray): VadFrameResult {
        val rms = rms(frame)
        val isSpeech = rms > noiseFloor * energyThreshold
        if (!isSpeech) {
            // Only adapt the noise floor during silence so a sustained loud
            // utterance doesn't drag the threshold up mid-speech.
            noiseFloor = noiseFloor * (1 - noiseFloorAdaptRate) + rms * noiseFloorAdaptRate
        }
        val confidence = (rms / (noiseFloor * energyThreshold)).coerceIn(0.0, 1.0).toFloat()
        return VadFrameResult(isSpeech, confidence)
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in frame) sumSq += (s.toDouble() * s.toDouble())
        return sqrt(sumSq / frame.size)
    }

    companion object {
        const val DEFAULT_ENERGY_THRESHOLD = 2.2
    }
}

/**
 * Model-backed VAD (Section 4: "Silero-VAD (ONNX, ~1MB)"). Loads a TFLite
 * export of Silero-VAD lazily on first use. Requires
 * assets/models/vad/silero_vad.tflite to be present — see
 * /tools/download_and_convert_models.py. Falls back to
 * [EnergyThresholdVadEngine] if the asset is missing, so the app never
 * crashes on a missing model file; it just runs at reduced VAD accuracy.
 */
class SileroVadEngine(
    private val context: Context,
    private val modelAssetPath: String = "models/vad/silero_vad.tflite"
) : VadEngine {

    private val fallback by lazy { EnergyThresholdVadEngine() }
    private var interpreter: Interpreter? = null
    private var loadAttempted = false

    override fun processFrame(frame: ShortArray): VadFrameResult {
        val interp = interpreterOrNull() ?: return fallback.processFrame(frame)

        val input = Array(1) { FloatArray(frame.size) { i -> frame[i] / 32768f } }
        val output = Array(1) { FloatArray(1) }
        return try {
            interp.run(input, output)
            val speechProb = output[0][0]
            VadFrameResult(isSpeech = speechProb > 0.5f, confidence = speechProb)
        } catch (t: Throwable) {
            // Never let a model-shape mismatch or runtime error kill the mic pipeline.
            fallback.processFrame(frame)
        }
    }

    private fun interpreterOrNull(): Interpreter? {
        if (interpreter == null && !loadAttempted) {
            loadAttempted = true
            interpreter = runCatching { loadModel(modelAssetPath) }.getOrNull()
        }
        return interpreter
    }

    private fun loadModel(assetPath: String): Interpreter {
        val afd = context.assets.openFd(assetPath)
        val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
        return Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

object VadEngineFactory {
    /** Prefers the model-backed engine; transparently degrades if the asset is absent. */
    fun create(context: Context): VadEngine = SileroVadEngine(context)
}
