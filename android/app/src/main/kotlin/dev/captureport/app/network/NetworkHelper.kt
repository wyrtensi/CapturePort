package dev.captureport.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.Network
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

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
                    .pingInterval(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .dns(DynamicDns())
                    .build()
                    .also { sharedOkHttpClient = it }
            }
        }
    }

    class DynamicDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val literalAddress = parseLiteralAddress(hostname)
            if (literalAddress != null) {
                return listOf(literalAddress)
            }

            return Dns.SYSTEM.lookup(hostname)
        }

        private fun parseLiteralAddress(hostname: String): InetAddress? {
            if (!InetAddresses.isNumericAddress(hostname)) return null

            return try {
                InetAddresses.parseNumericAddress(hostname)
            } catch (e: Exception) {
                null
            }
        }
    }
}
