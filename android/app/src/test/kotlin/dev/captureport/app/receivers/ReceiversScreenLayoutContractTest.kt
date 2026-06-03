package dev.captureport.app.receivers

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiversScreenLayoutContractTest {

    @Test
    fun connectedReceiverOverflowUsesLineIndicatorInsteadOfFade() {
        val source = readSource("receivers/ReceiversScreen.kt")
        val receiverRowBlock = source.blockBetween(
            start = "LazyRow(",
            end = "Spacer(modifier = Modifier.height(24.dp))",
        )

        assertTrue(
            "The connected receiver overflow indicator should be based on LazyRow scroll state.",
            receiverRowBlock.contains("canScrollForward")
        )
        assertFalse(
            "The old right-edge gradient fade should not remain.",
            receiverRowBlock.contains("Brush.horizontalGradient")
        )
        assertFalse(
            "The old fade overlay should not cover the receiver cards.",
            receiverRowBlock.contains(".matchParentSize()")
        )
    }

    @Test
    fun visibleNetworkAssistCopyAvoidsLegacyRouteWording() {
        val receiverSource = readSource("receivers/ReceiversScreen.kt")
        val pairingSource = readSource("pairing/PairingScreen.kt")
        val visibleSources = receiverSource + "\n" + pairingSource

        assertFalse(visibleSources.contains("TRANSPORT_"))
        assertFalse(visibleSources.contains("assist active"))
    }

    @Test
    fun receiverSettingsMenuOwnsPairingAndConnectionModeControls() {
        val receiverSource = readSource("receivers/ReceiversScreen.kt")
        val appSource = readSource("CapturePortApp.kt")
        val visibleSources = receiverSource + "\n" + appSource

        assertTrue(receiverSource.contains("Add & Pair a New PC"))
        assertTrue(visibleSources.contains("Local only"))
        assertTrue(visibleSources.contains("Through internet"))
        assertTrue(receiverSource.contains("Receiver settings"))
        assertFalse(receiverSource.contains("contentDescription = \"Pair Receiver\""))
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
