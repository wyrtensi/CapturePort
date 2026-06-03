package dev.captureport.app.network

object DiscoveryHosts {
    fun mergePacketAndPayloadHosts(packetHost: String?, payloadHosts: String): String {
        val hosts = linkedSetOf<String>()

        packetHost
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { hosts.add(it) }

        payloadHosts
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { hosts.add(it) }

        return hosts.joinToString(",")
    }
}
