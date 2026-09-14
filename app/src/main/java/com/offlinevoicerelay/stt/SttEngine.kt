package com.offlinevoicerelay.stt

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.offlinevoicerelay.model.Language
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

sealed class SttResult {
    data class Partial(val text: String) : SttResult()
    data class Final(val text: String) : SttResult()
    data class Error(val message: String) : SttResult()
}

interface SttEngine {
    /** Transcribes a finalized utterance (PCM16 mono @16kHz) in [language]. */
    fun transcribe(pcm16: ShortArray, language: Language): SttResult
    fun isLanguageLoaded(language: Language): Boolean
    fun preload(language: Language)
    fun release(language: Language)
}

/**
 * Vosk offline speech recognition engine. Loads small Vosk models on demand
 * from assets/models/stt/vosk-<lang>, unpacking into filesDir/models/vosk-<lang>.
 *
 * Manages per-language memory lifecycle by closing previous Model and Recognizer
 * instances on language switch.
 */
class VoskSttEngine(private val context: Context) : SttEngine {

    private var currentLanguage: Language? = null
    private var currentModel: Model? = null
    private var currentRecognizer: Recognizer? = null

    override fun isLanguageLoaded(language: Language): Boolean =
        currentLanguage == language && currentModel != null

    override fun preload(language: Language) {
        if (currentLanguage == language && currentModel != null) return
        releaseCurrent()

        val modelDir = File(context.filesDir, "models/vosk-${language.code}")
        val marker = File(modelDir, ".complete")
        if (!modelDir.exists() || !marker.exists() || !File(modelDir, "am/final.mdl").exists()) {
            val assetPath = "models/stt/vosk-${language.code}"
            runCatching {
                val tmpDir = File(context.filesDir, "models/vosk-${language.code}.tmp")
                tmpDir.deleteRecursively()
                val copied = copyAssetFolder(context.assets, assetPath, tmpDir.absolutePath)
                if (copied && File(tmpDir, "am/final.mdl").exists()) {
                    File(tmpDir, ".complete").createNewFile()
                    modelDir.deleteRecursively()
                    tmpDir.renameTo(modelDir)
                }
            }
        }

        if (modelDir.exists() && File(modelDir, ".complete").exists() && File(modelDir, "am/final.mdl").exists()) {
            runCatching {
                val model = Model(modelDir.absolutePath)
                val recognizer = Recognizer(model, 16000f)
                currentModel = model
                currentRecognizer = recognizer
                currentLanguage = language
                Log.i("VoskSttEngine", "Loaded Vosk model for ${language.displayName}")
            }.onFailure {
                Log.w("VoskSttEngine", "Failed to load Vosk model for ${language.displayName}: ${it.message}")
            }
        } else {
            Log.w("VoskSttEngine", "No Vosk model files found at ${modelDir.absolutePath}")
        }
    }

    @Synchronized
    override fun transcribe(pcm16: ShortArray, language: Language): SttResult {
        preload(language)
        val model = currentModel
            ?: return SttResult.Error("Vosk STT model not available for ${language.displayName}")

        return try {
            val recognizer = Recognizer(model, 16000f)
            try {
                val byteBuffer = ByteBuffer.allocate(pcm16.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in pcm16) {
                    byteBuffer.putShort(sample)
                }
                val audioBytes = byteBuffer.array()

                recognizer.acceptWaveForm(audioBytes, audioBytes.size)
                val jsonResult = recognizer.getFinalResult()
                val text = JSONObject(jsonResult).optString("text", "").trim()

                if (text.isNotBlank()) {
                    SttResult.Final(text)
                } else {
                    SttResult.Error("No speech recognized")
                }
            } finally {
                recognizer.close()
            }
        } catch (t: Throwable) {
            Log.e("VoskSttEngine", "Error during Vosk transcription: ${t.message}", t)
            SttResult.Error("Vosk transcription error: ${t.message}")
        }
    }

    override fun release(language: Language) {
        if (currentLanguage == language) {
            releaseCurrent()
        }
    }

    fun releaseAll() {
        releaseCurrent()
    }

    private fun releaseCurrent() {
        currentRecognizer?.close()
        currentRecognizer = null
        currentModel?.close()
        currentModel = null
        currentLanguage = null
    }

