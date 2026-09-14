package com.offlinevoicerelay.mesh

import com.offlinevoicerelay.model.Message

/**
 * Flood-with-dedupe mesh routing (Section 4). Supports both mesh broadcast (flood)
 * and direct 1-to-1 targeted unicast delivery.
 */
class MeshRouter(
    private val localDeviceId: String = "",
    private val dedupeWindowMillis: Long = DEFAULT_DEDUPE_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** msgId -> the wall-clock time (per [clock]) it was first seen. */
    private val seenCache = LinkedHashMap<String, Long>()

    data class RoutingDecision(
        val deliverLocally: Boolean,
        val relay: Message?
    )

    /**
     * Called for every message this node receives from any peer.
     */
    @Synchronized
    fun onMessageReceived(message: Message): RoutingDecision {
        evictExpired()

        // Never deliver or relay messages originated by this device
        if (localDeviceId.isNotEmpty() && message.originId == localDeviceId) {
            return RoutingDecision(deliverLocally = false, relay = null)
        }

        val alreadySeen = seenCache.containsKey(message.msgId)
        if (alreadySeen) {
            return RoutingDecision(deliverLocally = false, relay = null)
        }

        seenCache[message.msgId] = clock()

        // 1-to-1 Targeted Unicast Message
        if (message.targetPeerId != null) {
            val deliverLocally = (message.targetPeerId == localDeviceId)
            return RoutingDecision(deliverLocally = deliverLocally, relay = null)
        }

        // Broadcast Message
        val relayMessage = if (message.isExpiredByHops()) null else message.forRelay()
        return RoutingDecision(deliverLocally = true, relay = relayMessage)
    }

    /** Call periodically (e.g. from the transmission layer's receive loop). */
    @Synchronized
    fun evictExpired() {
        val cutoff = clock() - dedupeWindowMillis
        val it = seenCache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove() else break
        }
    }

    @Synchronized
    fun cacheSizeForTesting(): Int = seenCache.size

    @Synchronized
    fun markAsOriginated(msgId: String) {
        seenCache[msgId] = clock()
    }

    companion object {
        const val DEFAULT_DEDUPE_WINDOW_MILLIS = 2 * 60 * 1000L
    }
}
