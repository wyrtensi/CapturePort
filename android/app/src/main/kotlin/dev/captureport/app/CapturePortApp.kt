package dev.captureport.app

import android.app.Application
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import dev.captureport.app.network.UdpDiscoveryListener

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

    @Volatile
    var cameraCapturePolicy: CameraCapturePolicy = CameraCapturePolicy.ScreenOnly
    @Volatile
    var receiverConnectionMode: ReceiverConnectionMode = ReceiverConnectionMode.LocalThenInternet
    @Volatile
    var isCameraScreenVisible: Boolean = false
    var activeCameraController: dev.captureport.app.camera.CameraController? = null

    fun canServeRemoteCameraCapture(): Boolean {
        return cameraCapturePolicy == CameraCapturePolicy.ScreenOnly &&
            isCameraScreenVisible &&
            activeCameraController != null
    }

    fun applyCameraCapturePolicy(policy: CameraCapturePolicy) {
        cameraCapturePolicy = policy
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_CAMERA_POLICY, policy.name)
            .apply()
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        cameraCapturePolicy = prefs.getString(KEY_CAMERA_POLICY, null)
            ?.let { runCatching { CameraCapturePolicy.valueOf(it) }.getOrNull() }
            ?: CameraCapturePolicy.ScreenOnly
        receiverConnectionMode = prefs.getString(KEY_CONNECTION_MODE, null)
            ?.let { runCatching { ReceiverConnectionMode.valueOf(it) }.getOrNull() }
            ?: ReceiverConnectionMode.LocalThenInternet
        pairedDevicesRepository = PairedDevicesRepository(this)

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
