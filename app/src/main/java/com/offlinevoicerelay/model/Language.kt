package com.offlinevoicerelay.model

/**
 * The 10 languages required by the PRD (Section 8 "PROJECT" block).
 *
 * [code] is the BCP-47-ish tag used for model asset filenames:
 *   assets/models/stt/<code>.tflite
 *   assets/models/tts/<code>_acoustic.tflite
 *   assets/models/tts/<code>_vocoder.tflite
 *
 * Model files are NOT bundled in this repo (they are hundreds of MB across
 * 10 languages) — see /tools/download_and_convert_models.py and the README
 * "Model integration" section for how to obtain and drop them in.
 */
enum class Language(val code: String, val displayName: String, val englishName: String) {
    HINDI("hi", "हिन्दी", "Hindi"),
    GUJARATI("gu", "ગુજરાતી", "Gujarati"),
    MARATHI("mr", "मराठी", "Marathi"),
    KANNADA("kn", "ಕನ್ನಡ", "Kannada"),
    MALAYALAM("ml", "മലയാളം", "Malayalam"),
    TAMIL("ta", "தமிழ்", "Tamil"),
    TELUGU("te", "తెలుగు", "Telugu"),
    ODIA("or", "ଓଡ଼ିଆ", "Odia"),
    BENGALI("bn", "বাংলা", "Bengali"),
    ENGLISH("en", "English", "English");

    companion object {
        fun fromCode(code: String): Language =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unsupported language code: $code")
    }
}