    private fun copyAssetFolder(assetManager: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val files = assetManager.list(fromAssetPath) ?: return false
            File(toPath).mkdirs()
            var res = true
            for (file in files) {
                val subAssetPath = if (fromAssetPath.isEmpty()) file else "$fromAssetPath/$file"
                val subToPath = "$toPath/$file"
                val subFiles = assetManager.list(subAssetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    res = res && copyAssetFolder(assetManager, subAssetPath, subToPath)
                } else {
                    try {
                        assetManager.open(subAssetPath).use { input ->
                            File(subToPath).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VoskSttEngine", "Failed to copy asset $subAssetPath: ${e.message}", e)
                        res = false
                    }
                }
            }
            res
        } catch (e: Exception) {
            Log.e("VoskSttEngine", "Error copying folder $fromAssetPath: ${e.message}", e)
            false
        }
    }
}

/**
 * Wraps one TFLite interpreter per language, loaded lazily on first use or
 * explicit [preload].
 */
class TfLiteSttEngine(private val context: Context) : SttEngine {

    private val interpreters = ConcurrentHashMap<Language, Interpreter>()
    private val failedToLoad = ConcurrentHashMap<Language, Boolean>()

    override fun isLanguageLoaded(language: Language): Boolean = interpreters.containsKey(language)

    override fun preload(language: Language) {
        if (interpreters.containsKey(language) || failedToLoad.containsKey(language)) return
        val assetPath = "models/stt/${language.code}.tflite"
        val interpreter = runCatching { loadInterpreter(assetPath) }.getOrNull()
        if (interpreter != null) {
            interpreters[language] = interpreter
        } else {
            failedToLoad[language] = true
        }
    }

    override fun transcribe(pcm16: ShortArray, language: Language): SttResult {
        preload(language)
        val interpreter = interpreters[language]
            ?: return SttResult.Error(
                "No STT model loaded for ${language.displayName} " +
                    "(expected assets/models/stt/${language.code}.tflite)."
            )

        val pcmFloat = FloatArray(pcm16.size) { i -> pcm16[i] / 32768f }

        return try {
            val inputTensor = interpreter.getInputTensor(0)
            val inputShape = inputTensor.shape()
            val fixedInputSize = if (inputShape.size >= 2 && inputShape[1] > 0) inputShape[1] else -1

            val formattedPcmFloat = if (fixedInputSize > 0) {
                if (pcmFloat.size == fixedInputSize) {
                    pcmFloat
                } else if (pcmFloat.size < fixedInputSize) {
                    FloatArray(fixedInputSize).also { System.arraycopy(pcmFloat, 0, it, 0, pcmFloat.size) }
                } else {
                    pcmFloat.copyOfRange(0, fixedInputSize)
                }
            } else {
                runCatching {
                    interpreter.resizeInput(0, intArrayOf(1, pcmFloat.size))
                    interpreter.allocateTensors()
                }
                pcmFloat
            }

            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            val vocabSize = if (outputShape.isNotEmpty()) outputShape.last() else 255
            val timeSteps = if (outputShape.size >= 3) outputShape[1]
                            else if (outputShape.size >= 2) outputShape[0]
                            else maxOf(1, formattedPcmFloat.size / 320)

            val outputNumBytes = maxOf(4, outputTensor.numBytes())
            val outputBuffer = ByteBuffer.allocateDirect(outputNumBytes).order(ByteOrder.nativeOrder())

            interpreter.run(arrayOf(formattedPcmFloat), outputBuffer)
            outputBuffer.rewind()

            val isFloat32 = outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
            val tokenIds = IntArray(timeSteps) {
                var maxIdx = 0
                var maxVal = Float.NEGATIVE_INFINITY
                for (v in 0 until vocabSize) {
                    val score = if (isFloat32) {
                        if (outputBuffer.hasRemaining()) outputBuffer.float else 0f
                    } else {
                        if (outputBuffer.hasRemaining()) (outputBuffer.short.toInt() / 32768f) else 0f
                    }
                    if (score > maxVal) {
                        maxVal = score
                        maxIdx = v
                    }
                }
                maxIdx
            }

            val decoded = CtcGreedyDecoder.decode(tokenIds, language, context)
            if (decoded is SttResult.Final && decoded.text.isNotBlank()) {
                decoded
            } else {
                SttResult.Error("TFLite decoder produced no text for ${language.displayName}")
            }
        } catch (t: Throwable) {
            Log.e("TfLiteSttEngine", "STT inference error for ${language.displayName}: ${t.message}", t)
            SttResult.Error("STT inference error: ${t.message}")
        }
    }

    override fun release(language: Language) {
        interpreters.remove(language)?.close()
        failedToLoad.remove(language)
    }

    fun releaseAll() {
        interpreters.values.forEach { it.close() }
        interpreters.clear()
        failedToLoad.clear()
    }

