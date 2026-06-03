package dev.captureport.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import dev.captureport.app.data.datastore.PairedDevicesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.Arrays

import kotlinx.coroutines.Job

class UdpDiscoveryListener(
    private val context: Context,
    private val repository: PairedDevicesRepository,
    private val scope: CoroutineScope
) {
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = false
    private var job: Job? = null
 
    fun start() {
        if (isRunning) return
        isRunning = true
 
        job = scope.launch(Dispatchers.IO) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("CapturePortUdpLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
 
                socket = DatagramSocket(5354).apply {
                    broadcast = true
                }
 
                val buffer = ByteArray(4096)
                Log.i("UdpDiscoveryListener", "UDP Discovery Listener started on port 5354")
 
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet)
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e("UdpDiscoveryListener", "Socket receive error: ${e.message}")
                        continue
                    }
 
                    val message = String(packet.data, 0, packet.length)
                    try {
                        val payload = JSONObject(message)
                        val idBase64 = payload.optString("id")
                        val hosts = payload.optString("hosts")
                        val port = payload.optInt("port")
 
                        if (idBase64.isNotEmpty() && hosts.isNotEmpty() && port > 0) {
                            val decodedPk = Base64.decode(idBase64, Base64.URL_SAFE or Base64.NO_PADDING)
                            val devices = repository.pairedDevicesFlow.first()
                            for (device in devices) {
                                val devPk = device.publicKey.toByteArray()
                                if (Arrays.equals(devPk, decodedPk)) {
                                    // Update the device if host/port changed or to update last seen
                                    if (device.host != hosts || device.port != port) {
                                        Log.i("UdpDiscoveryListener", "Updating paired device ${device.name} address to: $hosts:$port")
                                    }
                                    repository.updateLastSeen(device.id, hosts, port)
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
                stop()
            }
        }.apply {
            invokeOnCompletion {
                stop()
            }
        }
    }
 
    fun stop() {
        if (!isRunning && socket == null && multicastLock == null) return
        isRunning = false
        val currentJob = job
        job = null
        currentJob?.cancel()
 
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
        Log.i("UdpDiscoveryListener", "UDP Discovery Listener stopped")
    }
}
