package com.offlinevoicerelay.translate

import android.content.Context
import com.offlinevoicerelay.model.Language
import java.util.Locale

/**
 * High-performance, fully offline multi-language translation engine for real-time mesh relay communication.
 *
 * Supports all 10 project languages:
 * Hindi (hi), Gujarati (gu), Marathi (mr), Kannada (kn), Malayalam (ml),
 * Tamil (ta), Telugu (te), Odia (or), Bengali (bn), English (en).
 *
 * Features:
 * 1. Rich conversational, greeting, question, and emergency phrase database.
 * 2. Exact & phonetic alias matching (Devanagari, Romanized/Hinglish, native scripts).
 * 3. Multi-word sliding window matcher with token boundaries for compound sentences.
 * 4. Token-level dictionary with semantic grammar alignment.
 * 5. Cross-Indic phonetic script transliteration fallback.
 */
class OfflineTranslationEngine(private val context: Context) : TranslationEngine {

    override suspend fun translate(text: String, from: Language, to: Language): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || from == to) return trimmed

        // 1. Direct whole-phrase match across native scripts, Latin transliteration & aliases
        val phraseMatch = matchPhrase(trimmed, from, to)
        if (phraseMatch != null) return phraseMatch

        // 2. Sliding window n-gram translation (e.g. "नमस्ते भाई कैसे हो" -> "Hello brother How are you")
        val windowResult = translateSlidingWindow(trimmed, from, to)
        if (windowResult != null) return windowResult

        // 3. Token-level semantic word replacement
        return translateTokens(trimmed, from, to)
    }

    private fun matchPhrase(input: String, from: Language, to: Language): String? {
        val normInput = normalize(input)
        val latinInput = toPhoneticLatin(input, from)

        // Pass 1: Exact matches
        for (entry in PHRASE_DATABASE) {
            if (entry.matchesExact(normInput, latinInput, from)) {
                return entry.getText(to)
            }
        }

        // Pass 2: Token-set full containment (all phrase tokens present in input or vice versa)
        val inputTokens = tokenize(input)
        for (entry in PHRASE_DATABASE) {
            if (entry.matchesTokens(inputTokens, latinInput, from)) {
                return entry.getText(to)
            }
        }

        return null
    }

    private fun translateSlidingWindow(input: String, from: Language, to: Language): String? {
        val words = input.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) return null

        val resultWords = mutableListOf<String>()
        var i = 0
        var anyMatched = false

        while (i < words.size) {
            var matched = false
            val maxLen = minOf(5, words.size - i)

            for (len in maxLen downTo 2) {
                val subPhrase = words.subList(i, i + len).joinToString(" ")
                val normSub = normalize(subPhrase)
                val latinSub = toPhoneticLatin(subPhrase, from)

                for (entry in PHRASE_DATABASE) {
                    if (entry.matchesExact(normSub, latinSub, from)) {
                        resultWords.add(entry.getText(to))
                        i += len
                        matched = true
                        anyMatched = true
                        break
                    }
                }
                if (matched) break
            }

            if (!matched) {
                val singleWord = words[i]
                val cleanWord = singleWord.replace(Regex("[^\\p{L}\\p{M}\\p{Nd}]"), "").lowercase(Locale.ROOT)
                val translated = translateSingleWord(cleanWord, from, to) ?: cleanWord
                resultWords.add(preservePunctuation(singleWord, translated))
                i++
            }
        }

        return if (anyMatched) resultWords.joinToString(" ") else null
    }

    private fun translateTokens(input: String, from: Language, to: Language): String {
        val tokens = input.split(Regex("\\s+")).filter { it.isNotBlank() }
        val translatedTokens = tokens.map { rawToken ->
            val cleanToken = rawToken.replace(Regex("[^\\p{L}\\p{M}\\p{Nd}]"), "").lowercase(Locale.ROOT)
            val translatedWord = translateSingleWord(cleanToken, from, to)
                ?: transliterateWord(cleanToken, from, to)

            preservePunctuation(rawToken, translatedWord)
        }
        return translatedTokens.joinToString(" ")
    }

    private fun translateSingleWord(cleanToken: String, from: Language, to: Language): String? {
        val norm = normalize(cleanToken)
        if (norm.isEmpty()) return null
        val latin = toPhoneticLatin(cleanToken, from)

        // 1. Direct dictionary lookup by key
        DICTIONARY[cleanToken]?.get(to)?.let { return it }

        // 2. Lookup across all dictionary entries by language or phonetic alias
        for ((key, langMap) in DICTIONARY) {
            val srcWord = langMap[from]
            if (srcWord != null) {
                if (normalize(srcWord) == norm || toPhoneticLatin(srcWord, from) == latin) {
                    return langMap[to]
                }
            }
            if (normalize(key) == norm || normalize(key) == latin) {
                return langMap[to]
            }
        }

        return null
    }

    private fun preservePunctuation(original: String, translated: String): String {
        return if (original.endsWith(".") || original.endsWith("।") || original.endsWith("!")) {
            "$translated."
        } else if (original.endsWith("?")) {
            "$translated?"
        } else if (original.endsWith(",")) {
            "$translated,"
        } else {
            translated
        }
    }

    private fun tokenize(str: String): Set<String> {
        return str.lowercase(Locale.ROOT)
            .split(Regex("[\\p{Punct}\\s।॥]+"))
            .filter { it.isNotBlank() }
            .map { normalize(it) }
            .toSet()
    }

    private fun toPhoneticLatin(str: String, from: Language): String {
        if (from == Language.ENGLISH) return normalize(str)
        val devanagari = if (from != Language.HINDI && from != Language.MARATHI) {
            IndicTransliteration.indicToIndic(str, from, Language.HINDI)
        } else str
        return normalize(IndicTransliteration.indicToLatin(devanagari))
    }

    private fun normalize(str: String): String {
        return str.lowercase(Locale.ROOT)
            .replace(Regex("[\\p{Punct}\\s।॥]"), "")
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("oo", "u")
            .replace("sh", "s")
            .replace("jh", "z")
            .replace("dh", "d")
            .replace("th", "t")
            .replace("kh", "k")
            .replace("bh", "b")
            .replace("ch", "c")
            .replace("v", "w")
            .trim()
    }

    private fun transliterateWord(word: String, from: Language, to: Language): String {
        if (word.isBlank()) return word
        if (from == Language.ENGLISH && to == Language.ENGLISH) return word
        if (from != Language.ENGLISH && to == Language.ENGLISH) {
            return IndicTransliteration.indicToLatin(word)
        }
        if (from == Language.ENGLISH && to != Language.ENGLISH) {
            return word
        }
        return IndicTransliteration.indicToIndic(word, from, to)
    }

    private class PhraseEntry(
        val translations: Map<Language, String>,
        val aliases: List<String> = emptyList()
    ) {
        fun getText(language: Language): String = translations[language] ?: translations[Language.ENGLISH] ?: ""

        fun matchesExact(normInput: String, latinInput: String, from: Language): Boolean {
            // Check native translation string
            val src = translations[from]?.let { normalizeText(it) }
            if (src != null && src == normInput) return true

            // Check across all translations in any language
            for ((_, text) in translations) {
                val norm = normalizeText(text)
                if (norm == normInput) return true
            }

            // Check all aliases
            for (alias in aliases) {
                val normAlias = normalizeText(alias)
                if (normAlias == normInput || normAlias == latinInput) {
                    return true
                }
            }
            return false
        }

        fun matchesTokens(inputTokens: Set<String>, latinInput: String, from: Language): Boolean {
            if (inputTokens.isEmpty()) return false

            // Check if all phrase tokens match input
            for ((_, text) in translations) {
                val phraseTokens = text.lowercase(Locale.ROOT)
                    .split(Regex("[\\p{Punct}\\s।॥]+"))
                    .filter { it.isNotBlank() }
                    .map { normalizeText(it) }
                    .toSet()
                if (phraseTokens.isNotEmpty() && (phraseTokens == inputTokens || (phraseTokens.size >= 2 && inputTokens.containsAll(phraseTokens)))) {
                    return true
                }
            }

            for (alias in aliases) {
                val aliasTokens = alias.lowercase(Locale.ROOT)
                    .split(Regex("[\\p{Punct}\\s।॥]+"))
                    .filter { it.isNotBlank() }
                    .map { normalizeText(it) }
                    .toSet()
                if (aliasTokens.isNotEmpty() && (aliasTokens == inputTokens || (aliasTokens.size >= 2 && inputTokens.containsAll(aliasTokens)))) {
                    return true
                }
                val normAlias = normalizeText(alias)
                if (normAlias.length >= 5 && (normAlias == latinInput || (latinInput.length >= normAlias.length && latinInput.contains(normAlias)))) {
                    return true
                }
            }
            return false
        }

        private fun normalizeText(str: String): String {
            return str.lowercase(Locale.ROOT)
                .replace(Regex("[\\p{Punct}\\s।॥]"), "")
                .replace("aa", "a")
                .replace("ee", "i")
                .replace("oo", "u")
                .replace("sh", "s")
                .replace("jh", "z")
                .replace("dh", "d")
                .replace("th", "t")
                .replace("kh", "k")
                .replace("bh", "b")
                .replace("ch", "c")
                .replace("v", "w")
                .trim()
        }
    }

    companion object {
        private val PHRASE_DATABASE: List<PhraseEntry> = listOf(
            // Greetings & Conversation
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "How are you",
                    Language.HINDI to "आप कैसे हैं",
                    Language.MARATHI to "कसे आहात",
                    Language.GUJARATI to "કેમ છો",
                    Language.TAMIL to "எப்படி இருக்கிறீர்கள்",
                    Language.TELUGU to "ఎలా ఉన్నారు",
                    Language.KANNADA to "ಹೇಗಿದ್ದೀರ",
                    Language.MALAYALAM to "സുഖമാണോ",
                    Language.BENGALI to "কেমন আছেন",
                    Language.ODIA to "କେମିତି ଅଛନ୍ତି"
                ),
                aliases = listOf(
                    "kaise ho", "kaisa hai", "kaisa ho", "kaise hain", "aap kaise ho", "tum kaise ho", "kaise ho aap",
                    "kasa ahes", "kase aahat", "kase ahat", "kasa hai", "kasa ahe", "kasa kay", "kase kay", "kashi ahes",
                    "kem cho", "kemcho", "how are you", "how are u", "how r u", "how you doing",
                    "eppadi irukkeenga", "eppadi irukinga", "ela unnaru", "hegiddira", "hegidhdhira",
                    "kemon acho", "kemon achen", "sukhamano", "kemiti achanti",
                    "कैसे हो", "कैसा है", "आप कैसे हैं", "तुम कैसे हो", "कैसे हैं",
                    "कसे आहात", "कसा आहेस", "कसा आहे", "कशी आहेस", "काय चाललंय",
                    "કેમ છો", "તમે કેમ છો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "I am fine",
                    Language.HINDI to "मैं ठीक हूँ",
                    Language.MARATHI to "मी ठीक आहे",
                    Language.GUJARATI to "હું મજામાં છું",
                    Language.TAMIL to "நான் நலமாக இருக்கிறேன்",
                    Language.TELUGU to "నేను బాగున్నాను",
                    Language.KANNADA to "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ",
                    Language.MALAYALAM to "എനിക്ക് സുഖമാണ്",
                    Language.BENGALI to "আমি ভালো আছি",
                    Language.ODIA to "ମୁଁ ଭଲ ଅଛି"
                ),
                aliases = listOf(
                    "main theek hoon", "main theek hu", "mai theek hu", "theek hu", "theek hoon", "thik hu",
                    "mi theek ahe", "mi chaan ahe", "mi majet ahe", "hu majama chu", "hu majama",
                    "i am fine", "i am good", "im fine", "im good", "all good", "doing well",
                    "मैं ठीक हूँ", "सब ठीक है", "मी ठीक आहे", "હું મજામાં છું"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "What is your name",
                    Language.HINDI to "आपका नाम क्या है",
                    Language.MARATHI to "तुमचे नाव काय आहे",
                    Language.GUJARATI to "તમારું નામ શું છે",
                    Language.TAMIL to "உங்கள் பெயர் என்ன",
                    Language.TELUGU to "మీ పేరు ఏమిటి",
                    Language.KANNADA to "ನಿಮ್ಮ ಹೆಸರೇನು",
                    Language.MALAYALAM to "നിങ്ങളുടെ പേരെന്താണ്",
                    Language.BENGALI to "আপনার নাম কী",
                    Language.ODIA to "ଆପଣଙ୍କ ନାମ କଣ"
                ),
                aliases = listOf(
                    "aapka naam kya hai", "tera naam kya hai", "tumhara naam kya hai", "naam kya hai",
                    "tumche naav kay ahe", "tujhe naav kay", "tamaru naam shu che", "what is your name", "whats your name",
                    "आपका नाम क्या है", "तुमचे नाव काय आहे", "તમારું નામ શું છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "What happened",
                    Language.HINDI to "क्या हुआ",
                    Language.MARATHI to "काय झाले",
                    Language.GUJARATI to "શું થયું",
                    Language.TAMIL to "என்ன நடந்தது",
                    Language.TELUGU to "ఏమైంది",
                    Language.KANNADA to "ಏನಾಯಿತು",
                    Language.MALAYALAM to "എന്താണ് സംഭവിച്ചത്",
                    Language.BENGALI to "কী হয়েছে",
                    Language.ODIA to "କଣ ହେଲା"
                ),
                aliases = listOf(
                    "kya hua", "kya ho gaya", "kay zale", "kay jhala", "shu thayu", "what happened", "what happened here",
                    "क्या हुआ", "क्या हो गया", "काय झाले", "काय झालं", "શું થયું"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "What are you doing",
                    Language.HINDI to "आप क्या कर रहे हैं",
                    Language.MARATHI to "तुम्ही काय करत आहात",
                    Language.GUJARATI to "તમે શું કરો છો",
                    Language.TAMIL to "நீங்கள் என்ன செய்கிறீர்கள்",
                    Language.TELUGU to "మీరు ఏమి చేస్తున్నారు",
                    Language.KANNADA to "ನೀವು ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ",
                    Language.MALAYALAM to "നിങ്ങൾ എന്താണ് ചെയ്യുന്നത്",
                    Language.BENGALI to "আপনি কি করছেন",
                    Language.ODIA to "ଆପଣ କଣ କରୁଛନ୍ତି"
                ),
                aliases = listOf(
                    "kya kar rahe ho", "kya kar rahe ho aap", "aap kya kar rahe hain",
                    "kay karat ahat", "kay kartoy", "tame shu karo cho", "what are you doing", "what are u doing",
                    "क्या कर रहे हो", "तुम्ही काय करत आहात", "શું કરો છો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Where are you",
                    Language.HINDI to "आप कहाँ हैं",
                    Language.MARATHI to "तुम्ही कुठे आहात",
                    Language.GUJARATI to "તમે ક્યાં છો",
                    Language.TAMIL to "நீங்கள் எங்கே இருக்கிறீர்கள்",
                    Language.TELUGU to "మీరు ఎక్కడ ఉన్నారు",
                    Language.KANNADA to "ನೀವು ಎಲ್ಲಿದ್ದೀರಿ",
                    Language.MALAYALAM to "നിങ്ങൾ എവിടെയാണ്",
                    Language.BENGALI to "আপনি কোথায় আছেন",
                    Language.ODIA to "ଆପଣ କେଉଁଠି ଅଛନ୍ତି"
                ),
                aliases = listOf(
                    "kahan ho", "kaha ho", "aap kaha ho", "tum kaha ho", "aap kahan hain",
                    "kuthe ahat", "kuthe ahes", "kya cho", "where are you", "where r u",
                    "कहाँ हो", "आप कहाँ हैं", "तुम्ही कुठे आहात", "તમે ક્યાં છો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Where are you going",
                    Language.HINDI to "आप कहाँ जा रहे हैं",
                    Language.MARATHI to "तुम्ही कुठे जात आहात",
                    Language.GUJARATI to "તમે ક્યાં જાઓ છો",
                    Language.TAMIL to "நீங்கள் எங்கே போகிறீர்கள்",
                    Language.TELUGU to "మీరు ఎక్కడికి వెళ్తున్నారు",
                    Language.KANNADA to "ನೀವು ಎಲ್ಲಿಗೆ ಹೋಗುತ್ತಿದ್ದೀರಿ",
                    Language.MALAYALAM to "നിങ്ങൾ എവിടെ പോകുന്നു",
                    Language.BENGALI to "আপনি কোথায় যাচ্ছেন",
                    Language.ODIA to "ଆପଣ କୁଆଡ଼େ ଯାଉଛନ୍ତି"
                ),
                aliases = listOf(
                    "kahan ja rahe ho", "kaha ja rahe ho", "aap kahan ja rahe hain",
                    "kuthe jat ahat", "kuthe challas", "where are you going",
                    "कहाँ जा रहे हो", "तुम्ही कुठे जात आहात"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Come here",
                    Language.HINDI to "यहाँ आओ",
                    Language.MARATHI to "इथे या",
                    Language.GUJARATI to "અહીં આવો",
                    Language.TAMIL to "இங்கே வாருங்கள்",
                    Language.TELUGU to "ఇక్కడికి రండి",
                    Language.KANNADA to "ಇಲ್ಲಿಗೆ ಬನ್ನಿ",
                    Language.MALAYALAM to "ഇവിടെ വരൂ",
                    Language.BENGALI to "এখানে আসুন",
                    Language.ODIA to "ଏଠାକୁ ଆସନ୍ତୁ"
                ),
                aliases = listOf(
                    "yahan aao", "idhar aao", "yaha aao", "ithe ya", "ahi aavo", "come here",
                    "यहाँ आओ", "इधर आओ", "इथे या", "અહીં આવો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Go there",
                    Language.HINDI to "वहाँ जाओ",
                    Language.MARATHI to "तिथे जा",
                    Language.GUJARATI to "ત્યાં જાઓ",
                    Language.TAMIL to "அங்கே போங்கள்",
                    Language.TELUGU to "అక్కడికి వెళ్ళండి",
                    Language.KANNADA to "ಅಲ್ಲಿಗೆ ಹೋಗಿ",
                    Language.MALAYALAM to "അവിടെ പോകുക",
                    Language.BENGALI to "সেখানে যান",
                    Language.ODIA to "ସେଠାକୁ ଯାଆନ୍ତୁ"
                ),
                aliases = listOf(
                    "wahan jao", "udhar jao", "waha jao", "tithe ja", "tya jao", "go there",
                    "वहाँ जाओ", "उधर जाओ", "तिथे जा", "ત્યાં જાઓ"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Everything is fine",
                    Language.HINDI to "सब ठीक है",
                    Language.MARATHI to "सर्व काही ठीक आहे",
                    Language.GUJARATI to "બધું બરાબર છે",
                    Language.TAMIL to "எல்லாம் நலமே",
                    Language.TELUGU to "అంతా బాగుంది",
                    Language.KANNADA to "ಎಲ್ಲವೂ ಚೆನ್ನಾಗಿದೆ",
                    Language.MALAYALAM to "എല്ലാം ശരിയാണ്",
                    Language.BENGALI to "সব ঠিক আছে",
                    Language.ODIA to "ସବୁ ଠିକ ଅଛି"
                ),
                aliases = listOf(
                    "sab theek hai", "sab thik hai", "sarva theek ahe", "badhu barabar che", "everything is fine", "all is well",
                    "सब ठीक है", "सर्व काही ठीक आहे", "બધું બરાબર છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Hello",
                    Language.HINDI to "नमस्ते",
                    Language.MARATHI to "नमस्कार",
                    Language.GUJARATI to "નમસ્તે",
                    Language.TAMIL to "வணக்கம்",
                    Language.TELUGU to "నమస్కారం",
                    Language.KANNADA to "ನಮಸ್ಕಾರ",
                    Language.MALAYALAM to "നമസ്കാരം",
                    Language.BENGALI to "নমস্কার",
                    Language.ODIA to "ନମସ୍କାର"
                ),
                aliases = listOf(
                    "namaste", "namaskar", "namaskaram", "hello", "hi", "hey",
                    "नमस्ते", "नमस्कार", "નમસ્તે", "வணக்கம்", "నమస్కారం"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Thank you",
                    Language.HINDI to "धन्यवाद",
                    Language.MARATHI to "धन्यवाद",
                    Language.GUJARATI to "આભાર",
                    Language.TAMIL to "நன்றி",
                    Language.TELUGU to "ధన్యవాదాలు",
                    Language.KANNADA to "ಧನ್ಯವಾದಗಳು",
                    Language.MALAYALAM to "നന്ദി",
                    Language.BENGALI to "ধন্যবাদ",
                    Language.ODIA to "ଧନ୍ୟବାଦ"
                ),
                aliases = listOf(
                    "dhanyavad", "shukriya", "aabhar", "nandri", "thank you", "thanks",
                    "धन्यवाद", "शुक्रिया", "આભાર", "நன்றி"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Please",
                    Language.HINDI to "कृपया",
                    Language.MARATHI to "कृपया",
                    Language.GUJARATI to "કૃપા કરીને",
                    Language.TAMIL to "தயவுசெய்து",
                    Language.TELUGU to "దయచేసి",
                    Language.KANNADA to "ದಯವಿಟ್ಟು",
                    Language.MALAYALAM to "ദയവായി",
                    Language.BENGALI to "দয়া করে",
                    Language.ODIA to "ଦୟାକରି"
                ),
                aliases = listOf("kripya", "krupaya", "please", "plz", "कृपया", "કૃપા કરીને")
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Hurry up",
                    Language.HINDI to "जल्दी करो",
                    Language.MARATHI to "लवकर करा",
                    Language.GUJARATI to "ઝડપ કરો",
                    Language.TAMIL to "சீக்கிரம் செய்யுங்கள்",
                    Language.TELUGU to "త్వరగా చేయండి",
                    Language.KANNADA to "ಬೇಗ ಮಾಡಿ",
                    Language.MALAYALAM to "വേഗം വരൂ",
                    Language.BENGALI to "তাড়াতাড়ি করুন",
                    Language.ODIA to "ଶୀଘ୍ର କରନ୍ତୁ"
                ),
                aliases = listOf(
                    "jaldi karo", "jaldi aao", "lavkar kara", "hurry up", "fast", "come fast",
                    "जल्दी करो", "जल्दी आओ", "लवकर करा", "ઝડપ કરો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Stop",
                    Language.HINDI to "रुको",
                    Language.MARATHI to "थांबा",
                    Language.GUJARATI to "ઊભા રહો",
                    Language.TAMIL to "நில்லுங்கள்",
                    Language.TELUGU to "ఆగండి",
                    Language.KANNADA to "ನಿಲ್ಲಿಸಿ",
                    Language.MALAYALAM to "നിൽക്കൂ",
                    Language.BENGALI to "থামুন",
                    Language.ODIA to "ଅଟକନ୍ତୁ"
                ),
                aliases = listOf(
                    "ruko", "ruk jao", "thamba", "stop", "wait", "hold on",
                    "रुको", "रुक जाओ", "थांबा", "ઊભા રહો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Listen",
                    Language.HINDI to "सुनो",
                    Language.MARATHI to "ऐका",
                    Language.GUJARATI to "સાંભળો",
                    Language.TAMIL to "கேளுங்கள்",
                    Language.TELUGU to "వినండి",
                    Language.KANNADA to "ಕೇಳಿ",
                    Language.MALAYALAM to "കേൾക്കൂ",
                    Language.BENGALI to "শুনুন",
                    Language.ODIA to "ଶୁଣନ୍ତୁ"
                ),
                aliases = listOf(
                    "suno", "suniye", "aika", "sambhalo", "listen",
                    "सुनो", "सुनिए", "ऐका", "સાંભળો"
                )
            ),

            // Emergency & Disaster Phrases
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Flood water is rising",
                    Language.HINDI to "पानी बढ़ रहा है",
                    Language.GUJARATI to "પાણી વધી રહ્યું છે",
                    Language.MARATHI to "पाणी वाढत आहे",
                    Language.KANNADA to "ನೀರು ಏರುತ್ತಿದೆ",
                    Language.MALAYALAM to "വെള്ളം ഉയരുന്നു",
                    Language.TAMIL to "தண்ணீர் உயர்கிறது",
                    Language.TELUGU to "నీళ్ళు పెరుగుతున్నాయి",
                    Language.ODIA to "ପାଣି ବଢ଼ୁଛି",
                    Language.BENGALI to "জল বাড়ছে"
                ),
                aliases = listOf(
                    "pani badh raha hai", "paani badh raha hai", "pani vadhat ahe", "flood water is rising", "water rising", "water is rising",
                    "पानी बढ़ रहा है", "पाणी वाढत आहे", "પાણી વધી રહ્યું છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Move to high ground",
                    Language.HINDI to "ऊँची जगह जाएँ",
                    Language.GUJARATI to "ઊંચી જગ્યાએ જાઓ",
                    Language.MARATHI to "उंच ठिकाणी जा",
                    Language.KANNADA to "ಎತ್ತರದ ಸ್ಥಳಕ್ಕೆ ಹೋಗಿ",
                    Language.MALAYALAM to "ഉയർന്ന സ്ഥലത്തേക്ക് പോകൂ",
                    Language.TAMIL to "உயரமான இடத்திற்கு செல்லுங்கள்",
                    Language.TELUGU to "ఎత్తైన ప్రదేశానికి వెళ్ళండి",
                    Language.ODIA to "ଉଚ୍ଚ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ",
                    Language.BENGALI to "উঁচু জায়গায় যান"
                ),
                aliases = listOf(
                    "oonchi jagah jaye", "unchi jagah jao", "unch thikani ja", "move to high ground", "go to high ground",
                    "ऊँची जगह जाएँ", "उंच ठिकाणी जा", "ઊંચી જગ્યાએ જાઓ"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Bridge has collapsed",
                    Language.HINDI to "पुल टूट गया है",
                    Language.GUJARATI to "પુલ તૂટી ગયો છે",
                    Language.MARATHI to "पूल तुटला आहे",
                    Language.KANNADA to "ಸೇತುವೆ ಕುಸಿದಿದೆ",
                    Language.MALAYALAM to "പാലം തകർന്നു",
                    Language.TAMIL to "பாலம் உடைந்தது",
                    Language.TELUGU to "వంతెన కూలిపోయింది",
                    Language.ODIA to "ପୋଲ ଭାଙ୍ଗିଯାଇଛି",
                    Language.BENGALI to "সেতু ভেঙে পড়েছে"
                ),
                aliases = listOf(
                    "pul toot gaya", "pul tut gaya hai", "pool tutla ahe", "bridge collapsed", "bridge has collapsed",
                    "पुल टूट गया है", "पूल तुटला आहे", "પુલ તૂટી ગયો છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "We need help",
                    Language.HINDI to "हमें मदद चाहिए",
                    Language.GUJARATI to "અમને મદદ જોઈએ છે",
                    Language.MARATHI to "आम्हाला मदत हवी आहे",
                    Language.KANNADA to "ನಮಗೆ ಸಹಾಯ ಬೇಕು",
                    Language.MALAYALAM to "ഞങ്ങൾക്ക് സഹായം വേണം",
                    Language.TAMIL to "எங்களுக்கு உதவி தேவை",
                    Language.TELUGU to "మాకు సహాయం కావాలి",
                    Language.ODIA to "ଆମକୁ ସାହାଯ୍ୟ ଦରକାର",
                    Language.BENGALI to "আমাদের সাহায্য দরকার"
                ),
                aliases = listOf(
                    "hume madad chahiye", "madad chahiye", "madad karo", "help me", "we need help", "need help", "please help",
                    "mala madat havi ahe", "amhi madat magat ahot", "madat kara",
                    "हमें मदद चाहिए", "मदद चाहिए", "मदद करो", "आम्हाला मदत हवी आहे", "मला मदत हवी आहे", "અમને મદદ જોઈએ છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "We are safe",
                    Language.HINDI to "हम सुरक्षित हैं",
                    Language.GUJARATI to "અમે સુરક્ષિત છીએ",
                    Language.MARATHI to "आम्ही सुरक्षित आहोत",
                    Language.KANNADA to "ನಾವು ಸುರಕ್ಷಿತವಾಗಿದ್ದೇವೆ",
                    Language.MALAYALAM to "ഞങ്ങൾ സുരക്ഷിതരാണ്",
                    Language.TAMIL to "நாங்கள் பாதுகாப்பாக இருக்கிறோம்",
                    Language.TELUGU to "మేము క్షేమంగా ఉన్నాము",
                    Language.ODIA to "ଆମେ ସୁରକ୍ଷିତ ଅଛୁ",
                    Language.BENGALI to "আমরা নিরাপদ আছি"
                ),
                aliases = listOf(
                    "hum surakshit hain", "amhi surakshit ahot", "we are safe", "im safe", "all safe",
                    "हम सुरक्षित हैं", "आम्ही सुरक्षित आहोत", "અમે સુરક્ષિત છીએ"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Road is blocked",
                    Language.HINDI to "रास्ता बंद है",
                    Language.GUJARATI to "રસ્તો બંધ છે",
                    Language.MARATHI to "रस्ता बंद आहे",
                    Language.KANNADA to "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ",
                    Language.MALAYALAM to "വഴി അടഞ്ഞിരിക്കുന്നു",
                    Language.TAMIL to "பாதை மூடப்பட்டுள்ளது",
                    Language.TELUGU to "రహదారి మూసివేయబడింది",
                    Language.ODIA to "ରାସ୍ତା ବନ୍ଦ ଅଛି",
                    Language.BENGALI to "রাস্তা বন্ধ আছে"
                ),
                aliases = listOf(
                    "rasta band hai", "rasta band ahe", "road is blocked", "road blocked",
                    "रास्ता बंद है", "रस्ता बंद आहे", "રસ્તો બંધ છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "People are trapped here",
                    Language.HINDI to "यहाँ लोग फँसे हैं",
                    Language.GUJARATI to "અહીં લોકો ફસાયા છે",
                    Language.MARATHI to "येथे लोक अडकले आहेत",
                    Language.KANNADA to "ಇಲ್ಲಿ ಜನರು ಸಿಲುಕಿಕೊಂಡಿದ್ದಾರೆ",
                    Language.MALAYALAM to "ഇവിടെ ആളുകൾ കുടുങ്ങിയിട്ടുണ്ട്",
                    Language.TAMIL to "இங்கே மக்கள் சிக்கியுள்ளனர்",
                    Language.TELUGU to "ఇక్కడ ప్రజలు చిక్కుకున్నారు",
                    Language.ODIA to "ଏଠାରେ ଲୋକମାନେ ଫସି ରହିଛନ୍ତି",
                    Language.BENGALI to "এখানে মানুষ আটকে আছে"
                ),
                aliases = listOf(
                    "yahan log fanse hain", "ithe lok adakle ahet", "people trapped", "trapped here",
                    "यहाँ लोग फँसे हैं", "येथे लोक अडकले आहेत", "અહીં લોકો ફસાયા છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Medical help needed",
                    Language.HINDI to "चिकित्सा सहायता चाहिए",
                    Language.GUJARATI to "તબીબી સહાય જોઈએ છે",
                    Language.MARATHI to "वैद्यकीय मदत हवी आहे",
                    Language.KANNADA to "ವೈದ್ಯಕೀಯ ಸಹಾಯ ಬೇಕು",
                    Language.MALAYALAM to "വൈദ്യസഹായം വേണം",
                    Language.TAMIL to "மருத்துவ உதவி தேவை",
                    Language.TELUGU to "వైద్య సహాయం కావాలి",
                    Language.ODIA to "ଡାକ୍ତରୀ ସାହାଯ୍ୟ ଦରକାର",
                    Language.BENGALI to "চিকিৎসা সাহায্য দরকার"
                ),
                aliases = listOf(
                    "doctor chahiye", "medical help needed", "dawa chahiye", "need doctor",
                    "चिकित्सा सहायता चाहिए", "डॉक्टर चाहिए", "वैद्यकीय मदत हवी आहे", "તબીબી સહાય જોઈએ છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Food and water needed",
                    Language.HINDI to "भोजन और पानी चाहिए",
                    Language.GUJARATI to "ખોરાક અને પાણી જોઈએ છે",
                    Language.MARATHI to "अन्न आणि पाणी हवे आहे",
                    Language.KANNADA to "ಆಹಾರ ಮತ್ತು ನೀರು ಬೇಕು",
                    Language.MALAYALAM to "ഭക്ഷണവും വെള്ളവും വേണം",
                    Language.TAMIL to "உணவும் தண்ணீரும் தேவை",
                    Language.TELUGU to "ఆహారం మరియు నీరు కావాలి",
                    Language.ODIA to "ଖାଦ୍ୟ ଏବଂ ପାଣି ଦରକାର",
                    Language.BENGALI to "খাবার এবং জল দরকার"
                ),
                aliases = listOf(
                    "khana aur pani chahiye", "food and water needed", "anna aani pani have ahe",
                    "भोजन और पानी चाहिए", "अन्न आणि पाणी हवे आहे", "ખોરાક અને પાણી જોઈએ છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Send a boat",
                    Language.HINDI to "नाव भेजो",
                    Language.GUJARATI to "હોડી મોકલો",
                    Language.MARATHI to "नाव पाठवा",
                    Language.KANNADA to "ದೋಣಿ ಕಳುಹಿಸಿ",
                    Language.MALAYALAM to "ബോട്ട് അയക്കൂ",
                    Language.TAMIL to "படகு அனுப்புங்கள்",
                    Language.TELUGU to "పడవ పంపండి",
                    Language.ODIA to "ଡଙ୍ଗା ପଠାନ୍ତୁ",
                    Language.BENGALI to "নৌকা পাঠান"
                ),
                aliases = listOf(
                    "naav bhejo", "boat bhejo", "naav pathva", "send a boat", "send boat",
                    "नाव भेजो", "नाव पाठवा", "હોડી મોકલો"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Danger ahead",
                    Language.HINDI to "आगे खतरा है",
                    Language.GUJARATI to "આગળ ભય છે",
                    Language.MARATHI to "पुढे धोका आहे",
                    Language.KANNADA to "ಮುಂದೆ ಅಪಾಯವಿದೆ",
                    Language.MALAYALAM to "മുന്നിൽ അപകടമുണ്ട്",
                    Language.TAMIL to "முன்னால் ஆபத்து",
                    Language.TELUGU to "ముందు ప్రమాదం ఉంది",
                    Language.ODIA to "ଆଗରେ ବିପଦ ଅଛି",
                    Language.BENGALI to "সামনে বিপদ"
                ),
                aliases = listOf(
                    "aage khatra hai", "pudhe dhoka ahe", "danger ahead",
                    "आगे खतरा है", "पुढे धोका आहे", "આગળ ભય છે"
                )
            ),
            PhraseEntry(
                translations = mapOf(
                    Language.ENGLISH to "Rescue team is coming",
                    Language.HINDI to "बचाव दल आ रहा है",
                    Language.GUJARATI to "બચાવ ટુકડી આવી રહી છે",
                    Language.MARATHI to "बचाव पथक येत आहे",
                    Language.KANNADA to "ರಕ್ಷಣಾ ತಂಡ ಬರುತ್ತಿದೆ",
                    Language.MALAYALAM to "രക്ഷാസംഘം വരുന്നു",
                    Language.TAMIL to "மீட்புக்குழு வருகிறது",
                    Language.TELUGU to "రక్షణ బృందం వస్తోంది",
                    Language.ODIA to "ଉଦ୍ଧାରକାରୀ ଦଳ ଆସୁଛି",
                    Language.BENGALI to "উদ্ধারকারী দল আসছে"
                ),
                aliases = listOf(
                    "bachav dal aa raha hai", "rescue team coming", "rescue team is coming",
                    "बचाव दल आ रहा है", "बचाव पथक येत आहे", "બચાવ ટુકડી આવી રહી છે"
                )
            )
        )

        private val DICTIONARY: Map<String, Map<Language, String>> = mapOf(
            // Question words
            "kaise" to mapOf(
                Language.ENGLISH to "how",
                Language.HINDI to "कैसे",
                Language.MARATHI to "कसे",
                Language.GUJARATI to "કેમ",
                Language.TAMIL to "எப்படி",
                Language.TELUGU to "ఎలా",
                Language.KANNADA to "ಹೇಗೆ",
                Language.MALAYALAM to "എങ്ങനെ",
                Language.BENGALI to "কেমন",
                Language.ODIA to "କେମିତି"
            ),
            "kaisa" to mapOf(
                Language.ENGLISH to "how",
                Language.HINDI to "कैसा",
                Language.MARATHI to "कसा",
                Language.GUJARATI to "કેવો",
                Language.TAMIL to "எப்படி",
                Language.TELUGU to "ఎలా",
                Language.KANNADA to "ಹೇಗೆ",
                Language.MALAYALAM to "എങ്ങനെ",
                Language.BENGALI to "কেমন",
                Language.ODIA to "କେମିତି"
            ),
            "kasa" to mapOf(
                Language.ENGLISH to "how",
                Language.HINDI to "कैसा",
                Language.MARATHI to "कसा",
                Language.GUJARATI to "કેવો",
                Language.TAMIL to "எப்படி",
                Language.TELUGU to "ఎలా",
                Language.KANNADA to "ಹೇಗೆ",
                Language.MALAYALAM to "എങ്ങനെ",
                Language.BENGALI to "কেমন",
                Language.ODIA to "କେମିତି"
            ),
            "how" to mapOf(
                Language.ENGLISH to "how",
                Language.HINDI to "कैसे",
                Language.MARATHI to "कसे",
                Language.GUJARATI to "કેમ",
                Language.TAMIL to "எப்படி",
                Language.TELUGU to "ఎలా",
                Language.KANNADA to "ಹೇಗೆ",
                Language.MALAYALAM to "എങ്ങനെ",
                Language.BENGALI to "কেমন",
                Language.ODIA to "କେମିତି"
            ),
            "kya" to mapOf(
                Language.ENGLISH to "what",
                Language.HINDI to "क्या",
                Language.MARATHI to "काय",
                Language.GUJARATI to "શું",
                Language.TAMIL to "என்ன",
                Language.TELUGU to "ఏమిటి",
                Language.KANNADA to "ಏನು",
                Language.MALAYALAM to "എന്ത്",
                Language.BENGALI to "কী",
                Language.ODIA to "କଣ"
            ),
            "what" to mapOf(
                Language.ENGLISH to "what",
                Language.HINDI to "क्या",
                Language.MARATHI to "काय",
                Language.GUJARATI to "શું",
                Language.TAMIL to "என்ன",
                Language.TELUGU to "ఏమిటి",
                Language.KANNADA to "ಏನು",
                Language.MALAYALAM to "എന്ത്",
                Language.BENGALI to "কী",
                Language.ODIA to "କଣ"
            ),
            "kahan" to mapOf(
                Language.ENGLISH to "where",
                Language.HINDI to "कहाँ",
                Language.MARATHI to "कुठे",
                Language.GUJARATI to "ક્યાં",
                Language.TAMIL to "எங்கே",
                Language.TELUGU to "ఎక్కడ",
                Language.KANNADA to "ಎಲ್ಲಿ",
                Language.MALAYALAM to "എവിടെ",
                Language.BENGALI to "কোথায়",
                Language.ODIA to "କେଉଁଠି"
            ),
            "where" to mapOf(
                Language.ENGLISH to "where",
                Language.HINDI to "कहाँ",
                Language.MARATHI to "कुठे",
                Language.GUJARATI to "ક્યાં",
                Language.TAMIL to "எங்கே",
                Language.TELUGU to "ఎక్కడ",
                Language.KANNADA to "ಎಲ್ಲಿ",
                Language.MALAYALAM to "എവിടെ",
                Language.BENGALI to "কোথায়",
                Language.ODIA to "କେଉଁଠି"
            ),
            "kab" to mapOf(
                Language.ENGLISH to "when",
                Language.HINDI to "कब",
                Language.MARATHI to "केव्हा",
                Language.GUJARATI to "ક્યારે",
                Language.TAMIL to "எப்போது",
                Language.TELUGU to "ఎప్పుడు",
                Language.KANNADA to "ಯಾವಾಗ",
                Language.MALAYALAM to "എപ്പോൾ",
                Language.BENGALI to "কখন",
                Language.ODIA to "କେବେ"
            ),
            "when" to mapOf(
                Language.ENGLISH to "when",
                Language.HINDI to "कब",
                Language.MARATHI to "केव्हा",
                Language.GUJARATI to "ક્યારે",
                Language.TAMIL to "எப்போது",
                Language.TELUGU to "ఎప్పుడు",
                Language.KANNADA to "ಯಾವಾಗ",
                Language.MALAYALAM to "എപ്പോൾ",
                Language.BENGALI to "কখন",
                Language.ODIA to "କେବେ"
            ),
            "kyun" to mapOf(
                Language.ENGLISH to "why",
                Language.HINDI to "क्यों",
                Language.MARATHI to "का",
                Language.GUJARATI to "કેમ",
                Language.TAMIL to "ஏன்",
                Language.TELUGU to "ఎందుకు",
                Language.KANNADA to "ಏಕೆ",
                Language.MALAYALAM to "എന്തുകൊണ്ട്",
                Language.BENGALI to "কেন",
                Language.ODIA to "କାହିଁକି"
            ),
            "why" to mapOf(
                Language.ENGLISH to "why",
                Language.HINDI to "क्यों",
                Language.MARATHI to "का",
                Language.GUJARATI to "કેમ",
                Language.TAMIL to "ஏன்",
                Language.TELUGU to "ఎందుకు",
                Language.KANNADA to "ಏಕೆ",
                Language.MALAYALAM to "എന്തുകൊണ്ട്",
                Language.BENGALI to "কেন",
                Language.ODIA to "କାହିଁକି"
            ),
            "kaun" to mapOf(
                Language.ENGLISH to "who",
                Language.HINDI to "कौन",
                Language.MARATHI to "कोण",
                Language.GUJARATI to "કોણ",
                Language.TAMIL to "யார்",
                Language.TELUGU to "ఎవరు",
                Language.KANNADA to "ಯಾರು",
                Language.MALAYALAM to "ആര്",
                Language.BENGALI to "কে",
                Language.ODIA to "କିଏ"
            ),
            "who" to mapOf(
                Language.ENGLISH to "who",
                Language.HINDI to "कौन",
                Language.MARATHI to "कोण",
                Language.GUJARATI to "કોણ",
                Language.TAMIL to "யார்",
                Language.TELUGU to "ఎవరు",
                Language.KANNADA to "ಯಾರು",
                Language.MALAYALAM to "ആര്",
                Language.BENGALI to "কে",
                Language.ODIA to "କିଏ"
            ),

            // Pronouns
            "main" to mapOf(
                Language.ENGLISH to "I",
                Language.HINDI to "मैं",
                Language.MARATHI to "मी",
                Language.GUJARATI to "હું",
                Language.TAMIL to "நான்",
                Language.TELUGU to "నేను",
                Language.KANNADA to "ನಾನು",
                Language.MALAYALAM to "ഞാൻ",
                Language.BENGALI to "আমি",
                Language.ODIA to "ମୁଁ"
            ),
            "hum" to mapOf(
                Language.ENGLISH to "we",
                Language.HINDI to "हम",
                Language.MARATHI to "आम्ही",
                Language.GUJARATI to "અમે",
                Language.TAMIL to "நாங்கள்",
                Language.TELUGU to "మేము",
                Language.KANNADA to "ನಾವು",
                Language.MALAYALAM to "ഞങ്ങൾ",
                Language.BENGALI to "আমরা",
                Language.ODIA to "ଆମେ"
            ),
            "aap" to mapOf(
                Language.ENGLISH to "you",
                Language.HINDI to "आप",
                Language.MARATHI to "तुम्ही",
                Language.GUJARATI to "તમે",
                Language.TAMIL to "நீங்கள்",
                Language.TELUGU to "మీరు",
                Language.KANNADA to "ನೀವು",
                Language.MALAYALAM to "നിങ്ങൾ",
                Language.BENGALI to "আপনি",
                Language.ODIA to "ଆପଣ"
            ),
            "tum" to mapOf(
                Language.ENGLISH to "you",
                Language.HINDI to "तुम",
                Language.MARATHI to "तू",
                Language.GUJARATI to "તું",
                Language.TAMIL to "நீ",
                Language.TELUGU to "నువ్వు",
                Language.KANNADA to "ನೀನು",
                Language.MALAYALAM to "നീ",
                Language.BENGALI to "তুমি",
                Language.ODIA to "ତୁମେ"
            ),
            "you" to mapOf(
                Language.ENGLISH to "you",
                Language.HINDI to "आप",
                Language.MARATHI to "तुम्ही",
                Language.GUJARATI to "તમે",
                Language.TAMIL to "நீங்கள்",
                Language.TELUGU to "మీరు",
                Language.KANNADA to "ನೀವು",
                Language.MALAYALAM to "നിങ്ങൾ",
                Language.BENGALI to "আপনি",
                Language.ODIA to "ଆପଣ"
            ),

            // Verbs / Auxiliary
            "ho" to mapOf(
                Language.ENGLISH to "are",
                Language.HINDI to "हो",
                Language.MARATHI to "आहात",
                Language.GUJARATI to "છો",
                Language.TAMIL to "இருக்கிறீர்கள்",
                Language.TELUGU to "ఉన్నారు",
                Language.KANNADA to "ಇದ್ದೀರಿ",
                Language.MALAYALAM to "ആണ്",
                Language.BENGALI to "আছেন",
                Language.ODIA to "ଅଛନ୍ତି"
            ),
            "hai" to mapOf(
                Language.ENGLISH to "is",
                Language.HINDI to "है",
                Language.MARATHI to "आहे",
                Language.GUJARATI to "છે",
                Language.TAMIL to "இருக்கிறது",
                Language.TELUGU to "ఉంది",
                Language.KANNADA to "ಇದೆ",
                Language.MALAYALAM to "ആണ്",
                Language.BENGALI to "আছে",
                Language.ODIA to "ଅଛି"
            ),
            "hain" to mapOf(
                Language.ENGLISH to "are",
                Language.HINDI to "हैं",
                Language.MARATHI to "आहेत",
                Language.GUJARATI to "છે",
                Language.TAMIL to "இருக்கிறார்கள்",
                Language.TELUGU to "ఉన్నారు",
                Language.KANNADA to "ಇದ್ದಾರೆ",
                Language.MALAYALAM to "ആണ്",
                Language.BENGALI to "আছেন",
                Language.ODIA to "ଅଛନ୍ତି"
            ),
            "theek" to mapOf(
                Language.ENGLISH to "fine",
                Language.HINDI to "ठीक",
                Language.MARATHI to "ठीक",
                Language.GUJARATI to "સારું",
                Language.TAMIL to "நலமாக",
                Language.TELUGU to "బాగుంది",
                Language.KANNADA to "ಚೆನ್ನಾಗಿದೆ",
                Language.MALAYALAM to "നല്ലത്",
                Language.BENGALI to "ভালো",
                Language.ODIA to "ଭଲ"
            ),
            "fine" to mapOf(
                Language.ENGLISH to "fine",
                Language.HINDI to "ठीक",
                Language.MARATHI to "ठीक",
                Language.GUJARATI to "સારું",
                Language.TAMIL to "நலமாக",
                Language.TELUGU to "బాగుంది",
                Language.KANNADA to "ಚೆನ್ನಾಗಿದೆ",
                Language.MALAYALAM to "നല്ലത്",
                Language.BENGALI to "ভালো",
                Language.ODIA to "ଭଲ"
            ),
            "good" to mapOf(
                Language.ENGLISH to "good",
                Language.HINDI to "अच्छा",
                Language.MARATHI to "चांगले",
                Language.GUJARATI to "સારું",
                Language.TAMIL to "நல்ல",
                Language.TELUGU to "మంచి",
                Language.KANNADA to "ಒಳ್ಳೆಯದು",
                Language.MALAYALAM to "നല്ലത്",
                Language.BENGALI to "ভালো",
                Language.ODIA to "ଭଲ"
            ),
            "aao" to mapOf(
                Language.ENGLISH to "come",
                Language.HINDI to "आओ",
                Language.MARATHI to "या",
                Language.GUJARATI to "આવો",
                Language.TAMIL to "வாருங்கள்",
                Language.TELUGU to "రండి",
                Language.KANNADA to "ಬನ್ನಿ",
                Language.MALAYALAM to "വരൂ",
                Language.BENGALI to "আসুন",
                Language.ODIA to "ଆସନ୍ତୁ"
            ),
            "come" to mapOf(
                Language.ENGLISH to "come",
                Language.HINDI to "आओ",
                Language.MARATHI to "या",
                Language.GUJARATI to "આવો",
                Language.TAMIL to "வாருங்கள்",
                Language.TELUGU to "రండి",
                Language.KANNADA to "ಬನ್ನಿ",
                Language.MALAYALAM to "വരൂ",
                Language.BENGALI to "আসুন",
                Language.ODIA to "ଆସନ୍ତୁ"
            ),
            "jao" to mapOf(
                Language.ENGLISH to "go",
                Language.HINDI to "जाओ",
                Language.MARATHI to "जा",
                Language.GUJARATI to "જાઓ",
                Language.TAMIL to "போங்கள்",
                Language.TELUGU to "వెళ్ళండి",
                Language.KANNADA to "ಹೋಗಿ",
                Language.MALAYALAM to "പോകൂ",
                Language.BENGALI to "যান",
                Language.ODIA to "ଯାଆନ୍ତୁ"
            ),
            "go" to mapOf(
                Language.ENGLISH to "go",
                Language.HINDI to "जाओ",
                Language.MARATHI to "जा",
                Language.GUJARATI to "જાઓ",
                Language.TAMIL to "போங்கள்",
                Language.TELUGU to "వెళ్ళండి",
                Language.KANNADA to "ಹೋಗಿ",
                Language.MALAYALAM to "പോകൂ",
                Language.BENGALI to "যান",
                Language.ODIA to "ଯାଆନ୍ତୁ"
            ),
            "bolo" to mapOf(
                Language.ENGLISH to "speak",
                Language.HINDI to "बोलो",
                Language.MARATHI to "बोला",
                Language.GUJARATI to "બોલો",
                Language.TAMIL to "பேசுங்கள்",
                Language.TELUGU to "మాట్లాడండి",
                Language.KANNADA to "ಮಾತನಾಡಿ",
                Language.MALAYALAM to "സംസാരിക്കൂ",
                Language.BENGALI to "বলুন",
                Language.ODIA to "କୁହନ୍ତୁ"
            ),
            "suno" to mapOf(
                Language.ENGLISH to "listen",
                Language.HINDI to "सुनो",
                Language.MARATHI to "ऐका",
                Language.GUJARATI to "સાંભળો",
                Language.TAMIL to "கேளுங்கள்",
                Language.TELUGU to "వినండి",
                Language.KANNADA to "ಕೇಳಿ",
                Language.MALAYALAM to "കേൾക്കൂ",
                Language.BENGALI to "শুনুন",
                Language.ODIA to "ଶୁଣନ୍ତୁ"
            ),
            "listen" to mapOf(
                Language.ENGLISH to "listen",
                Language.HINDI to "सुनो",
                Language.MARATHI to "ऐका",
                Language.GUJARATI to "સાંભળો",
                Language.TAMIL to "கேளுங்கள்",
                Language.TELUGU to "వినండి",
                Language.KANNADA to "ಕೇಳಿ",
                Language.MALAYALAM to "കേൾക്കൂ",
                Language.BENGALI to "শুনুন",
                Language.ODIA to "ଶୁଣନ୍ତୁ"
            ),
            "madad" to mapOf(
                Language.ENGLISH to "help",
                Language.HINDI to "मदद",
                Language.MARATHI to "मदत",
                Language.GUJARATI to "મદદ",
                Language.TAMIL to "உதவி",
                Language.TELUGU to "సహాయం",
                Language.KANNADA to "ಸಹಾಯ",
                Language.MALAYALAM to "സഹായം",
                Language.BENGALI to "সাহায্য",
                Language.ODIA to "ସାହାଯ୍ୟ"
            ),
            "help" to mapOf(
                Language.ENGLISH to "help",
                Language.HINDI to "मदद",
                Language.MARATHI to "मदत",
                Language.GUJARATI to "મદદ",
                Language.TAMIL to "உதவி",
                Language.TELUGU to "సహాయం",
                Language.KANNADA to "ಸಹಾಯ",
                Language.MALAYALAM to "സഹായം",
                Language.BENGALI to "সাহায্য",
                Language.ODIA to "ସାହାଯ୍ୟ"
            ),
            "pani" to mapOf(
                Language.ENGLISH to "water",
                Language.HINDI to "पानी",
                Language.MARATHI to "पाणी",
                Language.GUJARATI to "પાણી",
                Language.TAMIL to "தண்ணீர்",
                Language.TELUGU to "నీరు",
                Language.KANNADA to "ನೀರು",
                Language.MALAYALAM to "വെള്ളം",
                Language.BENGALI to "জল",
                Language.ODIA to "ପାଣି"
            ),
            "water" to mapOf(
                Language.ENGLISH to "water",
                Language.HINDI to "पानी",
                Language.MARATHI to "पाणी",
                Language.GUJARATI to "પાણી",
                Language.TAMIL to "தண்ணீர்",
                Language.TELUGU to "నీరు",
                Language.KANNADA to "ನೀರು",
                Language.MALAYALAM to "വെള്ളം",
                Language.BENGALI to "জল",
                Language.ODIA to "ପାଣି"
            ),
            "khana" to mapOf(
                Language.ENGLISH to "food",
                Language.HINDI to "खाना",
                Language.MARATHI to "अन्न",
                Language.GUJARATI to "ખોરાક",
                Language.TAMIL to "உணவு",
                Language.TELUGU to "ఆహారం",
                Language.KANNADA to "ಆಹಾರ",
                Language.MALAYALAM to "ഭക്ഷണം",
                Language.BENGALI to "খাবার",
                Language.ODIA to "ଖାଦ୍ୟ"
            ),
            "food" to mapOf(
                Language.ENGLISH to "food",
                Language.HINDI to "खाना",
                Language.MARATHI to "अन्न",
                Language.GUJARATI to "ખોરાક",
                Language.TAMIL to "உணவு",
                Language.TELUGU to "ఆహారం",
                Language.KANNADA to "ಆಹಾರ",
                Language.MALAYALAM to "ഭക്ഷണം",
                Language.BENGALI to "খাবার",
                Language.ODIA to "ଖାଦ୍ୟ"
            ),
            "ghar" to mapOf(
                Language.ENGLISH to "home",
                Language.HINDI to "घर",
                Language.MARATHI to "घर",
                Language.GUJARATI to "ઘર",
                Language.TAMIL to "வீடு",
                Language.TELUGU to "ఇల్లు",
                Language.KANNADA to "ಮನೆ",
                Language.MALAYALAM to "വീട്",
                Language.BENGALI to "বাড়ি",
                Language.ODIA to "ଘର"
            ),
            "home" to mapOf(
                Language.ENGLISH to "home",
                Language.HINDI to "घर",
                Language.MARATHI to "घर",
                Language.GUJARATI to "ઘર",
                Language.TAMIL to "வீடு",
                Language.TELUGU to "ఇల్లు",
                Language.KANNADA to "ಮನೆ",
                Language.MALAYALAM to "വീട്",
                Language.BENGALI to "বাড়ি",
                Language.ODIA to "ଘର"
            ),
            "naam" to mapOf(
                Language.ENGLISH to "name",
                Language.HINDI to "नाम",
                Language.MARATHI to "नाव",
                Language.GUJARATI to "નામ",
                Language.TAMIL to "பெயர்",
                Language.TELUGU to "పేరు",
                Language.KANNADA to "ಹೆಸರು",
                Language.MALAYALAM to "പേര്",
                Language.BENGALI to "নাম",
                Language.ODIA to "ନାମ"
            ),
            "name" to mapOf(
                Language.ENGLISH to "name",
                Language.HINDI to "नाम",
                Language.MARATHI to "नाव",
                Language.GUJARATI to "નામ",
                Language.TAMIL to "பெயர்",
                Language.TELUGU to "పేరు",
                Language.KANNADA to "ಹೆಸರು",
                Language.MALAYALAM to "പേര്",
                Language.BENGALI to "নাম",
                Language.ODIA to "ନାମ"
            ),
            "dost" to mapOf(
                Language.ENGLISH to "friend",
                Language.HINDI to "दोस्त",
                Language.MARATHI to "मित्र",
                Language.GUJARATI to "મિત્ર",
                Language.TAMIL to "நண்பர்",
                Language.TELUGU to "స్నేహితుడు",
                Language.KANNADA to "ಸ್ನೇಹಿತ",
                Language.MALAYALAM to "സുഹൃത്ത്",
                Language.BENGALI to "বন্ধু",
                Language.ODIA to "ସାଙ୍ଗ"
            ),
            "bhai" to mapOf(
                Language.ENGLISH to "brother",
                Language.HINDI to "भाई",
                Language.MARATHI to "भाऊ",
                Language.GUJARATI to "ભાઈ",
                Language.TAMIL to "சகோதரர்",
                Language.TELUGU to "సోదరుడు",
                Language.KANNADA to "ಸಹೋದರ",
                Language.MALAYALAM to "സഹോദരൻ",
                Language.BENGALI to "ভাই",
                Language.ODIA to "ଭାଇ"
            ),
            "doctor" to mapOf(
                Language.ENGLISH to "doctor",
                Language.HINDI to "डॉक्टर",
                Language.MARATHI to "डॉक्टर",
                Language.GUJARATI to "ડૉક્ટર",
                Language.TAMIL to "மருத்துவர்",
                Language.TELUGU to "డాక్టర్",
                Language.KANNADA to "ವೈದ್ಯರು",
                Language.MALAYALAM to "ഡോക്ടർ",
                Language.BENGALI to "ডাক্তার",
                Language.ODIA to "ଡାକ୍ତର"
            ),
            "yes" to mapOf(
                Language.ENGLISH to "yes",
                Language.HINDI to "हाँ",
                Language.MARATHI to "हो",
                Language.GUJARATI to "હા",
                Language.TAMIL to "ஆம்",
                Language.TELUGU to "అవును",
                Language.KANNADA to "ಹೌದು",
                Language.MALAYALAM to "അതെ",
                Language.BENGALI to "হ্যাঁ",
                Language.ODIA to "ହଁ"
            ),
            "no" to mapOf(
                Language.ENGLISH to "no",
                Language.HINDI to "नहीं",
                Language.MARATHI to "नाही",
                Language.GUJARATI to "ના",
                Language.TAMIL to "இல்லை",
                Language.TELUGU to "కాదు",
                Language.KANNADA to "ಇಲ್ಲ",
                Language.MALAYALAM to "ഇല്ല",
                Language.BENGALI to "না",
                Language.ODIA to "ନା"
            )
        )
    }
}

