package dev.captureport.app

import android.app.Application
import android.util.Log
import androidx.core.content.ContextCompat
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

internal fun isBackgroundModeReady(
    policy: CameraCapturePolicy,
    cameraPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
    notificationsGranted: Boolean,
    isBackgroundCameraArmed: Boolean,
    isBackgroundMicrophoneArmed: Boolean
): Boolean {
    if (policy != CameraCapturePolicy.Background) return true
    return cameraPermissionGranted &&
        microphonePermissionGranted &&
        notificationsGranted &&
        isBackgroundCameraArmed &&
        isBackgroundMicrophoneArmed
}

internal fun shouldShowXiaomiAutostartHint(
    manufacturer: String,
    display: String
): Boolean {
    val manufacturerText = manufacturer.lowercase()
    val displayText = display.lowercase()
    return manufacturerText.contains("xiaomi") ||
        manufacturerText.contains("poco") ||
        displayText.contains("hyperos") ||
        displayText.contains("miui")
}

internal fun isRemoteCameraCaptureAllowed(
    policy: CameraCapturePolicy,
    isCameraScreenVisible: Boolean,
    isBackgroundCameraArmed: Boolean
): Boolean {
    return isCameraScreenVisible ||
        (policy == CameraCapturePolicy.Background && isBackgroundCameraArmed)
}

internal fun isRemoteVideoCaptureAllowed(
    policy: CameraCapturePolicy,
    isCameraScreenVisible: Boolean,
    isBackgroundCameraArmed: Boolean,
    isBackgroundMicrophoneArmed: Boolean
): Boolean {
    return isCameraScreenVisible ||
        (
            policy == CameraCapturePolicy.Background &&
                isBackgroundCameraArmed &&
                isBackgroundMicrophoneArmed
        )
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

    private val _isBackgroundCameraArmed = kotlinx.coroutines.flow.MutableStateFlow(false)
    var isBackgroundCameraArmed: Boolean
        get() = _isBackgroundCameraArmed.value
        set(value) { _isBackgroundCameraArmed.value = value }
    val isBackgroundCameraArmedFlow = _isBackgroundCameraArmed.asStateFlow()

    private val _isBackgroundMicrophoneArmed = kotlinx.coroutines.flow.MutableStateFlow(false)
    var isBackgroundMicrophoneArmed: Boolean
        get() = _isBackgroundMicrophoneArmed.value
        set(value) { _isBackgroundMicrophoneArmed.value = value }
    val isBackgroundMicrophoneArmedFlow = _isBackgroundMicrophoneArmed.asStateFlow()
    
    lateinit var cameraController: dev.captureport.app.camera.CameraController
        private set

    fun canServeRemoteCameraCapture(): Boolean {
        return hasCameraCapturePermission() &&
            isRemoteCameraCaptureAllowed(
                cameraCapturePolicy,
                isCameraScreenVisible,
                isBackgroundCameraArmed
            )
    }

    fun canServeRemoteVideoCapture(): Boolean {
        return hasCameraCapturePermission() &&
            hasAudioCapturePermission() &&
            isRemoteVideoCaptureAllowed(
                cameraCapturePolicy,
                isCameraScreenVisible,
                isBackgroundCameraArmed,
                isBackgroundMicrophoneArmed
            )
    }

    fun hasCameraCapturePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioCapturePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun ensureWsClient(): WsClient {
        wsClient?.let { return it }
        return synchronized(this) {
            wsClient ?: WsClient(
                context = applicationContext,
                scope = applicationScope,
                onCaptureRequest = { onPhotoSnapped, onCaptureRejected ->
                    applicationScope.launch(Dispatchers.Main) {
                        val activeCam = cameraController
                        if (canServeRemoteCameraCapture()) {
                            activeCam.takePhoto(
                                onSuccess = { file -> onPhotoSnapped(file) },
                                onError = { err ->
                                    Log.e("CapturePortApp", "MCP photo snap failed: ${err.message}")
                                    onCaptureRejected("camera capture failed")
                                }
                            )
                        } else {
                            Log.w("CapturePortApp", "Remote camera capture rejected: camera unavailable")
                            onCaptureRejected("camera unavailable under current capture policy or permissions")
                        }
                    }
                }
            ).also { wsClient = it }
        }
    }

    fun reconnectToDevice(device: dev.captureport.app.data.PairedDevice, startService: Boolean = true) {
        ensureWsClient().connect(device)
        if (startService) {
            val intent = android.content.Intent(this, dev.captureport.app.service.CapturePortService::class.java).apply {
                action = dev.captureport.app.service.CapturePortService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    fun reconnectToSelectedDevice(startService: Boolean = true) {
        applicationScope.launch(Dispatchers.IO) {
            val device = pairedDevicesRepository.selectedDeviceFlow.first()
            if (device != null) {
                reconnectToDevice(device, startService)
            }
        }
    }

    fun ensureSelectedDeviceConnection(startService: Boolean = true) {
        applicationScope.launch(Dispatchers.IO) {
            val state = wsClient?.connectionState?.value.orEmpty()
            if (WsClient.isConnectionLoopActive(state)) {
                return@launch
            }
            val device = pairedDevicesRepository.selectedDeviceFlow.first()
            if (device != null) {
                reconnectToDevice(device, startService)
            }
        }
    }

    fun disconnectFromReceiver(stopService: Boolean = true) {
        wsClient?.disconnect()
        if (stopService) {
            val intent = android.content.Intent(this, dev.captureport.app.service.CapturePortService::class.java).apply {
                action = dev.captureport.app.service.CapturePortService.ACTION_STOP
            }
            stopService(intent)
        }
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
