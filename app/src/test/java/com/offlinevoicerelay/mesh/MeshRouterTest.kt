package com.offlinevoicerelay.mesh

import com.google.common.truth.Truth.assertThat
import com.offlinevoicerelay.model.Language
import com.offlinevoicerelay.model.Message
import org.junit.Test

class MeshRouterTest {

    private fun testMessage(hopCeiling: Int = 5, hopCount: Int = 0, targetPeerId: String? = null) = Message(
        msgId = "m1",
        lang = Language.HINDI.code,
        text = "flood water rising, move to high ground",
        isAlert = true,
        hopCount = hopCount,
        ttl = hopCeiling,
        ts = 1_000L,
        originId = "phone-A",
        targetPeerId = targetPeerId
    )

    @Test
    fun `first arrival is delivered and relayed`() {
        val router = MeshRouter(localDeviceId = "my-device")
        val decision = router.onMessageReceived(testMessage())

        assertThat(decision.deliverLocally).isTrue()
        assertThat(decision.relay).isNotNull()
        assertThat(decision.relay!!.hopCount).isEqualTo(1)
    }

    @Test
    fun `message originated by local device is dropped and not relayed`() {
        val router = MeshRouter(localDeviceId = "phone-A")
        val decision = router.onMessageReceived(testMessage()) // testMessage has originId = "phone-A"

        assertThat(decision.deliverLocally).isFalse()
        assertThat(decision.relay).isNull()
    }

    @Test
    fun `duplicate arrival is dropped, not delivered twice, not relayed twice`() {
        val router = MeshRouter(localDeviceId = "my-device")
        router.onMessageReceived(testMessage())
        val secondArrival = router.onMessageReceived(testMessage())

        assertThat(secondArrival.deliverLocally).isFalse()
        assertThat(secondArrival.relay).isNull()
    }

    @Test
    fun `message at hop ceiling is delivered but not relayed further`() {
        val router = MeshRouter(localDeviceId = "my-device")
        val atCeiling = testMessage(hopCeiling = 5, hopCount = 5)

        val decision = router.onMessageReceived(atCeiling)

        assertThat(decision.deliverLocally).isTrue()
        assertThat(decision.relay).isNull()
    }

    @Test
    fun `targeted message for local device is delivered locally and NOT relayed`() {
        val router = MeshRouter(localDeviceId = "my-device")
        val targeted = testMessage(targetPeerId = "my-device")

        val decision = router.onMessageReceived(targeted)

        assertThat(decision.deliverLocally).isTrue()
        assertThat(decision.relay).isNull() // Never relayed for targeted messages
    }

    @Test
    fun `targeted message for different device is NOT delivered locally and NOT relayed`() {
        val router = MeshRouter(localDeviceId = "my-device")
        val targeted = testMessage(targetPeerId = "other-device")

        val decision = router.onMessageReceived(targeted)

        assertThat(decision.deliverLocally).isFalse()
        assertThat(decision.relay).isNull() // Never relayed for targeted messages
    }

    @Test
    fun `dedupe cache evicts entries after the TTL window`() {
        var now = 0L
        val router = MeshRouter(localDeviceId = "my-device", dedupeWindowMillis = 1000L, clock = { now })

        router.onMessageReceived(testMessage())
        assertThat(router.cacheSizeForTesting()).isEqualTo(1)

        now = 500L
        router.evictExpired()
        assertThat(router.cacheSizeForTesting()).isEqualTo(1)

        now = 1500L
        router.evictExpired()
        assertThat(router.cacheSizeForTesting()).isEqualTo(0)
    }
}
