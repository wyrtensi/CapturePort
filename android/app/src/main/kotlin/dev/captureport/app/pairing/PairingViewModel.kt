package dev.captureport.app.pairing

import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.captureport.app.core.crypto.Ed25519KeyManager
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.EnvelopeCodec
import dev.captureport.app.network.EnvelopeCodec.Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed interface PairingState {
    object Idle : PairingState
    object Scanning : PairingState
    object Connecting : PairingState
    object Handshaking : PairingState
    data class FingerprintVerification(val fingerprint: String, val onConfirm: () -> Unit) : PairingState
    object Success : PairingState
    data class Error(val message: String) : PairingState
}

class PairingViewModel(
    private val repository: PairedDevicesRepository
) : ViewModel() {

    private data class PairingRequest(
        val hosts: List<String>,
        val port: Int,
        val pcPublicKeyB64: String,
        val pcName: String,
        val pcOs: String,
        val pcNonceB64: String,
        val pcSigB64: String
    )

    private val _uiState = MutableStateFlow<PairingState>(PairingState.Idle)
    val uiState: StateFlow<PairingState> = _uiState

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pairingSocket: WebSocket? = null
    private val pairingAttemptCounter = AtomicLong(0)

    private fun nextAttemptId(): Long = pairingAttemptCounter.incrementAndGet()

    private fun isCurrentAttempt(attemptId: Long): Boolean = pairingAttemptCounter.get() == attemptId

    fun resetScanning() {
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Scanning
    }

    fun showError(message: String) {
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Error(message)
    }

    // Starts challenge-response handshake sequence from QR parameters
    fun startPairing(
        hosts: List<String>,
        port: Int,
        pcPublicKeyB64: String,
        pcName: String,
        pcOs: String,
        pcNonceB64: String,
        pcSigB64: String
    ) {
        val candidateHosts = hosts.map(String::trim).filter(String::isNotBlank).distinct()
        if (candidateHosts.isEmpty() || port !in 1..65535 || pcPublicKeyB64.isBlank() || pcNonceB64.isBlank() || pcSigB64.isBlank()) {
            showError("Invalid pairing QR. Regenerate the QR code on your PC and scan again.")
            return
        }

        val attemptId = nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Connecting

        val pairingRequest = PairingRequest(
            hosts = candidateHosts,
            port = port,
            pcPublicKeyB64 = pcPublicKeyB64,
            pcName = pcName,
            pcOs = pcOs,
            pcNonceB64 = pcNonceB64,
            pcSigB64 = pcSigB64
        )

        viewModelScope.launch(Dispatchers.IO) {
            val phonePubKey = try {
                Ed25519KeyManager.getRawPublicKey()
            } catch (e: Throwable) {
                Log.e("PairingViewModel", "Failed to access Ed25519 key pair: ${e.message}", e)
                if (isCurrentAttempt(attemptId)) {
                    _uiState.value = PairingState.Error(
                        "Couldn't initialize the device identity key. Please update the app and try again."
                    )
                }
                return@launch
            }
            val phonePubKeyB64 = Base64.encodeToString(phonePubKey, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            attemptPairing(pairingRequest, phonePubKeyB64, attemptId, 0)
        }
    }

    private fun attemptPairing(
        requestData: PairingRequest,
        phonePubKeyB64: String,
        attemptId: Long,
        hostIndex: Int
    ) {
        if (!isCurrentAttempt(attemptId)) {
            return
        }

        val host = requestData.hosts.getOrNull(hostIndex)
        if (host == null) {
            _uiState.value = PairingState.Error(
                "Couldn't reach the PC on any advertised network address. Make sure both devices are on the same network and that your OS firewall allows CapturePort on port ${requestData.port}."
            )
            return
        }

        val socketRequest = try {
            Request.Builder()
                .url("ws://$host:${requestData.port}/ws")
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e("PairingViewModel", "Invalid pairing URL: ${e.message}")
            if (hostIndex + 1 < requestData.hosts.size) {
                attemptPairing(requestData, phonePubKeyB64, attemptId, hostIndex + 1)
            } else {
                _uiState.value = PairingState.Error("Invalid pairing address. Regenerate the QR code on your PC and scan again.")
            }
            return
        }

        var handshakeStarted = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrentAttempt(attemptId)) {
                    webSocket.cancel()
                    return
                }

                handshakeStarted = true
                pairingSocket = webSocket
                _uiState.value = PairingState.Handshaking

                val helloId = "P_HELLO_" + System.nanoTime()
                val phoneName = Build.MODEL

                val helloReq = Envelope(
                    t = "req",
                    id = helloId,
                    method = "hello",
                    params = JSONObject().apply {
                        put("pubkey_phone", phonePubKeyB64)
                        put("device_name", phoneName)
                        put("os", "android")
                        put("qr_nonce", requestData.pcNonceB64)
                        put("qr_sig", requestData.pcSigB64)
                    }
                )
                webSocket.send(EnvelopeCodec.encodeEnvelope(helloReq))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrentAttempt(attemptId)) {
                    webSocket.cancel()
                    return
                }

                try {
                    val envelope = EnvelopeCodec.decodeEnvelope(text)

                    if (envelope.method == "challenge") {
                        val pcNonceStr = envelope.params?.optString("nonce") ?: ""
                        val pcNonceBytes = Base64.decode(pcNonceStr, Base64.URL_SAFE or Base64.NO_PADDING)

                        val signature = try {
                            Ed25519KeyManager.signChallenge(pcNonceBytes)
                        } catch (e: Throwable) {
                            Log.e("PairingViewModel", "Failed to sign PC challenge: ${e.message}", e)
                            pairingSocket = null
                            _uiState.value = PairingState.Error(
                                "Couldn't sign the PC challenge. Please update the app and try again."
                            )
                            webSocket.close(1000, "Sign failure")
                            return
                        }
                        val signatureB64 = Base64.encodeToString(signature, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

                        val resp = Envelope(
                            t = "resp",
                            id = envelope.id,
                            result = JSONObject().apply {
                                put("sig", signatureB64)
                            }
                        )
                        webSocket.send(EnvelopeCodec.encodeEnvelope(resp))
                    } else if (envelope.t == "resp" && envelope.result != null) {
                        val status = envelope.result.optString("status")
                        if (status == "paired") {
                            val deviceId = envelope.result.getString("device_id")
                            val token = envelope.result.getString("token")
                            val fingerprint = envelope.result.getString("fingerprint_phone")
                            val pcPublicKeyBytes = Base64.decode(requestData.pcPublicKeyB64, Base64.URL_SAFE or Base64.NO_PADDING)

                            val newDevice = PairedDevice.newBuilder()
                                .setId(deviceId)
                                .setName(requestData.pcName)
                                .setOs(requestData.pcOs)
                                .setHost(host)
                                .setPort(requestData.port)
                                .setToken(token)
                                .setPublicKey(com.google.protobuf.ByteString.copyFrom(pcPublicKeyBytes))
                                .setLastSeenMs(System.currentTimeMillis())
                                .build()

                            _uiState.value = PairingState.FingerprintVerification(fingerprint) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    repository.addDevice(newDevice)
                                    _uiState.value = PairingState.Success
                                    webSocket.close(1000, "Pairing success")
                                    pairingSocket = null
                                }
                            }
                        }
                    } else if (envelope.error != null) {
                        val errMsg = envelope.error.optString("message", "Unknown error")
                        pairingSocket = null
                        _uiState.value = PairingState.Error("Pairing rejected: $errMsg")
                        webSocket.close(1000, "Pairing rejected")
                    }
                } catch (e: Exception) {
                    Log.e("PairingViewModel", "Handshake parse error: ${e.message}")
                    pairingSocket = null
                    _uiState.value = PairingState.Error("Handshake logic error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrentAttempt(attemptId)) {
                    return
                }

                pairingSocket = null
                if (!handshakeStarted && hostIndex + 1 < requestData.hosts.size) {
                    attemptPairing(requestData, phonePubKeyB64, attemptId, hostIndex + 1)
                    return
                }

                if (_uiState.value is PairingState.Error || _uiState.value is PairingState.Success) {
                    return
                }

                val attemptedHosts = requestData.hosts.take(hostIndex + 1).joinToString(", ")
                val message = if (!handshakeStarted && requestData.hosts.size > 1) {
                    "Couldn't reach the PC on the advertised network addresses: $attemptedHosts. Make sure both devices are on the same network and that your OS firewall allows CapturePort on port ${requestData.port}."
                } else {
                    "Network failure connecting: ${t.message ?: "unknown error"}"
                }
                _uiState.value = PairingState.Error(message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (pairingSocket === webSocket) {
                    pairingSocket = null
                }
            }
        }

        pairingSocket = client.newWebSocket(socketRequest, listener)
    }

    override fun onCleared() {
        super.onCleared()
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
    }
}
