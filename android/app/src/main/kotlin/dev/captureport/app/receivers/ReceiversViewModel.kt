package dev.captureport.app.receivers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.captureport.app.CapturePortApp
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.data.datastore.PairedDevicesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReceiversViewModel(
    private val repository: PairedDevicesRepository
) : ViewModel() {

    val pairedDevices: StateFlow<List<PairedDevice>> = repository.pairedDevicesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedDevice: StateFlow<PairedDevice?> = repository.selectedDeviceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectDevice(device: PairedDevice) {
        viewModelScope.launch {
            repository.selectDevice(device.id)
        }
    }

    fun removeDevice(device: PairedDevice) {
        viewModelScope.launch {
            repository.removeDevice(device.id)
            val app = CapturePortApp.instance
            if (selectedDevice.value?.id == device.id) {
                app.wsClient?.disconnect()
            }
        }
    }

    class Factory(private val repository: PairedDevicesRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ReceiversViewModel::class.java)) {
                return ReceiversViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
