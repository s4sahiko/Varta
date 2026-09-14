package com.offlinevoicerelay.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The wire message relayed between phones. TEXT ONLY — audio never leaves the
 * capturing device (Section 6, hard constraint #3).
 */
@Serializable
data class Message(
    val msgId: String,
    val lang: String,       // Language.code
    val text: String,
    val isAlert: Boolean,
    val hopCount: Int,
    val ttl: Int,            // hop-count ceiling, default 20 (Section 4, Mesh relay row)
    val ts: Long,            // origin epoch millis
    val originId: String,
    val targetPeerId: String? = null
) {
    /** True once the message has exhausted its hop budget and must not be relayed further. */
    fun isExpiredByHops(): Boolean = hopCount >= ttl

    /** Returns a copy ready to be rebroadcast by a relay node (hop count +1, same msgId). */
    fun forRelay(): Message = copy(hopCount = hopCount + 1)

    fun toWireBytes(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    companion object {
        const val DEFAULT_HOP_CEILING = 20

        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun newOriginated(
            text: String,
            lang: Language,
            isAlert: Boolean,
            originId: String,
            targetPeerId: String? = null,
            hopCeiling: Int = DEFAULT_HOP_CEILING
        ): Message = Message(
            msgId = UUID.randomUUID().toString(),
            lang = lang.code,
            text = text,
            isAlert = isAlert,
            hopCount = 0,
            ttl = hopCeiling,
            ts = System.currentTimeMillis(),
            originId = originId,
            targetPeerId = targetPeerId
        )

        fun fromWireBytes(bytes: ByteArray): Message =
            json.decodeFromString(serializer(), bytes.decodeToString())
    }
}