    private fun loadInterpreter(assetPath: String): Interpreter {
        val afd = context.assets.openFd(assetPath)
        val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
        return Interpreter(buffer, Interpreter.Options().apply {
            setNumThreads(2)
            setAllowFp16PrecisionForFp32(true)
        })
    }
}

/**
 * Hybrid STT Engine that delegates to VoskSttEngine or TfLiteSttEngine based on asset availability.
 * Automatically falls back to available installed models if a specific language asset model is missing.
 */
class HybridSttEngine(private val context: Context) : SttEngine {
    private val voskEngine = VoskSttEngine(context)
    private val tfLiteEngine = TfLiteSttEngine(context)

    override fun isLanguageLoaded(language: Language): Boolean {
        return voskEngine.isLanguageLoaded(language) || tfLiteEngine.isLanguageLoaded(language)
    }

    override fun preload(language: Language) {
        val target = resolveTargetLanguage(language)
        if (hasVoskModel(target)) {
            voskEngine.preload(target)
        } else {
            tfLiteEngine.preload(target)
        }
    }

    override fun transcribe(pcm16: ShortArray, language: Language): SttResult {
        val target = resolveTargetLanguage(language)
        val result = if (hasVoskModel(target)) {
            voskEngine.transcribe(pcm16, target)
        } else {
            tfLiteEngine.transcribe(pcm16, target)
        }

        return if (result is SttResult.Error && target != Language.HINDI) {
            // Fall back to Hindi model if selected language model failed or produced no text
            if (hasVoskModel(Language.HINDI)) {
                voskEngine.transcribe(pcm16, Language.HINDI)
            } else if (hasTfLiteModel(Language.HINDI)) {
                tfLiteEngine.transcribe(pcm16, Language.HINDI)
            } else {
                result
            }
        } else {
            result
        }
    }

    override fun release(language: Language) {
        voskEngine.release(language)
        tfLiteEngine.release(language)
    }

    fun releaseAll() {
        voskEngine.releaseAll()
        tfLiteEngine.releaseAll()
    }

    private fun hasVoskModel(language: Language): Boolean {
        val modelDir = File(context.filesDir, "models/vosk-${language.code}")
        if (modelDir.exists() && File(modelDir, "am/final.mdl").exists()) return true
        return runCatching {
            val list = context.assets.list("models/stt/vosk-${language.code}")
            list != null && list.isNotEmpty()
        }.getOrDefault(false)
    }

    private fun hasTfLiteModel(language: Language): Boolean {
        return runCatching {
            context.assets.openFd("models/stt/${language.code}.tflite").close()
            true
        }.getOrDefault(false)
    }

    private fun resolveTargetLanguage(language: Language): Language {
        if (hasVoskModel(language) || hasTfLiteModel(language)) return language
        if (hasVoskModel(Language.HINDI) || hasTfLiteModel(Language.HINDI)) return Language.HINDI
        return language
    }
}

/**
 * Greedy CTC decoder with vocabulary file support.
 */
internal object CtcGreedyDecoder {

    private const val TAG = "CtcGreedyDecoder"
    private val vocabCache = ConcurrentHashMap<Language, List<String>>()

    fun decode(tokenIds: IntArray, language: Language, context: Context): SttResult {
        val vocab = vocabOrNull(language, context)
            ?: vocabOrNull(Language.HINDI, context)
            ?: return SttResult.Error(
                "Vocab file missing for ${language.displayName} " +
                    "(expected assets/models/stt/${language.code}.vocab)."
            )

        val text = buildString {
            var prev = -1
            for (id in tokenIds) {
                if (id == prev) continue         // CTC repeat collapse
                prev = id
                if (id < 0 || id >= vocab.size) continue
                val rawToken = vocab[id]
                if (rawToken == "<blank>" || rawToken == " " || rawToken.isEmpty() ||
                    rawToken.startsWith("[") || rawToken.startsWith("<")) continue

                if (rawToken.startsWith("##")) {
                    append(rawToken.removePrefix("##"))
                } else {
                    if (isNotEmpty() && !endsWith(" ")) {
                        append(" ")
                    }
                    append(rawToken)
                }
            }
        }.trim()

        return if (text.isNotBlank()) SttResult.Final(text)
        else SttResult.Error("CTC greedy decoder produced empty text")
    }

    private fun vocabOrNull(language: Language, context: Context): List<String>? {
        vocabCache[language]?.let { return it }
        val assetPath = "models/stt/${language.code}.vocab"
        return try {
            val lines = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).readLines()
            lines.also { vocabCache[language] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load vocab at $assetPath: ${e.message}")
            null
        }
    }

    fun clearCache() = vocabCache.clear()
}
