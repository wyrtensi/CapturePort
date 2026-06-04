package dev.captureport.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.captureport.app.CapturePortApp
import dev.captureport.app.network.EnvelopeCodec
import dev.captureport.app.network.EnvelopeCodec.Envelope
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.*

class TransferService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val CHANNEL_ID = "CapturePortTransferChannel"
    private val NOTIFICATION_ID = 101

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote service to foreground immediately to satisfy the Android OS 5-second window
        val initialNotification = buildNotification("Preparing transfer...", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                initialNotification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        val filePath = intent?.getStringExtra("file_path") ?: ""
        val requestId = intent?.getStringExtra("request_id") ?: "user_push_video"

        val file = File(filePath)
        if (!file.exists()) {
            updateNotificationFailure("Transfer failed: file does not exist")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Begin background streaming upload
        serviceScope.launch {
            uploadVideoFile(file, requestId)
        }

        return START_NOT_STICKY
    }

    // Splits the video file into 256 KB chunks and streams them over WebSocket
    private suspend fun uploadVideoFile(file: File, requestId: String) {
        val wsClient = CapturePortApp.instance.wsClient
        val ws = wsClient?.getActiveWebSocket()

        if (ws == null) {
            updateNotificationFailure("Transfer failed: Receiver offline")
            stopSelf()
            return
        }

        val totalSize = file.length()
        val chunkSize = 256 * 1024 // 256 KB
        val totalChunks = ((totalSize + chunkSize - 1) / chunkSize).toInt()

        val metaJson = JSONObject().apply {
            put("request_id", requestId)
        }.toString()

        try {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(chunkSize)
                var bytesRead = 0
                var chunkSeq = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

                    val chunkBytes = if (bytesRead < chunkSize) {
                        buffer.take(bytesRead).toByteArray()
                    } else {
                        buffer
                    }

                    chunkSeq++
                    val isLast = chunkSeq == totalChunks
                    val flags = if (isLast) 1 else 0

                    val binaryFrame = EnvelopeCodec.encodeBinaryFrame(
                        streamId = 1, // Video Chunk Stream
                        frameSeq = chunkSeq,
                        flags = flags,
                        totalSize = totalSize,
                        metaJson = metaJson,
                        payload = chunkBytes
                    )

                    // Write binary sequence directly to WebSocket
                    val sent = ws.send(binaryFrame.toByteString())
                    if (!sent) {
                        Log.e("TransferService", "WebSocket send failed, aborting transfer")
                        updateNotificationFailure("Transfer failed: Connection lost")
                        return
                    }

                    // Update Notification Progress bar
                    val progress = ((chunkSeq.toFloat() / totalChunks.toFloat()) * 100).toInt()
                    updateNotificationProgress(progress)

                    // Yield 40ms to prevent saturating the sockets or dropping TCP buffers
                    delay(40)
                }
            }

            // Pushes matching response envelope to mark completion
            val completionEnv = Envelope(
                t = "resp",
                id = requestId,
                result = JSONObject().apply {
                    put("status", "done")
                }
            )
            ws.send(EnvelopeCodec.encodeEnvelope(completionEnv))
            Log.i("TransferService", "Video uploaded successfully: ${file.name}")
        } catch (e: Exception) {
            Log.e("TransferService", "Streaming upload failed: ${e.message}")
            updateNotificationFailure("Video upload failed")
        } finally {
            // Clean up temp video file from disk
            if (file.exists()) {
                file.delete()
            }
            // Self terminate cleanly on complete
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CapturePort Transfer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationProgress(progress: Int) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = buildNotification("Uploading video... ($progress%)", progress)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationFailure(errorText: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CapturePort Transfer Failed")
            .setContentText(errorText)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Video Transfer Channel"
            val desc = "Notification for background media socket transfer progress"
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
        serviceJob.cancel()
    }
}
