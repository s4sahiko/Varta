package com.offlinevoicerelay.model

data class MessageEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val langCode: String,
    val isAlert: Boolean,
    val isSent: Boolean,
    val targetPeerId: String? = null,
    val originPeerId: String? = null,
    val isBroadcast: Boolean = (targetPeerId == null),
    val timestampMs: Long = System.currentTimeMillis()
)
