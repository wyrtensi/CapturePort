package dev.captureport.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import dev.captureport.app.data.datastore.PairedDevicesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.Arrays

class UdpDiscoveryListener(
    private val context: Context,
    private val repository: PairedDevicesRepository,
    private val scope: CoroutineScope
) {
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = false
    private var job: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val restartMutex = Mutex()

    fun start() {
        scope.launch(Dispatchers.IO) {
            restartMutex.withLock {
                if (isRunning) return@withLock
                isRunning = true

                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null && networkCallback == null) {
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            Log.i("UdpDiscoveryListener", "Network available, restarting UDP listener")
                            restartSocket()
                        }

                        override fun onLost(network: Network) {
                            Log.i("UdpDiscoveryListener", "Network lost, restarting UDP listener")
                            restartSocket()
                        }
                    }
                    networkCallback = callback
                    try {
                        cm.registerDefaultNetworkCallback(callback)
                    } catch (e: Exception) {
                        Log.e("UdpDiscoveryListener", "Failed to register network callback: ${e.message}")
                    }
                }

                startListeningJobLocked()
            }
        }
    }

    private fun restartSocket() {
        scope.launch(Dispatchers.IO) {
            restartMutex.withLock {
                if (!isRunning) return@withLock
                Log.i("UdpDiscoveryListener", "Restarting UDP discovery listening job")
                job?.cancelAndJoin()
                startListeningJobLocked()
            }
        }
    }

    private fun startListeningJobLocked() {
        cleanupSocketAndLock()

        job = scope.launch(Dispatchers.IO) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("CapturePortUdpLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }

                val newSocket = DatagramSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    broadcast = true
                }
                socket = newSocket

                newSocket.bind(java.net.InetSocketAddress(5354))

                val buffer = ByteArray(4096)
                Log.i("UdpDiscoveryListener", "UDP Discovery Listener started on port 5354")

                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e("UdpDiscoveryListener", "Socket receive error: ${e.message}")
                        delay(1000)
                        continue
                    }

                    val message = String(packet.data, 0, packet.length)
                    try {
                        val payload = JSONObject(message)
                        val idBase64 = payload.optString("id")
                        val payloadHosts = payload.optString("hosts")
                        val port = payload.optInt("port")

                        if (idBase64.isNotEmpty() && port > 0) {
                            val hosts = DiscoveryHosts.mergePacketAndPayloadHosts(
                                packet.address?.hostAddress,
                                payloadHosts,
                            )
                            if (hosts.isEmpty()) {
                                continue
                            }
                            val decodedPk = Base64.decode(idBase64, Base64.URL_SAFE or Base64.NO_PADDING)
                            val devices = repository.pairedDevicesFlow.first()
                            for (device in devices) {
                                val devPk = device.publicKey.toByteArray()
                                if (Arrays.equals(devPk, decodedPk)) {
                                    // Update the device if host/port changed or to update last seen
                                    if (device.host != hosts || device.port != port) {
                                        Log.i("UdpDiscoveryListener", "Updating paired device ${device.name} address to: $hosts:$port")
                                    }
                                    repository.updateLocalEndpoint(device.id, hosts, port)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("UdpDiscoveryListener", "Failed to parse UDP packet: ${e.message}")
                    }
                }
            } catch (e: SocketException) {
                Log.e("UdpDiscoveryListener", "Failed to start UDP socket on port 5354: ${e.message}")
            } catch (e: Exception) {
                Log.e("UdpDiscoveryListener", "Error in UDP listener: ${e.message}")
            } finally {
                cleanupSocketAndLock()
            }
        }
    }

    private fun cleanupSocketAndLock() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
        socket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    fun stop() {
        scope.launch(Dispatchers.IO) {
            restartMutex.withLock {
                if (!isRunning) return@withLock
                isRunning = false

                val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                networkCallback?.let { callback ->
                    try {
                        cm?.unregisterNetworkCallback(callback)
                    } catch (e: Exception) {
                        Log.e("UdpDiscoveryListener", "Failed to unregister network callback: ${e.message}")
                    }
                }
                networkCallback = null

                job?.cancelAndJoin()
                cleanupSocketAndLock()
                Log.i("UdpDiscoveryListener", "UDP Discovery Listener stopped")
            }
        }
    }
}
