package dev.captureport.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.data.PairedDevices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.pairedDevicesDataStore: DataStore<PairedDevices> by dataStore(
    fileName = "paired_devices.pb",
    serializer = PairedDevicesSerializer
)

class PairedDevicesRepository(
    private val context: Context?,
    private val dataStore: DataStore<PairedDevices> = context!!.pairedDevicesDataStore
) {

    // Flow of all paired devices
    val pairedDevicesFlow: Flow<List<PairedDevice>> = dataStore.data
        .map { it.devicesList }

    // Flow of the currently selected/active device
    val selectedDeviceFlow: Flow<PairedDevice?> = dataStore.data
        .map { pairedDevices ->
            pairedDevices.devicesList.find { it.id == pairedDevices.selectedId }
        }

    // Add a newly paired device to the database
    suspend fun addDevice(device: PairedDevice) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            // Remove previous instances if duplicate
            val existingIndex = builder.devicesList.indexOfFirst { it.id == device.id }
            if (existingIndex >= 0) {
                val existing = builder.getDevices(existingIndex)
                val updated = device.toBuilder().apply {
                    if (alias.isBlank() && existing.alias.isNotBlank()) {
                        alias = existing.alias
                    }
                    if (internetHost.isBlank() && existing.internetHost.isNotBlank()) {
                        internetHost = existing.internetHost
                        internetPort = existing.internetPort
                    }
                }.build()
                builder.setDevices(existingIndex, updated)
            } else {
                builder.addDevices(device)
            }
            builder.setSelectedId(device.id)
            builder.build()
        }
    }

    // Update the last seen timestamp of a device when it replies to broadcast
    suspend fun updateLastSeen(deviceId: String, host: String, port: Int) {
        updateLocalEndpoint(deviceId, host, port)
    }

    suspend fun updateLocalEndpoint(deviceId: String, hosts: String, port: Int) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.devicesList.indexOfFirst { it.id == deviceId }
            if (index >= 0) {
                val updated = builder.getDevices(index).toBuilder()
                    .setLastSeenMs(System.currentTimeMillis())
                    .setHost(hosts)
                    .setPort(port)
                    .setLocalHosts(hosts)
                    .setLocalPort(port)
                    .build()
                builder.setDevices(index, updated)
            }
            builder.build()
        }
    }

    suspend fun updateInternetEndpoint(deviceId: String, host: String, port: Int) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.devicesList.indexOfFirst { it.id == deviceId }
            if (index >= 0) {
                val updated = builder.getDevices(index).toBuilder()
                    .setInternetHost(host.trim())
                    .setInternetPort(port)
                    .build()
                builder.setDevices(index, updated)
            }
            builder.build()
        }
    }

    suspend fun renameDeviceAlias(deviceId: String, alias: String) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.devicesList.indexOfFirst { it.id == deviceId }
            if (index >= 0) {
                val updated = builder.getDevices(index).toBuilder()
                    .setAlias(alias.trim())
                    .build()
                builder.setDevices(index, updated)
            }
            builder.build()
        }
    }

    // Sets a device as the primary selected communication target
    suspend fun selectDevice(deviceId: String) {
        dataStore.updateData { current ->
            current.toBuilder()
                .setSelectedId(deviceId)
                .build()
        }
    }

    // Remove a paired device
    suspend fun removeDevice(deviceId: String) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val index = builder.devicesList.indexOfFirst { it.id == deviceId }
            if (index >= 0) {
                builder.removeDevices(index)
            }
            if (builder.selectedId == deviceId) {
                builder.selectedId = ""
            }
            builder.build()
        }
    }
}
