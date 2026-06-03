package dev.captureport.app.pairing

import android.os.Build
import android.util.Base64
import dev.captureport.app.network.NetworkHelper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.captureport.app.core.crypto.Ed25519KeyManager
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.data.datastore.PairedDevicesRepository
import dev.captureport.app.network.EnvelopeCodec
import dev.captureport.app.network.EnvelopeCodec.Envelope
import dev.captureport.app.network.EndpointTarget
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
    data class Error(val message: String, val canRetryManual: Boolean = false) : PairingState
}

class PairingViewModel(
    private val repository: PairedDevicesRepository
) : ViewModel() {

    private data class PairingRequest(
        val localHosts: List<String>,
        val localPort: Int,
        val internetHost: String?,
        val internetPort: Int?,
        val targets: List<EndpointTarget>,
        val pcPublicKeyB64: String,
        val pcName: String,
        val pcOs: String,
        val pcNonceB64: String,
        val pcSigB64: String
    )

    private val _uiState = MutableStateFlow<PairingState>(PairingState.Idle)
    val uiState: StateFlow<PairingState> = _uiState

    private val okHttpClient: OkHttpClient by lazy {
        val context = dev.captureport.app.CapturePortApp.instance
        NetworkHelper.getSharedClient(context)
    }

    private var pairingSocket: WebSocket? = null
    private val pairingAttemptCounter = AtomicLong(0)
    private var lastPairingRequest: PairingRequest? = null

    private fun nextAttemptId(): Long = pairingAttemptCounter.incrementAndGet()

    private fun isCurrentAttempt(attemptId: Long): Boolean = pairingAttemptCounter.get() == attemptId

    fun resetScanning() {
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Scanning
    }

    fun reset() {
        resetScanning()
    }

    fun showError(message: String) {
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Error(message, canRetryManual = lastPairingRequest != null)
    }

    fun pairWithManualIp(manualIp: String) {
        val req = lastPairingRequest ?: return
        var host = manualIp.trim()
        if (host.isEmpty()) return

        // Strip potential URL schemes and paths
        if (host.startsWith("ws://", ignoreCase = true)) host = host.substring(5)
        else if (host.startsWith("wss://", ignoreCase = true)) host = host.substring(6)
        else if (host.startsWith("http://", ignoreCase = true)) host = host.substring(7)
        else if (host.startsWith("https://", ignoreCase = true)) host = host.substring(8)
        
        val slashIndex = host.indexOf('/')
        if (slashIndex != -1) {
            host = host.substring(0, slashIndex)
        }

        // Parse host[:port], supporting bracketed IPv6 literals like [::1]:7878.
        // A bare IPv6 literal (multiple colons, no brackets) is kept as a single host
        // with the default port, since splitting on the last ':' would be ambiguous.
        val (targetHost, targetPort) = if (host.startsWith("[")) {
            val endBracket = host.indexOf(']')
            if (endBracket > 0) {
                val inner = host.substring(1, endBracket)
                val portStr = host.substring(endBracket + 1).removePrefix(":")
                inner to (portStr.toIntOrNull() ?: req.localPort)
            } else {
                host to req.localPort
            }
        } else {
            val lastColon = host.lastIndexOf(':')
            val parsedPort = if (lastColon > 0) host.substring(lastColon + 1).toIntOrNull() else null
            if (parsedPort != null && host.indexOf(':') == lastColon) {
                host.substring(0, lastColon) to parsedPort
            } else {
                host to req.localPort
            }
        }

        val newRequest = req.copy(
            localHosts = listOf(targetHost),
            localPort = targetPort,
            targets = listOf(EndpointTarget(targetHost, targetPort))
        )
        val attemptId = nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Connecting

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
            attemptPairing(newRequest, phonePubKeyB64, attemptId, 0)
        }
    }

    // Starts challenge-response handshake sequence from QR parameters
    fun startPairing(
        localHosts: List<String>,
        localPort: Int,
        internetHost: String?,
        internetPort: Int?,
        endpointMode: String,
        pcPublicKeyB64: String,
        pcName: String,
        pcOs: String,
        pcNonceB64: String,
        pcSigB64: String
    ) {
        val candidateLocalHosts = localHosts.map(String::trim).filter(String::isNotBlank).distinct()
        val cleanInternetHost = internetHost?.trim()?.takeIf { it.isNotEmpty() }
        val cleanInternetPort = internetPort?.takeIf { it in 1..65535 }
        val targets = buildPairingTargets(candidateLocalHosts, localPort, cleanInternetHost, cleanInternetPort, endpointMode)
        if (targets.isEmpty() || localPort !in 1..65535 || pcPublicKeyB64.isBlank() || pcNonceB64.isBlank() || pcSigB64.isBlank()) {
            showError("Invalid pairing QR. Regenerate the QR code on your PC and scan again.")
            return
        }

        val attemptId = nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
        _uiState.value = PairingState.Connecting

        val pairingRequest = PairingRequest(
            localHosts = candidateLocalHosts,
            localPort = localPort,
            internetHost = cleanInternetHost,
            internetPort = cleanInternetPort,
            targets = targets,
            pcPublicKeyB64 = pcPublicKeyB64,
            pcName = pcName,
            pcOs = pcOs,
            pcNonceB64 = pcNonceB64,
            pcSigB64 = pcSigB64
        )
        lastPairingRequest = pairingRequest

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

    private fun buildPairingTargets(
        localHosts: List<String>,
        localPort: Int,
        internetHost: String?,
        internetPort: Int?,
        endpointMode: String,
    ): List<EndpointTarget> {
        val localTargets = localHosts.map { EndpointTarget(it, localPort) }
        val internetTarget = internetHost?.let { EndpointTarget(it, internetPort ?: localPort) }
        return when (endpointMode) {
            "internet-only" -> listOfNotNull(internetTarget)
            "local-then-internet" -> localTargets + listOfNotNull(internetTarget)
            else -> localTargets
        }.distinct()
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

        val target = requestData.targets.getOrNull(hostIndex)
        if (target == null) {
            _uiState.value = PairingState.Error(
                "Couldn't reach the PC on any advertised network address. Make sure both devices are on the same network or that your internet endpoint is reachable."
            )
            return
        }
        val host = target.host
        val port = target.port

        val socketRequest = try {
            Request.Builder()
                .url("ws://$host:$port/ws")
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e("PairingViewModel", "Invalid pairing URL: ${e.message}")
            if (hostIndex + 1 < requestData.targets.size) {
                attemptPairing(requestData, phonePubKeyB64, attemptId, hostIndex + 1)
            } else {
                _uiState.value = PairingState.Error("Invalid pairing address. Regenerate the QR code on your PC and scan again.")
            }
            return
        }

        var handshakeStarted = false
        val listener = object : WebSocketListener() {
            private var currentDeviceId: String? = null

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
                        if (status == "verified") {
                            val deviceId = envelope.result.optString("device_id")
                            if (deviceId.isNotEmpty()) {
                                currentDeviceId = deviceId
                            }
                            val fingerprint = envelope.result.getString("fingerprint_phone")

                            _uiState.value = PairingState.FingerprintVerification(fingerprint) {
                                _uiState.value = PairingState.Handshaking
                                val confirmId = "P_CONFIRM_" + System.nanoTime()
                                val confirmReq = Envelope(
                                    t = "req",
                                    id = confirmId,
                                    method = "pair_confirm",
                                    params = JSONObject()
                                )
                                webSocket.send(EnvelopeCodec.encodeEnvelope(confirmReq))
                            }
                        } else if (status == "paired") {
                            val deviceId = envelope.result.optString("device_id").takeIf { it.isNotEmpty() } ?: currentDeviceId ?: ""
                            val token = envelope.result.getString("token")
                            val pcPublicKeyBytes = Base64.decode(requestData.pcPublicKeyB64, Base64.URL_SAFE or Base64.NO_PADDING)

                            val newDevice = PairedDevice.newBuilder()
                                .setId(deviceId)
                                .setName(requestData.pcName)
                                .setOs(requestData.pcOs)
                                .setHost(requestData.localHosts.joinToString(","))
                                .setPort(requestData.localPort)
                                .setLocalHosts(requestData.localHosts.joinToString(","))
                                .setLocalPort(requestData.localPort)
                                .setToken(token)
                                .setPublicKey(com.google.protobuf.ByteString.copyFrom(pcPublicKeyBytes))
                                .setLastSeenMs(System.currentTimeMillis())
                                .build()
                                .toBuilder()
                                .apply {
                                    requestData.internetHost?.let { setInternetHost(it) }
                                    requestData.internetPort?.let { setInternetPort(it) }
                                }
                                .build()

                            viewModelScope.launch(Dispatchers.IO) {
                                repository.addDevice(newDevice)
                                _uiState.value = PairingState.Success
                                webSocket.close(1000, "Pairing success")
                                pairingSocket = null
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
                if (!handshakeStarted && hostIndex + 1 < requestData.targets.size) {
                    attemptPairing(requestData, phonePubKeyB64, attemptId, hostIndex + 1)
                    return
                }

                if (_uiState.value is PairingState.Error || _uiState.value is PairingState.Success) {
                    return
                }

                val attemptedHosts = requestData.targets.take(hostIndex + 1).joinToString(", ") { "${it.host}:${it.port}" }
                val message = if (!handshakeStarted && requestData.targets.size > 1) {
                    "Couldn't reach the PC on the advertised network addresses: $attemptedHosts. Make sure local network access or the internet endpoint is available."
                } else {
                    "Network failure connecting: ${t.message ?: "unknown error"}"
                }
                _uiState.value = PairingState.Error(message, canRetryManual = true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (pairingSocket === webSocket) {
                    pairingSocket = null
                }
            }
        }

        pairingSocket = okHttpClient.newWebSocket(socketRequest, listener)
    }

    override fun onCleared() {
        super.onCleared()
        nextAttemptId()
        pairingSocket?.cancel()
        pairingSocket = null
    }
}
