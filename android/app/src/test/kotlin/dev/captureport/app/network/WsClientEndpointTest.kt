package dev.captureport.app.network

import dev.captureport.app.ReceiverConnectionMode
import dev.captureport.app.data.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WsClientEndpointTest {

    @Test
    fun localOnlyUsesOnlyLocalEndpoints() {
        val device = pairedDevice()

        assertEquals(
            listOf(EndpointTarget("192.168.0.111", 7878), EndpointTarget("192.168.0.112", 7878)),
            WsClient.endpointTargets(device, ReceiverConnectionMode.LocalOnly)
        )
    }

    @Test
    fun throughInternetUsesLocalFirstThenInternet() {
        val device = pairedDevice()

        assertEquals(
            listOf(
                EndpointTarget("192.168.0.111", 7878),
                EndpointTarget("192.168.0.112", 7878),
                EndpointTarget("capture.example.net", 9443),
            ),
            WsClient.endpointTargets(device, ReceiverConnectionMode.LocalThenInternet)
        )
    }

    @Test
    fun internetOnlyUsesOnlyInternetEndpoint() {
        val device = pairedDevice()

        assertEquals(
            listOf(EndpointTarget("capture.example.net", 9443)),
            WsClient.endpointTargets(device, ReceiverConnectionMode.InternetOnly)
        )
    }

    @Test
    fun legacyHostAndPortStillWorkAsLocalEndpoint() {
        val device = PairedDevice.newBuilder()
            .setHost("10.0.0.2,10.0.0.3")
            .setPort(7878)
            .build()

        assertEquals(
            listOf(EndpointTarget("10.0.0.2", 7878), EndpointTarget("10.0.0.3", 7878)),
            WsClient.endpointTargets(device, ReceiverConnectionMode.LocalThenInternet)
        )
    }

    @Test
    fun connectionLoopActiveCoversConnectedAndRetryingStatesOnly() {
        assertTrue(WsClient.isConnectionLoopActive("Connected"))
        assertTrue(WsClient.isConnectionLoopActive("Connecting to 192.168.1.10..."))
        assertTrue(WsClient.isConnectionLoopActive("Reconnecting (Attempt 2)..."))
        assertFalse(WsClient.isConnectionLoopActive("Disconnected"))
        assertFalse(WsClient.isConnectionLoopActive(""))
    }

    private fun pairedDevice(): PairedDevice =
        PairedDevice.newBuilder()
            .setLocalHosts("192.168.0.111,192.168.0.112")
            .setLocalPort(7878)
            .setInternetHost("capture.example.net")
            .setInternetPort(9443)
            .build()
}
