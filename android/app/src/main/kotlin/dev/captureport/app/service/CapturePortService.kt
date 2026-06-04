package dev.captureport.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.captureport.app.CapturePortApp
import dev.captureport.app.CameraCapturePolicy
import dev.captureport.app.MainActivity
import dev.captureport.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CapturePortService : LifecycleService() {

    private var connectionStateJob: Job? = null
    private var stateObserverJob: Job? = null
    private val CHANNEL_ID = "CapturePortServiceChannel"
    private val NOTIFICATION_ID = 102
    private var currentForegroundType = -1

    companion object {
        const val ACTION_START = "dev.captureport.app.action.START_SERVICE"
        const val ACTION_STOP = "dev.captureport.app.action.STOP_SERVICE"
        const val ACTION_UPDATE_STATE = "dev.captureport.app.action.UPDATE_STATE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startObservingAppState()
    }

    private fun startObservingAppState() {
        if (stateObserverJob != null) return
        val app = CapturePortApp.instance
        stateObserverJob = lifecycleScope.launch {
            combine(
                app.cameraCapturePolicyFlow,
                app.isActivityVisibleFlow
            ) { policy, isVisible ->
                Pair(policy, isVisible)
            }.collectLatest { (policy, isVisible) ->
                Log.d("CapturePortService", "App state changed: policy=$policy, isActivityVisible=$isVisible")
                promoteToForeground()
                updateCameraLifecycleBinding()
            }
        }
    }

    private fun stopObservingAppState() {
        stateObserverJob?.cancel()
        stateObserverJob = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START, ACTION_UPDATE_STATE -> {
                promoteToForeground()
                startTrackingConnectionState()
                updateCameraLifecycleBinding()
            }
            ACTION_STOP -> {
                stopTrackingConnectionState()
                stopObservingAppState()
                val app = CapturePortApp.instance
                app.cameraController.unbind()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun promoteToForeground() {
        val app = CapturePortApp.instance
        val isBgCamera = app.cameraCapturePolicy == CameraCapturePolicy.Background
        
        // Choose foreground type: camera + dataSync if background camera is allowed
        // To comply with Android 14+ foreground service rules, we promote to Camera type while in the foreground.
        val foregroundType = if (isBgCamera) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        }

        val notification = buildNotification(app.wsClient?.connectionState?.value ?: "Disconnected")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && foregroundType != 0) {
                if (currentForegroundType != foregroundType) {
                    startForeground(NOTIFICATION_ID, notification, foregroundType)
                    currentForegroundType = foregroundType
                } else {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            } else {
                if (currentForegroundType != 0) {
                    startForeground(NOTIFICATION_ID, notification)
                    currentForegroundType = 0
                } else {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        } catch (e: Exception) {
            Log.e("CapturePortService", "Failed to start foreground service: ${e.message}")
        }
    }

    private fun startTrackingConnectionState() {
        if (connectionStateJob != null) return
        
        val app = CapturePortApp.instance
        val wsClient = app.wsClient ?: return

        connectionStateJob = lifecycleScope.launch {
            wsClient.connectionState.collectLatest { state ->
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun stopTrackingConnectionState() {
        connectionStateJob?.cancel()
        connectionStateJob = null
    }

    fun updateCameraLifecycleBinding() {
        val app = CapturePortApp.instance
        val isBgCamera = app.cameraCapturePolicy == CameraCapturePolicy.Background
        val isActivityVisible = app.isActivityVisible

        lifecycleScope.launch {
            if (isBgCamera && !isActivityVisible) {
                Log.i("CapturePortService", "Binding camera to background service lifecycle")
                app.cameraController.bindToLifecycle(this@CapturePortService)
            } else {
                Log.i("CapturePortService", "Unbinding camera from background service lifecycle (Activity visible or ScreenOnly mode)")
                // Only unbind if it's currently bound to this service
                app.cameraController.unbind(this@CapturePortService)
            }
        }
    }

    private fun buildNotification(connectionState: String): Notification {
        val app = CapturePortApp.instance
        val isBgCamera = app.cameraCapturePolicy == CameraCapturePolicy.Background
        val isActivityVisible = app.isActivityVisible

        val contentText = when (connectionState) {
            "Connected" -> "Connected to PC receiver"
            "Disconnected" -> "Offline"
            else -> connectionState
        }

        val subText = if (isBgCamera && !isActivityVisible) {
            "Background camera capture mode active"
        } else {
            "Smart background connection active"
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Using android default icons or system symbols
        val smallIcon = if (connectionState == "Connected") {
            android.R.drawable.presence_online
        } else {
            android.R.drawable.presence_invisible
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CapturePort")
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(smallIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "CapturePort Service Channel"
            val desc = "Notification for background connection status and camera capture bindings"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTrackingConnectionState()
        stopObservingAppState()
    }
}
