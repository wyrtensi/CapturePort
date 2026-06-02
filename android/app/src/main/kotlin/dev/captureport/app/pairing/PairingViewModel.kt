package dev.captureport.app.pairing

import android.content.Context
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
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    private val _uiState = MutableStateFlow<PairingState>(PairingState.Idle)
    val uiState: StateFlow<PairingState> = _uiState

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pairingSocket: WebSocket? = null

    // Starts challenge-response handshake sequence from QR parameters
    fun startPairing(
        context: Context,
        host: String,
        port: Int,
        pcPublicKeyB64: String,
        pcName: String,
        pcOs: String,
        pcNonceB64: String,
        pcSigB64: String
    ) {
        _uiState.value = PairingState.Connecting
        
        viewModelScope.launch(Dispatchers.IO) {
            val phonePubKey = Ed25519KeyManager.getRawPublicKey()
            val phonePubKeyB64 = Base64.encodeToString(phonePubKey, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            val request = Request.Builder()
                .url("ws://$host:$port/ws")
                .build()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _uiState.value = PairingState.Handshaking
                    
                    // 1. Send first hello envelope containing phone public key, device name, and OS
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
                            put("qr_nonce", pcNonceB64)
                            put("qr_sig", pcSigB64)
                        }
                    )
                    webSocket.send(EnvelopeCodec.encodeEnvelope(helloReq))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val envelope = EnvelopeCodec.decodeEnvelope(text)
                        
                        if (envelope.method == "challenge") {
                            // 2. Respond to PC challenge
                            val pcNonceStr = envelope.params?.optString("nonce") ?: ""
                            val pcNonceBytes = Base64.decode(pcNonceStr, Base64.URL_SAFE or Base64.NO_PADDING)
                            
                            val signature = Ed25519KeyManager.signChallenge(pcNonceBytes)
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
                                // 3. Handshake verify fingerprint and store device persistence
                                val deviceId = envelope.result.getString("device_id")
                                val token = envelope.result.getString("token")
                                val fingerprint = envelope.result.getString("fingerprint_phone")
                                
                                val pcPublicKeyBytes = Base64.decode(pcPublicKeyB64, Base64.URL_SAFE or Base64.NO_PADDING)

                                val newDevice = PairedDevice.newBuilder()
                                    .setId(deviceId)
                                    .setName(pcName)
                                    .setOs(pcOs)
                                    .setHost(host)
                                    .setPort(port)
                                    .setToken(token)
                                    .setPublicKey(com.google.protobuf.ByteString.copyFrom(pcPublicKeyBytes))
                                    .setLastSeenMs(System.currentTimeMillis())
                                    .build()

                                _uiState.value = PairingState.FingerprintVerification(fingerprint) {
                                    viewModelScope.launch(Dispatchers.IO) {
                                        repository.addDevice(newDevice)
                                        _uiState.value = PairingState.Success
                                        webSocket.close(1000, "Pairing success")
                                    }
                                }
                            }
                        } else if (envelope.error != null) {
                            val errMsg = envelope.error.optString("message", "Unknown error")
                            _uiState.value = PairingState.Error("Pairing rejected: $errMsg")
                            webSocket.close(1000, "Pairing rejected")
                        }
                    } catch (e: Exception) {
                        Log.e("PairingViewModel", "Handshake parse error: ${e.message}")
                        _uiState.value = PairingState.Error("Handshake logic error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _uiState.value = PairingState.Error("Network failure connecting: ${t.message}")
                }
            }

            pairingSocket = client.newWebSocket(request, listener)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pairingSocket?.close(1000, "ViewModel cleared")
    }
}
