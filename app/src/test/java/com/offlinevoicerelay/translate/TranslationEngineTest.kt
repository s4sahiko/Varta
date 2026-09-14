package com.offlinevoicerelay.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.offlinevoicerelay.model.Language
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationEngineTest {

    private lateinit var translationEngine: OfflineTranslationEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        translationEngine = OfflineTranslationEngine(context)
    }

    @Test
    fun testKaiseHoHinglishToEnglish() = runTest {
        val translated = translationEngine.translate(
            text = "kaise ho",
            from = Language.HINDI,
            to = Language.ENGLISH
        )
        assertThat(translated).isEqualTo("How are you")
    }

    @Test
    fun testKaiseHoHinglishToMarathi() = runTest {
        val translated = translationEngine.translate(
            text = "kaise ho",
            from = Language.HINDI,
            to = Language.MARATHI
        )
        assertThat(translated).isEqualTo("कसे आहात")
    }

    @Test
    fun testKasaHaiMarathiToEnglish() = runTest {
        val translated = translationEngine.translate(
            text = "kasa hai",
            from = Language.MARATHI,
            to = Language.ENGLISH
        )
        assertThat(translated).isEqualTo("How are you")
    }

    @Test
    fun testKasaHaiMarathiToHindi() = runTest {
        val translated = translationEngine.translate(
            text = "kasa hai",
            from = Language.MARATHI,
            to = Language.HINDI
        )
        assertThat(translated).isEqualTo("आप कैसे हैं")
    }

    @Test
    fun testHindiDevanagariToEnglish() = runTest {
        val translated = translationEngine.translate(
            text = "कैसे हो",
            from = Language.HINDI,
            to = Language.ENGLISH
        )
        assertThat(translated).isEqualTo("How are you")
    }

    @Test
    fun testHindiDevanagariToMarathi() = runTest {
        val translated = translationEngine.translate(
            text = "कैसे हो",
            from = Language.HINDI,
            to = Language.MARATHI
        )
        assertThat(translated).isEqualTo("कसे आहात")
    }

    @Test
    fun testEnglishToHindiAndMarathi() = runTest {
        val toHindi = translationEngine.translate(
            text = "How are you",
            from = Language.ENGLISH,
            to = Language.HINDI
        )
        assertThat(toHindi).isEqualTo("आप कैसे हैं")

        val toMarathi = translationEngine.translate(
            text = "How are you",
            from = Language.ENGLISH,
            to = Language.MARATHI
        )
        assertThat(toMarathi).isEqualTo("कसे आहात")
    }

    @Test
    fun testHindiToEnglishPhraseTranslation() = runTest {
        val translated = translationEngine.translate(
            text = "पानी बढ़ रहा है",
            from = Language.HINDI,
            to = Language.ENGLISH
        )
        assertThat(translated).isEqualTo("Flood water is rising")
    }

    @Test
    fun testEnglishToHindiPhraseTranslation() = runTest {
        val translated = translationEngine.translate(
            text = "We need help",
            from = Language.ENGLISH,
            to = Language.HINDI
        )
        assertThat(translated).isEqualTo("हमें मदद चाहिए")
    }

    @Test
    fun testHindiToMarathiPhraseTranslation() = runTest {
        val translated = translationEngine.translate(
            text = "पुल टूट गया है",
            from = Language.HINDI,
            to = Language.MARATHI
        )
        assertThat(translated).isEqualTo("पूल तुटला आहे")
    }

    @Test
    fun testEnglishToTamilPhraseTranslation() = runTest {
        val translated = translationEngine.translate(
            text = "Move to high ground",
            from = Language.ENGLISH,
            to = Language.TAMIL
        )
        assertThat(translated).isEqualTo("உயரமான இடத்திற்கு செல்லுங்கள்")
    }

    @Test
    fun testSameLanguageReturnsUnchanged() = runTest {
        val original = "पानी बढ़ रहा है"
        val translated = translationEngine.translate(
            text = original,
            from = Language.HINDI,
            to = Language.HINDI
        )
        assertThat(translated).isEqualTo(original)
    }

    @Test
    fun testWordLevelTranslation() = runTest {
        val translated = translationEngine.translate(
            text = "पानी",
            from = Language.HINDI,
            to = Language.ENGLISH
        )
        assertThat(translated.lowercase()).contains("water")
    }
}
