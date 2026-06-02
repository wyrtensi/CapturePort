package dev.captureport.app

import android.app.Application
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

enum class CameraCapturePolicy(val label: String) {
    ScreenOnly("Camera: screen only")
}

class CapturePortApp : Application() {
    
    val applicationScope = CoroutineScope(SupervisorJob())
    
    lateinit var pairedDevicesRepository: PairedDevicesRepository
        private set
        
    var wsClient: WsClient? = null
    @Volatile
    var cameraCapturePolicy: CameraCapturePolicy = CameraCapturePolicy.ScreenOnly
    @Volatile
    var isCameraScreenVisible: Boolean = false
    var activeCameraController: dev.captureport.app.camera.CameraController? = null

    fun canServeRemoteCameraCapture(): Boolean {
        return cameraCapturePolicy == CameraCapturePolicy.ScreenOnly &&
            isCameraScreenVisible &&
            activeCameraController != null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pairedDevicesRepository = PairedDevicesRepository(this)
    }

    companion object {
        lateinit var instance: CapturePortApp
            private set
    }
}
