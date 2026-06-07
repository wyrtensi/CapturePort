package dev.captureport.app.network

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import dev.captureport.app.CapturePortApp
import dev.captureport.app.ReceiverConnectionMode
import dev.captureport.app.core.crypto.Ed25519KeyManager
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.network.EnvelopeCodec.Envelope
import dev.captureport.app.transfer.TransferService
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EndpointTarget(val host: String, val port: Int)

class WsClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCaptureRequest: (
        useFlash: Boolean,
        onPhotoSnapped: (File) -> Unit,
        onCaptureRejected: (String) -> Unit
    ) -> Unit
) {
    private val okHttpClient: OkHttpClient by lazy {
        NetworkHelper.getSharedClient(context)
    }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private val connectionAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private var isRecordingVideo = false
    
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private var activeDevice: PairedDevice? = null
    private val pendingPhotoUploads = java.util.concurrent.ConcurrentLinkedQueue<File>()

    companion object {
        fun isConnectionLoopActive(state: String): Boolean {
            return state == "Connected" ||
                state.startsWith("Connecting") ||
                state.startsWith("Reconnecting")
        }

        fun endpointTargets(
            device: PairedDevice,
            mode: ReceiverConnectionMode,
        ): List<EndpointTarget> {
            val localPort = device.localPort.takeIf { it > 0 } ?: device.port
            val localHosts = (device.localHosts.ifBlank { device.host })
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { EndpointTarget(it, localPort) }

            val internetTarget = device.internetHost
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { EndpointTarget(it, device.internetPort.takeIf { port -> port > 0 } ?: device.port) }

            return when (mode) {
                ReceiverConnectionMode.LocalOnly -> localHosts
                ReceiverConnectionMode.LocalThenInternet -> localHosts + listOfNotNull(internetTarget)
                ReceiverConnectionMode.InternetOnly -> listOfNotNull(internetTarget)
            }.distinct()
        }
    }

    // Connects to target paired PC receiver using sequential host list retry fallback
    fun connect(device: PairedDevice) {
        disconnect() // Cleanly shut down existing socket and cancel background loop
        activeDevice = device
        _connectionState.value = "Connecting..."
        
        val attemptId = connectionAttempt.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            reconnectJob = launch {
                val attempt = java.util.concurrent.atomic.AtomicInteger(0)
                val delayMs = java.util.concurrent.atomic.AtomicLong(1000L)

                try {
                    while (isActive && connectionAttempt.get() == attemptId) {
                        val targets = endpointTargets(device, CapturePortApp.instance.receiverConnectionMode)
                        var connected = false

                        for (target in targets) {
                            val host = target.host
                            val port = target.port
                            if (!isActive || connectionAttempt.get() != attemptId) break
                            _connectionState.value = "Connecting to $host..."

                            val hostSuccess = CompletableDeferred<Boolean>()
                            val helloId = ulid()

                            val listener = object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    if (connectionAttempt.get() != attemptId) {
                                        webSocket.cancel()
                                        return
                                    }
                                    tracingLog("Socket opened to $host, sending auth hello...")
                                    
                                    // Send hello authentication envelope
                                    val helloReq = Envelope(
                                        t = "req",
                                        id = helloId,
                                        method = "hello",
                                        params = JSONObject().apply {
                                            put("device_id", device.id)
                                            put("token", device.token)
                                        }
                                    )
                                    webSocket.send(EnvelopeCodec.encodeEnvelope(helloReq))
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    if (connectionAttempt.get() != attemptId) {
                                        webSocket.cancel()
                                        return
                                    }
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val envelope = EnvelopeCodec.decodeEnvelope(text)
                                            if (envelope.t == "resp" && envelope.id == helloId) {
                                                if (envelope.result != null) {
                                                    val status = envelope.result.optString("status")
                                                    if (status == "authorized") {
                                                        _connectionState.value = "Connected"
                                                        hostSuccess.complete(true)
                                                        flushPendingUploads(webSocket)
                                                    }
                                                } else if (envelope.error != null) {
                                                    Log.e("WsClient", "Auth rejected: ${envelope.error.optString("message")}")
                                                    hostSuccess.complete(false)
                                                }
                                            }
                                            handleTextMessage(webSocket, text)
                                        } catch (e: Exception) {
                                            Log.e("WsClient", "Auth error: ${e.message}")
                                        }
                                    }
                                }

                                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                    if (_connectionState.value == "Connected") {
                                        _connectionState.value = "Disconnected"
                                    }
                                }

                                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                    if (_connectionState.value == "Connected") {
                                        _connectionState.value = "Disconnected"
                                    }
                                }

                                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                    if (_connectionState.value == "Connected") {
                                        _connectionState.value = "Disconnected"
                                    }
                                    hostSuccess.complete(false)
                                }
                            }

                            tracingLog("Attempting connection to ws://$host:$port/ws...")
                            val request = Request.Builder()
                                .url("ws://$host:$port/ws")
                                .build()
                            val currentWs = okHttpClient.newWebSocket(request, listener)
                            
                            if (connectionAttempt.get() == attemptId) {
                                webSocket = currentWs
                            } else {
                                currentWs.cancel()
                                break
                            }

                            val isOk = withTimeoutOrNull(3000) { hostSuccess.await() } ?: false
                            if (isOk) {
                                connected = true
                                attempt.set(0)
                                delayMs.set(1000L)
                                while (_connectionState.value == "Connected" && isActive) {
                                    delay(500)
                                }
                                break
                            } else {
                                currentWs.cancel()
                            }
                        }

                        if (!connected && connectionAttempt.get() == attemptId) {
                            attempt.incrementAndGet()
                            _connectionState.value = "Reconnecting (Attempt ${attempt.get()})..."
                            val jitter = (0..500).random()
                            delay(delayMs.get() + jitter)
                            delayMs.set((delayMs.get() * 2).coerceAtMost(30000L))
                        }
                    }
                } finally {
                    if (connectionAttempt.get() == attemptId) {
                        if (_connectionState.value == "Connected") {
                            webSocket?.close(1000, "Connection cancelled")
                        } else {
                            webSocket?.cancel()
                        }
                        webSocket = null
                        _connectionState.value = "Disconnected"
                    }
                }
            }
        }
    }

    // Handles incoming JSON-RPC control frames
    private suspend fun handleTextMessage(ws: WebSocket, text: String) {
        try {
            val envelope = EnvelopeCodec.decodeEnvelope(text)
            
            if (envelope.t == "resp" && envelope.result != null) {
                val status = envelope.result.optString("status")
                if (status == "authorized") {
                    _connectionState.value = "Connected"
                    tracingLog("Authorized successfully!")
                }
            } else if (envelope.t == "req") {
                when (envelope.method) {
                    "challenge" -> {
                        // Handshake challenge verification
                        val nonceStr = envelope.params?.optString("nonce") ?: ""
                        val nonceBytes = Base64.decode(nonceStr, Base64.URL_SAFE or Base64.NO_PADDING)
                        
                        // Sign nonce natively
                        val signature = Ed25519KeyManager.signChallenge(nonceBytes)
                        val signatureB64 = Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                        val resp = Envelope(
                            t = "resp",
                            id = envelope.id,
                            result = JSONObject().apply {
                                put("sig", signatureB64)
                            }
                        )
                        ws.send(EnvelopeCodec.encodeEnvelope(resp))
                    }
                    "capture_photo", "capture_screenshot" -> {
                        val useFlash = envelope.params?.let { params ->
                            params.optBoolean("use_flash", params.optBoolean("flash", false))
                        } ?: false
                        // Trigger Camera Snap
                        onCaptureRequest(
                            useFlash,
                            { file ->
                                scope.launch(Dispatchers.IO) {
                                    uploadPhotoFile(ws, envelope.id, file)
                                }
                            },
                            { reason ->
                                sendCaptureRejected(ws, envelope.id, reason)
                            }
                        )
                    }
                    "set_flashlight" -> {
                        val enabled = envelope.params?.optBoolean("enabled") ?: false
                        val app = CapturePortApp.instance
                        val activeCam = app.cameraController
                        scope.launch(Dispatchers.Main) {
                            val ok = activeCam.setTorchEnabled(enabled)
                            scope.launch(Dispatchers.IO) {
                                if (ok) {
                                    val resp = Envelope(
                                        t = "resp",
                                        id = envelope.id,
                                        result = JSONObject().apply {
                                            put("status", "success")
                                            put("enabled", enabled)
                                        }
                                    )
                                    ws.send(EnvelopeCodec.encodeEnvelope(resp))
                                } else {
                                    sendCaptureRejected(ws, envelope.id, "flashlight unavailable")
                                }
                            }
                        }
                    }
                    "record_video" -> {
                        val duration = envelope.params?.optLong("duration_seconds") ?: 10L
                        val app = CapturePortApp.instance
                        val activeCam = app.cameraController
                        if (app.canServeRemoteVideoCapture()) {
                            if (isRecordingVideo || activeCam.isRecording) {
                                scope.launch(Dispatchers.IO) {
                                    sendCaptureRejected(ws, envelope.id, "Camera is already recording video")
                                }
                                return
                            }
                            isRecordingVideo = true
                            scope.launch(Dispatchers.Main) {
                                try {
                                    var videoFile: java.io.File? = null
                                    videoFile = activeCam.startVideoRecording { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            isRecordingVideo = false
                                            if (!event.hasError()) {
                                                val intent = Intent(context, TransferService::class.java).apply {
                                                    putExtra("file_path", videoFile?.absolutePath)
                                                    putExtra("request_id", envelope.id)
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                            } else {
                                                scope.launch(Dispatchers.IO) {
                                                    sendCaptureRejected(ws, envelope.id, "Video recording error: ${event.error}")
                                                }
                                            }
                                        }
                                    }
                                    delay(duration * 1000)
                                    activeCam.stopVideoRecording()
                                } catch (e: Exception) {
                                    isRecordingVideo = false
                                    scope.launch(Dispatchers.IO) {
                                        sendCaptureRejected(ws, envelope.id, "Failed to start recording: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            scope.launch(Dispatchers.IO) {
                                sendCaptureRejected(ws, envelope.id, "camera unavailable")
                            }
                        }
                    }
                    "get_device_clipboard" -> {
                        scope.launch(Dispatchers.Main) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clipData = clipboard.primaryClip
                            val textContent = if (clipData != null && clipData.itemCount > 0) {
                                clipData.getItemAt(0).text?.toString() ?: ""
                            } else {
                                ""
                            }
                            scope.launch(Dispatchers.IO) {
                                val resp = Envelope(
                                    t = "resp", id = envelope.id,
                                    result = JSONObject().apply { put("text", textContent) }
                                )
                                ws.send(EnvelopeCodec.encodeEnvelope(resp))
                            }
                        }
                    }
                    "set_device_clipboard" -> {
                        val newText = envelope.params?.optString("text") ?: ""
                        scope.launch(Dispatchers.Main) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clipData = android.content.ClipData.newPlainText("CapturePort", newText)
                            clipboard.setPrimaryClip(clipData)
                            scope.launch(Dispatchers.IO) {
                                val resp = Envelope(
                                    t = "resp", id = envelope.id,
                                    result = JSONObject().apply { put("status", "success") }
                                )
                                ws.send(EnvelopeCodec.encodeEnvelope(resp))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WsClient", "Failed to parse text envelope message: ${e.message}")
        }
    }

    private fun sendCaptureRejected(ws: WebSocket, requestId: String, reason: String) {
        val response = Envelope(
            t = "resp",
            id = requestId,
            error = JSONObject().apply {
                put("code", "camera_unavailable")
                put("message", reason)
            }
        )
        ws.send(EnvelopeCodec.encodeEnvelope(response))
        tracingLog("Rejected remote capture request: $reason")
    }

    // Processes, downscales, and uploads photo binary frames over socket
    private fun uploadPhotoFile(ws: WebSocket, requestId: String, file: File) {
        try {
            val compressedBytes = compressPhoto(file)
            // Clean up temp file after compression
            if (file.exists()) file.delete()
            
            // 1. Send MCP Capture Binary Frame (streamId = 2)
            val metaJson = JSONObject().apply {
                put("request_id", requestId)
            }.toString()

            val binaryFrame = EnvelopeCodec.encodeBinaryFrame(
                streamId = 2,
                frameSeq = 0,
                flags = 1, // Last chunk
                totalSize = compressedBytes.size.toLong(),
                metaJson = metaJson,
                payload = compressedBytes
            )

            ws.send(binaryFrame.toByteString())

            // 2. Respond Done JSON Envelope
            val response = Envelope(
                t = "resp",
                id = requestId,
                result = JSONObject().apply {
                    put("status", "done")
                    put("size", compressedBytes.size)
                }
            )
            ws.send(EnvelopeCodec.encodeEnvelope(response))
            tracingLog("Photo capture uploaded successfully!")
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            Log.e("WsClient", "Upload photo failed: ${e.message}")
        }
    }

    // High quality bitmap downscaling to target 1920 long edge
    private fun compressPhoto(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= 1920) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return ByteArray(0)

        val outStream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        bmp.recycle()
        return outStream.toByteArray()
    }

    // Push standard user photo captures to current PC clipboard
    fun pushPhoto(file: File) {
        val ws = webSocket
        val isConnected = _connectionState.value == "Connected"
        if (ws == null || !isConnected) {
            Log.i("WsClient", "Socket offline, queueing photo for upload on reconnection: ${file.name}")
            pendingPhotoUploads.add(file)
            return
        }

        scope.launch(Dispatchers.IO) {
            sendPhotoBytes(ws, file)
        }
    }

    private fun sendPhotoBytes(ws: WebSocket, file: File): Boolean {
        try {
            val compressedBytes = compressPhoto(file)
            val metaJson = JSONObject().apply {
                put("type", "photo")
            }.toString()

            val binaryFrame = EnvelopeCodec.encodeBinaryFrame(
                streamId = 0, // Photo Stream
                frameSeq = 0,
                flags = 1,
                totalSize = compressedBytes.size.toLong(),
                metaJson = metaJson,
                payload = compressedBytes
            )

            val sent = ws.send(binaryFrame.toByteString())
            return if (sent) {
                if (file.exists()) file.delete()
                tracingLog("Photo pushed to PC clipboard successfully!")
                true
            } else {
                Log.e("WsClient", "Failed to send photo, keeping in queue")
                pendingPhotoUploads.add(file)
                false
            }
        } catch (e: Exception) {
            if (file.exists()) file.delete()
            Log.e("WsClient", "Push photo failed: ${e.message}")
            return true
        }
    }

    private fun flushPendingUploads(ws: WebSocket) {
        scope.launch(Dispatchers.IO) {
            while (pendingPhotoUploads.isNotEmpty()) {
                val file = pendingPhotoUploads.poll() ?: break
                if (file.exists()) {
                    Log.i("WsClient", "Uploading queued photo: ${file.name}")
                    val sent = sendPhotoBytes(ws, file)
                    if (!sent) {
                        break
                    }
                }
            }
        }
    }

    // Expose WebSocket handle directly for video chunking service access
    fun getActiveWebSocket(): WebSocket? = webSocket

    fun disconnect() {
        connectionAttempt.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = "Disconnected"
    }

    private fun tracingLog(msg: String) {
        Log.i("WsClient", msg)
    }

    private fun ulid(): String = java.util.UUID.randomUUID().toString()
}