/**
 * Phonetic script mapping and transliteration helper across Brahmi-derived Indic scripts and English.
 */
internal object IndicTransliteration {
    private val SCRIPT_BASES = mapOf(
        Language.HINDI to 0x0900,
        Language.MARATHI to 0x0900,
        Language.BENGALI to 0x0980,
        Language.GUJARATI to 0x0A80,
        Language.ODIA to 0x0B00,
        Language.TAMIL to 0x0B80,
        Language.TELUGU to 0x0C00,
        Language.KANNADA to 0x0C80,
        Language.MALAYALAM to 0x0D00
    )

    fun indicToIndic(text: String, from: Language, to: Language): String {
        val fromBase = SCRIPT_BASES[from] ?: return text
        val toBase = SCRIPT_BASES[to] ?: return text

        return buildString {
            for (ch in text) {
                val code = ch.code
                if (code in fromBase..(fromBase + 0x7F)) {
                    val offset = code - fromBase
                    append((toBase + offset).toChar())
                } else {
                    append(ch)
                }
            }
        }
    }

    fun indicToLatin(text: String): String {
        val map = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'क' to "k", 'ख' to "kh",
            'ग' to "g", 'घ' to "gh", 'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n", 'त' to "t",
            'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n", 'प' to "p", 'फ' to "ph",
            'ब' to "b", 'भ' to "bh", 'म' to "m", 'य' to "y", 'र' to "r", 'ल' to "l",
            'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
            'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
            'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", '्' to "",
            'ं' to "n", 'ँ' to "n", 'ः' to "h", '़' to ""
        )
        return buildString {
            for (c in text) {
                append(map[c] ?: c.toString())
            }
        }
    }
}
