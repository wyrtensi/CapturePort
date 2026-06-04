package dev.captureport.app

import android.app.Application
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import dev.captureport.app.network.UdpDiscoveryListener
import kotlinx.coroutines.flow.asStateFlow

enum class CameraCapturePolicy(val label: String) {
    ScreenOnly("Camera: screen only"),
    Background("Camera: background")
}

enum class ReceiverConnectionMode(val label: String) {
    LocalOnly("Local only"),
    LocalThenInternet("Through internet"),
    InternetOnly("Internet only")
}

class CapturePortApp : Application() {
    
    val applicationScope = CoroutineScope(SupervisorJob())
    
    lateinit var pairedDevicesRepository: PairedDevicesRepository
        private set
        
    var wsClient: WsClient? = null
    private var udpDiscoveryListener: UdpDiscoveryListener? = null

    private val _cameraCapturePolicy = kotlinx.coroutines.flow.MutableStateFlow(CameraCapturePolicy.ScreenOnly)
    var cameraCapturePolicy: CameraCapturePolicy
        get() = _cameraCapturePolicy.value
        set(value) { _cameraCapturePolicy.value = value }
    val cameraCapturePolicyFlow = _cameraCapturePolicy.asStateFlow()

    @Volatile
    var receiverConnectionMode: ReceiverConnectionMode = ReceiverConnectionMode.LocalThenInternet

    private val _isCameraScreenVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    var isCameraScreenVisible: Boolean
        get() = _isCameraScreenVisible.value
        set(value) { _isCameraScreenVisible.value = value }
    val isCameraScreenVisibleFlow = _isCameraScreenVisible.asStateFlow()

    private val _isActivityVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    var isActivityVisible: Boolean
        get() = _isActivityVisible.value
        set(value) { _isActivityVisible.value = value }
    val isActivityVisibleFlow = _isActivityVisible.asStateFlow()
    
    lateinit var cameraController: dev.captureport.app.camera.CameraController
        private set

    fun canServeRemoteCameraCapture(): Boolean {
        return (cameraCapturePolicy == CameraCapturePolicy.Background) ||
               (cameraCapturePolicy == CameraCapturePolicy.ScreenOnly && isCameraScreenVisible)
    }

    fun updateBackgroundService() {
        val intent = android.content.Intent(this, dev.captureport.app.service.CapturePortService::class.java).apply {
            action = dev.captureport.app.service.CapturePortService.ACTION_UPDATE_STATE
        }
        try {
            // Only start/update service if there's a running connection attempt or active device selected
            // We'll let ReceiversScreen manage service start/stop cleanly based on device selection,
            // but we can safely call startService to update state if the service is already running.
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.w("CapturePortApp", "Could not update service state: ${e.message}")
        }
    }

    fun applyCameraCapturePolicy(policy: CameraCapturePolicy) {
        cameraCapturePolicy = policy
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_CAMERA_POLICY, policy.name)
            .apply()
        updateBackgroundService()
    }

    fun applyReceiverConnectionMode(mode: ReceiverConnectionMode) {
        receiverConnectionMode = mode
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_CONNECTION_MODE, mode.name)
            .apply()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Clean up temporary capture files in cache directory
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("CP_")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CapturePortApp", "Failed to clean up cache: ${e.message}")
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        cameraCapturePolicy = prefs.getString(KEY_CAMERA_POLICY, null)
            ?.let { runCatching { CameraCapturePolicy.valueOf(it) }.getOrNull() }
            ?: CameraCapturePolicy.ScreenOnly
        receiverConnectionMode = prefs.getString(KEY_CONNECTION_MODE, null)
            ?.let { runCatching { ReceiverConnectionMode.valueOf(it) }.getOrNull() }
            ?: ReceiverConnectionMode.LocalThenInternet
        pairedDevicesRepository = PairedDevicesRepository(this)
        cameraController = dev.captureport.app.camera.CameraController(this)

        udpDiscoveryListener = UdpDiscoveryListener(this, pairedDevicesRepository, applicationScope).apply {
            start()
        }
    }

    companion object {
        private const val PREFS_NAME = "captureport_settings"
        private const val KEY_CAMERA_POLICY = "camera_policy"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        lateinit var instance: CapturePortApp
            private set
    }
}
