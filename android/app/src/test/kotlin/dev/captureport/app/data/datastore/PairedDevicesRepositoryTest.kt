package dev.captureport.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.data.PairedDevices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryDataStore<T>(initialValue: T) : DataStore<T> {
    private val _data = MutableStateFlow(initialValue)
    override val data: Flow<T> = _data

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        val current = _data.value
        val updated = transform(current)
        _data.value = updated
        return updated
    }
}

class PairedDevicesRepositoryTest {

    private lateinit var dataStore: DataStore<PairedDevices>
    private lateinit var repository: PairedDevicesRepository

    @Before
    fun setUp() {
        dataStore = InMemoryDataStore(PairedDevices.getDefaultInstance())
        repository = PairedDevicesRepository(null, dataStore)
    }

    @Test
    fun testAddAndGetDevice() = runBlocking {
        val device = PairedDevice.newBuilder()
            .setId("device-123")
            .setName("My PC")
            .setOs("windows")
            .setHost("192.168.1.10")
            .setPort(7878)
            .setToken("my-token")
            .build()

        repository.addDevice(device)

        val devices = repository.pairedDevicesFlow.first()
        assertEquals(1, devices.size)
        assertEquals("device-123", devices[0].id)
        assertEquals("My PC", devices[0].name)

        val selected = repository.selectedDeviceFlow.first()
        assertEquals("device-123", selected?.id)
    }

    @Test
    fun testRemoveDevice() = runBlocking {
        val device = PairedDevice.newBuilder()
            .setId("device-123")
            .setName("My PC")
            .setOs("windows")
            .build()

        repository.addDevice(device)
        repository.removeDevice("device-123")

        val devices = repository.pairedDevicesFlow.first()
        assertTrue(devices.isEmpty())

        val selected = repository.selectedDeviceFlow.first()
        assertEquals(null, selected)
    }

    @Test
    fun updateLocalEndpointPreservesInternetEndpointAndAlias() = runBlocking {
        val device = PairedDevice.newBuilder()
            .setId("device-123")
            .setName("Office PC")
            .setAlias("Capture Rig")
            .setOs("windows")
            .setHost("192.168.1.10")
            .setPort(7878)
            .setLocalHosts("192.168.1.10")
            .setLocalPort(7878)
            .setInternetHost("capture.example.net")
            .setInternetPort(9443)
            .build()

        repository.addDevice(device)
        repository.updateLocalEndpoint("device-123", "192.168.1.20,192.168.1.21", 7879)

        val updated = repository.pairedDevicesFlow.first().single()
        assertEquals("Capture Rig", updated.alias)
        assertEquals("capture.example.net", updated.internetHost)
        assertEquals(9443, updated.internetPort)
        assertEquals("192.168.1.20,192.168.1.21", updated.localHosts)
        assertEquals(7879, updated.localPort)
    }

    @Test
    fun addDevicePreservesExistingAliasWhenQrNameChanges() = runBlocking {
        val first = PairedDevice.newBuilder()
            .setId("device-123")
            .setName("Old Hostname")
            .setAlias("Desk PC")
            .setOs("windows")
            .build()
        val refreshed = PairedDevice.newBuilder()
            .setId("device-123")
            .setName("New Hostname")
            .setOs("windows")
            .build()

        repository.addDevice(first)
        repository.addDevice(refreshed)

        val updated = repository.pairedDevicesFlow.first().single()
        assertEquals("New Hostname", updated.name)
        assertEquals("Desk PC", updated.alias)
    }

    @Test
    fun selectDeviceSwitchesActivePcWithoutRemovingOtherReceivers() = runBlocking {
        val laptop = PairedDevice.newBuilder()
            .setId("pc-laptop")
            .setName("Laptop")
            .setOs("windows")
            .build()
        val desktop = PairedDevice.newBuilder()
            .setId("pc-desktop")
            .setName("Desktop")
            .setOs("linux")
            .build()

        repository.addDevice(laptop)
        repository.addDevice(desktop)
        repository.selectDevice("pc-laptop")

        val devices = repository.pairedDevicesFlow.first()
        val selected = repository.selectedDeviceFlow.first()

        assertEquals(listOf("pc-laptop", "pc-desktop"), devices.map { it.id })
        assertEquals("pc-laptop", selected?.id)
    }
}
