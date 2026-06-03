package dev.captureport.app.receivers

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiversScreenLayoutContractTest {

    @Test
    fun connectedReceiverFadeDoesNotDefineDashboardHeight() {
        val source = readSource("receivers/ReceiversScreen.kt")
        val receiverRowBlock = source.blockBetween(
            start = "LazyRow(",
            end = "Spacer(modifier = Modifier.height(24.dp))",
        )

        assertTrue(
            "The connected receiver fade should overlay the measured LazyRow instead of sizing the dashboard.",
            receiverRowBlock.contains(".matchParentSize()")
        )
        assertFalse(
            "fillMaxHeight here makes the connected receiver area consume the available screen height.",
            receiverRowBlock.contains(".fillMaxHeight()")
        )
    }

    @Test
    fun visibleNetworkAssistCopyAvoidsExplicitVpnWording() {
        val receiverSource = readSource("receivers/ReceiversScreen.kt")
        val pairingSource = readSource("pairing/PairingScreen.kt")
        val visibleSources = receiverSource + "\n" + pairingSource

        assertFalse(visibleSources.contains("VPN Bypass Active"))
        assertFalse(visibleSources.contains("Active VPN detected"))
        assertFalse(visibleSources.contains("VPN bypass"))
    }

    private fun readSource(relativePath: String): String {
        val packagePath = "src/main/kotlin/dev/captureport/app/$relativePath"
        val candidates = listOf(
            Paths.get(packagePath),
            Paths.get("app").resolve(packagePath)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Could not find source file for $relativePath")
        return String(Files.readAllBytes(path))
    }

    private fun String.blockBetween(start: String, end: String): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Could not find start marker: $start" }
        val endIndex = indexOf(end, startIndex)
        require(endIndex >= 0) { "Could not find end marker after $start: $end" }
        return substring(startIndex, endIndex)
    }
}
