package dev.captureport.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryHostsTest {
    @Test
    fun packetSenderAddressIsFirstAndPayloadHostsFollowDeduped() {
        val merged = DiscoveryHosts.mergePacketAndPayloadHosts(
            packetHost = "192.168.1.20",
            payloadHosts = "10.8.0.2, 192.168.1.20, pc.tailnet.ts.net, 172.17.0.1",
        )

        assertEquals(
            "192.168.1.20,10.8.0.2,pc.tailnet.ts.net,172.17.0.1",
            merged,
        )
    }

    @Test
    fun payloadHostsAreUsedWhenPacketAddressIsMissing() {
        val merged = DiscoveryHosts.mergePacketAndPayloadHosts(
            packetHost = null,
            payloadHosts = " 10.0.0.5,10.0.0.5,pc.tailnet.ts.net ",
        )

        assertEquals("10.0.0.5,pc.tailnet.ts.net", merged)
    }

    @Test
    fun packetSenderAddressCanStandAloneWhenPayloadHostsAreMissing() {
        val merged = DiscoveryHosts.mergePacketAndPayloadHosts(
            packetHost = "192.168.0.110",
            payloadHosts = "",
        )

        assertEquals("192.168.0.110", merged)
    }
}
