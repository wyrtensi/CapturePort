package dev.captureport.app.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import dev.captureport.app.core.crypto.Ed25519KeyManager
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.network.EnvelopeCodec.Envelope
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

class WsClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCaptureRequest: (
        onPhotoSnapped: (File) -> Unit,
        onCaptureRejected: (String) -> Unit
    ) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive socket
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnecting = false
    private var reconnectJob: Job? = null
    
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private var activeDevice: PairedDevice? = null

    // Connects to target paired PC receiver
    fun connect(device: PairedDevice) {
        activeDevice = device
        disconnect() // Cleanly shut down existing socket and cancel background loop
        
        scope.launch(Dispatchers.IO) {
            reconnectJob = launch {
                val attempt = java.util.concurrent.atomic.AtomicInteger(0)
                val delayMs = java.util.concurrent.atomic.AtomicLong(1000L)
                _connectionState.value = "Connecting..."

                try {
                    while (isActive) {
                        val host = device.host
                        val port = device.port
                        val request = Request.Builder()
                            .url("ws://$host:$port/ws")
                            .build()

                        val listener = object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                tracingLog("Socket opened, sending auth hello...")
                                attempt.set(0)
                                delayMs.set(1000L)
                                
                                // Send hello authentication envelope
                                val helloId = ulid()
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
                                scope.launch(Dispatchers.IO) {
                                    handleTextMessage(webSocket, text)
                                }
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                _connectionState.value = "Disconnected"
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                _connectionState.value = "Disconnected"
                            }
                        }

                        tracingLog("Attempting connection to ws://$host:$port/ws...")
                        val currentWs = client.newWebSocket(request, listener)
                        webSocket = currentWs

                        // Monitor state and wait if dropped
                        val startTime = System.currentTimeMillis()
                        while (_connectionState.value == "Connecting..." || _connectionState.value == "Connected") {
                            delay(500)
                            if (!isActive) break
                            
                            // 5-second handshake timeout: if socket is open but remains unauthenticated
                            if (_connectionState.value == "Connecting..." && (System.currentTimeMillis() - startTime) > 5000) {
                                tracingLog("Authorization handshake timed out after 5s. Reconnecting...")
                                currentWs.close(1000, "Handshake timeout")
                                break
                            }
                        }

                        attempt.incrementAndGet()
                        _connectionState.value = "Reconnecting (Attempt ${attempt.get()})..."
                        val jitter = (0..500).random()
                        delay(delayMs.get() + jitter)
                        delayMs.set((delayMs.get() * 2).coerceAtMost(30000L))
                    }
                } finally {
                    webSocket?.close(1000, "Connection cancelled")
                    webSocket = null
                    _connectionState.value = "Disconnected"
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
                    "capture_photo" -> {
                        // Trigger Camera Snap
                        onCaptureRequest(
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
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)

        val outStream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
        bmp.recycle()
        return outStream.toByteArray()
    }

    // Push standard user photo captures to current PC clipboard
    fun pushPhoto(file: File) {
        val ws = webSocket ?: return
        if (_connectionState.value != "Connected") return

        scope.launch(Dispatchers.IO) {
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

                ws.send(binaryFrame.toByteString())
                tracingLog("Photo pushed to PC clipboard successfully!")
            } catch (e: Exception) {
                Log.e("WsClient", "Push photo failed: ${e.message}")
            }
        }
    }

    // Expose WebSocket handle directly for video chunking service access
    fun getActiveWebSocket(): WebSocket? = webSocket

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = "Disconnected"
    }

    private fun tracingLog(msg: String) {
        Log.i("WsClient", msg)
    }

    private fun ulid(): String = java.util.UUID.randomUUID().toString()
}
