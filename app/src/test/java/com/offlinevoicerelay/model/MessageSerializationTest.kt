package com.offlinevoicerelay.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageSerializationTest {

    @Test
    fun `round trip preserves all fields`() {
        val original = Message.newOriginated(
            text = "पानी बढ़ रहा है, ऊँची जगह जाएँ",
            lang = Language.HINDI,
            isAlert = true,
            originId = "device-123"
        )

        val bytes = original.toWireBytes()
        val decoded = Message.fromWireBytes(bytes)

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `wire payload stays compact for a typical alert sentence`() {
        val msg = Message.newOriginated(
            text = "Bridge on the east road has collapsed, use the north route",
            lang = Language.ENGLISH,
            isAlert = true,
            originId = "device-abc"
        )
        // Section 4 target: "typically <500 bytes/message"
        assertThat(msg.toWireBytes().size).isLessThan(500)
    }

    @Test
    fun `forRelay increments hop count and preserves msgId`() {
        val original = Message.newOriginated("test", Language.TAMIL, false, "origin-1")
        val relayed = original.forRelay()

        assertThat(relayed.hopCount).isEqualTo(original.hopCount + 1)
        assertThat(relayed.msgId).isEqualTo(original.msgId)
    }

    @Test
    fun `isExpiredByHops true only once hopCount reaches ttl`() {
        val msg = Message.newOriginated("x", Language.KANNADA, false, "o", hopCeiling = 3)
        assertThat(msg.isExpiredByHops()).isFalse()

        val atCeiling = msg.copy(hopCount = 3)
        assertThat(atCeiling.isExpiredByHops()).isTrue()
    }

    @Test
    fun `unicode text in non-Latin scripts round trips exactly`() {
        val scripts = listOf(
            Language.GUJARATI to "પાણી વધી રહ્યું છે",
            Language.MALAYALAM to "വെള്ളം ഉയരുന്നു",
            Language.ODIA to "ପାଣି ବଢ଼ୁଛି",
            Language.BENGALI to "জল বাড়ছে"
        )
        scripts.forEach { (lang, text) ->
            val msg = Message.newOriginated(text, lang, true, "o")
            val decoded = Message.fromWireBytes(msg.toWireBytes())
            assertThat(decoded.text).isEqualTo(text)
        }
    }
}
