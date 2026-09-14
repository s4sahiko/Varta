package com.offlinevoicerelay.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.offlinevoicerelay.model.Language
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** PCM16 mono @22050Hz float synthesis output, ready for AlertAudioPlayer. */
data class SynthResult(val pcm16: ShortArray, val sampleRateHz: Int)

interface TtsEngine {
    suspend fun synthesize(text: String, language: Language): SynthResult?
    fun isLanguageLoaded(language: Language): Boolean
    fun preload(language: Language)
}

/**
 * Section 4 TTS row: FastPitch (acoustic model, text -> mel-spectrogram) +
 * HiFi-GAN (vocoder, mel-spectrogram -> waveform), two TFLite graphs per
 * language, both required:
 *   assets/models/tts/<lang-code>_acoustic.tflite
 *   assets/models/tts/<lang-code>_vocoder.tflite
 *
 * As with [com.offlinevoicerelay.stt.TfLiteSttEngine], this defines the
 * integration contract and lazy-loading behavior; it does not ship a real
 * checkpoint. synthesize() returns null (not a crash) if a model is absent,
 * so callers must handle that — [AndroidSystemTtsEngine] below is the
 * dev-time stand-in used to exercise the rest of the app right now.
 */
class TfLiteTtsEngine(private val context: Context) : TtsEngine {

    private data class LoadedModels(val acoustic: Interpreter, val vocoder: Interpreter)

    private val models = ConcurrentHashMap<Language, LoadedModels>()
    private val failedToLoad = ConcurrentHashMap<Language, Boolean>()

    override fun isLanguageLoaded(language: Language) = models.containsKey(language)

    override fun preload(language: Language) {
        if (models.containsKey(language) || failedToLoad.containsKey(language)) return
        val loaded = runCatching {
            val acoustic = loadInterpreter("models/tts/${language.code}_acoustic.tflite")
            val vocoder = loadInterpreter("models/tts/${language.code}_vocoder.tflite")
            LoadedModels(acoustic, vocoder)
        }.getOrNull()

        if (loaded != null) models[language] = loaded else failedToLoad[language] = true
    }

    override suspend fun synthesize(text: String, language: Language): SynthResult? {
        preload(language)
        val loaded = models[language] ?: return null

        return runCatching {
            // M3: Character-level tokenizer stub — maps each Unicode codepoint
            // to an integer ID. This is a functional placeholder; a real
            // deployment must replace this with the exact text-frontend
            // (grapheme-to-phoneme / SentencePiece tokenizer) used during
            // model training. Using a mismatched tokenizer will produce garbled
            // audio even when the model file is correct.
            val tokenIds = IntArray(text.length) { i -> text[i].code }

            // Stage 1: text -> mel-spectrogram (FastPitch).
            val melFrames = 200 // placeholder length; real graph returns dynamic T
            val melBins = 80
            val melInput = arrayOf(tokenIds)
            val melOutput = arrayOf(Array(melFrames) { FloatArray(melBins) })
            loaded.acoustic.run(melInput, melOutput)

            // Stage 2: mel-spectrogram -> waveform (HiFi-GAN), non-autoregressive
            // so RTF stays low per Section 5 target (RTF < 0.3).
            val hopSize = 256
            val waveform = FloatArray(melFrames * hopSize)
            loaded.vocoder.run(melOutput, arrayOf(waveform))

            SynthResult(
                pcm16 = waveform
                    .map { (it * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
                    .toShortArray(),
                sampleRateHz = 22_050
            )
        }.getOrNull()
    }

    private fun loadInterpreter(assetPath: String): Interpreter {
        val afd = context.assets.openFd(assetPath)
        val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
        return Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
    }
}

/**
 * DEV/TESTING BRIDGE ONLY — uses the phone's built-in Android TextToSpeech
 * so the push-to-talk / mesh / ALERT pipeline can be exercised end to end
 * before real FastPitch+HiFi-GAN checkpoints are converted and bundled.
 *
 * Do NOT ship this as the release TTS path: (a) language/voice coverage and
 * quality depend on whatever TTS data is installed on the device, which the
 * PRD's "fully offline, guaranteed 10-language" requirement can't rely on,
 * and (b) some OEM TTS engines are not open-source, which conflicts with
 * Section 6's "open-source only" constraint. Gate this behind a debug build
 * flag (see app/build.gradle.kts buildTypes) and switch to [TfLiteTtsEngine]
 * for release builds once models are in place.
 */
class AndroidSystemTtsEngine(context: Context) : TtsEngine {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    }

    override fun isLanguageLoaded(language: Language) = ready
    override fun preload(language: Language) { /* system TTS has no per-language preload step */ }

    override suspend fun synthesize(text: String, language: Language): SynthResult? {
        val engine = tts ?: return null
        if (!ready) return null
        engine.language = localeFor(language)

        // Route to a temp WAV file, then read it back as PCM16 — the public
        // TextToSpeech API has no direct in-memory PCM callback.
        val outFile = java.io.File.createTempFile("tts_", ".wav")
        return try {
            val completed = suspendCoroutine<Boolean> { cont ->
                val utteranceId = "u_${System.nanoTime()}"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == utteranceId) cont.resume(true) }
                    // API 21+ preferred override (non-deprecated).
                    override fun onError(id: String?, errorCode: Int) { if (id == utteranceId) cont.resume(false) }
                    // Legacy fallback required by the abstract class (deprecated since API 21).
                    // OVERRIDE_DEPRECATION: we must implement this abstract method; the API-21+
                    // onError(String?, Int) override above is the preferred handler at runtime.
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String?) { if (id == utteranceId) cont.resume(false) }
                })
                engine.synthesizeToFile(text, null, outFile, utteranceId)
            }
            if (!completed) null
            else runCatching { WavReader.readPcm16(outFile) }.getOrNull()
        } finally {
            // Bug-fix #12: Always delete the temp WAV file to prevent unbounded
            // disk usage. synthesizeToFile() writes a new file per call, so
            // without this cleanup the /tmp directory fills up over a long session.
            outFile.delete()
        }
    }

    private fun localeFor(language: Language): Locale = when (language) {
        Language.HINDI     -> Locale("hi", "IN")
        Language.GUJARATI  -> Locale("gu", "IN")
        Language.MARATHI   -> Locale("mr", "IN")
        Language.KANNADA   -> Locale("kn", "IN")
        Language.MALAYALAM -> Locale("ml", "IN")
        Language.TAMIL     -> Locale("ta", "IN")
        Language.TELUGU    -> Locale("te", "IN")
        Language.ODIA      -> Locale("or", "IN")
        Language.BENGALI   -> Locale("bn", "IN")
        Language.ENGLISH   -> Locale.ENGLISH
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}

