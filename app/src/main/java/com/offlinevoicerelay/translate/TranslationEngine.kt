package com.offlinevoicerelay.translate

import com.offlinevoicerelay.model.Language

/**
 * Contract for offline inter-language translation.
 */
interface TranslationEngine {
    /**
     * Translates [text] from [from] language to [to] language.
     * Guaranteed to work fully offline.
     */
    suspend fun translate(text: String, from: Language, to: Language): String
}
