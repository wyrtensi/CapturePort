package dev.captureport.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

object NetworkHelper {
    @Volatile
    private var sharedOkHttpClient: OkHttpClient? = null

    @Volatile
    private var isCallbackRegistered = false

    private fun registerNetworkCallback(context: Context) {
        if (isCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i("NetworkHelper", "Default network changed, evicting OkHttp connection pool")
                    sharedOkHttpClient?.connectionPool?.evictAll()
                }

                override fun onLost(network: Network) {
                    Log.i("NetworkHelper", "Default network lost, evicting OkHttp connection pool")
                    sharedOkHttpClient?.connectionPool?.evictAll()
                }
            })
            isCallbackRegistered = true
        } catch (e: Exception) {
            Log.e("NetworkHelper", "Failed to register network callback: ${e.message}")
        }
    }

    fun getSharedClient(context: Context): OkHttpClient {
        return sharedOkHttpClient ?: synchronized(this) {
            sharedOkHttpClient ?: run {
                registerNetworkCallback(context.applicationContext)
                OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .pingInterval(10, TimeUnit.SECONDS) // Keep VPN / Wi-Fi connections alive
                    .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive socket
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .socketFactory(DelegatingSocketFactory(context.applicationContext))
                    .dns(DynamicDns(context.applicationContext))
                    .build().also {
                        sharedOkHttpClient = it
                    }
            }
        }
    }

    /**
     * Finds a physical network interface (preferably Wi-Fi, otherwise Cellular)
     * that is NOT routed through a VPN. Returns null if no such network is active.
     */
    @Suppress("DEPRECATION")
    fun getNonVpnNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val networks = cm.allNetworks
        
        // 1. Try to find a non-VPN Wi-Fi network
        val wifi = networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        if (wifi != null) return wifi

        // 2. Fallback to any non-VPN network (e.g. Ethernet, Cellular)
        return networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || 
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    class VpnBypassSocket(private val context: Context) : Socket() {
        override fun connect(endpoint: SocketAddress?) {
            connect(endpoint, 0)
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (endpoint is InetSocketAddress) {
                bindSocketIfLocal(endpoint.address)
            }
            super.connect(endpoint, timeout)
        }

        private fun bindSocketIfLocal(address: InetAddress) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            if (shouldBypassVpn(address, cm)) {
                val nonVpnNetwork = getNonVpnNetwork(context)
                if (nonVpnNetwork != null) {
                    try {
                        nonVpnNetwork.bindSocket(this)
                        Log.i("VpnBypassSocket", "Successfully bound socket to bypass VPN for address: $address")
                    } catch (e: Exception) {
                        Log.e("VpnBypassSocket", "Failed to bind socket to physical network: ${e.message}")
                    }
                }
            }
        }
    }

    private fun shouldBypassVpn(address: InetAddress, cm: ConnectivityManager): Boolean {
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        val isVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (!isVpnActive) return false

        // If target is loopback, bypass is not needed/applicable
        if (address.isLoopbackAddress) return false

        // Check if target is on the active VPN network's subnet
        val vpnLinkProperties = cm.getLinkProperties(activeNetwork)
        val vpnLinkAddresses = vpnLinkProperties?.linkAddresses ?: emptyList()
        val isInVpnSubnet = vpnLinkAddresses.any { linkAddr ->
            isIpInSubnet(address, linkAddr)
        }
        if (isInVpnSubnet) {
            // Target is on the VPN subnet, so route through VPN (do not bypass)
            return false
        }

        // If it's a site-local or link-local address, bypass VPN
        if (address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }

        // Manual check for IPv6 ULA (fc00::/7)
        val bytes = address.address
        if (bytes.size == 16) {
            val firstByte = bytes[0].toInt() and 0xFF
            if ((firstByte and 0xFE) == 0xFC) {
                return true
            }
        }

        return false
    }

    private fun isIpInSubnet(ip: InetAddress, linkAddress: LinkAddress): Boolean {
        val ipBytes = ip.address
        val linkBytes = linkAddress.address.address
        if (ipBytes.size != linkBytes.size) return false

        val prefixLength = linkAddress.prefixLength
        val bytesToCheck = prefixLength / 8
        val bitsToCheck = prefixLength % 8

        for (i in 0 until bytesToCheck) {
            if (ipBytes[i] != linkBytes[i]) return false
        }

        if (bitsToCheck > 0) {
            val mask = (0xFF shl (8 - bitsToCheck)).toByte()
            val ipVal = ipBytes[bytesToCheck].toInt() and mask.toInt()
            val linkVal = linkBytes[bytesToCheck].toInt() and mask.toInt()
            if (ipVal != linkVal) return false
        }

        return true
    }

    class DelegatingSocketFactory(private val context: Context) : SocketFactory() {
        override fun createSocket(): Socket {
            return VpnBypassSocket(context)
        }

        override fun createSocket(host: String?, port: Int): Socket {
            val socket = VpnBypassSocket(context)
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val socket = VpnBypassSocket(context)
            if (localHost != null) {
                socket.bind(InetSocketAddress(localHost, localPort))
            }
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(address: InetAddress?, port: Int): Socket {
            val socket = VpnBypassSocket(context)
            socket.connect(InetSocketAddress(address, port))
            return socket
        }

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
            val socket = VpnBypassSocket(context)
            if (localAddress != null) {
                socket.bind(InetSocketAddress(localAddress, localPort))
            }
            socket.connect(InetSocketAddress(address, port))
            return socket
        }
    }

    class DynamicDns(private val context: Context) : Dns {
        private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        override fun lookup(hostname: String): List<InetAddress> {
            val activeNetwork = cm?.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val isVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (isVpnActive) {
                val nonVpnNetwork = getNonVpnNetwork(context)
                if (nonVpnNetwork != null) {
                    try {
                        return nonVpnNetwork.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        // fallback to system if lookup fails
                    }
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }
}