internal object WavReader {
    /**
     * Robust PCM16 mono WAV reader.
     *
     * Bug-fix #13: The previous version assumed a fixed 44-byte canonical RIFF
     * header. Real WAV files from Android TTS engines commonly include additional
     * RIFF chunks (LIST metadata, fmt extension bytes) that push the data chunk
     * start past offset 44, causing an ArrayIndexOutOfBoundsException or
     * silently garbled audio when reading the wrong region.
     *
     * This version scans forward through all RIFF chunks looking for the "data"
     * FourCC tag, making it robust to any standard-compliant WAV file.
     */
    fun readPcm16(file: java.io.File): SynthResult {
        val bytes = file.readBytes()
        if (bytes.size < 44) error("WAV file too short: ${bytes.size} bytes")

        // Parse RIFF header (bytes 0-11): "RIFF" <fileSize:LE32> "WAVE"
        val riff = bytes.sliceArray(0..3).toString(Charsets.US_ASCII)
        check(riff == "RIFF") { "Not a RIFF file (got: $riff)" }
        val wave = bytes.sliceArray(8..11).toString(Charsets.US_ASCII)
        check(wave == "WAVE") { "RIFF type is not WAVE (got: $wave)" }

        // Read the sample rate from the fmt chunk. The fmt chunk is always the
        // first chunk after the RIFF header (offset 12). Its layout:
        //   [0..3]  "fmt " FourCC
        //   [4..7]  chunk size (LE32) — typically 16 for PCM
        //   [8..9]  audio format (1 = PCM)
        //   [10..11] num channels
        //   [12..15] sample rate (LE32)  ← we want this
        val sampleRate = readLeInt(bytes, 12 + 8 + 4)

        // Scan chunks from offset 12 to find the "data" chunk.
        var offset = 12
        var dataStart = -1
        var dataLength = -1
        while (offset + 8 <= bytes.size) {
            val tag = bytes.sliceArray(offset..offset + 3).toString(Charsets.US_ASCII)
            val chunkSize = readLeInt(bytes, offset + 4)
            if (tag == "data") {
                dataStart = offset + 8
                dataLength = chunkSize
                break
            }
            // Advance to next chunk; chunk sizes are padded to even byte counts.
            offset += 8 + ((chunkSize + 1) and 1.inv())
        }
        check(dataStart >= 0) { "No 'data' chunk found in WAV file" }
        check(dataStart + dataLength <= bytes.size) {
            "data chunk size ($dataLength) exceeds file size (${bytes.size})"
        }

        val sampleCount = dataLength / 2
        val samples = ShortArray(sampleCount) { i ->
            val lo = bytes[dataStart + i * 2].toInt() and 0xFF
            val hi = bytes[dataStart + i * 2 + 1].toInt()
            ((hi shl 8) or lo).toShort()
        }
        return SynthResult(samples, sampleRate)
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
