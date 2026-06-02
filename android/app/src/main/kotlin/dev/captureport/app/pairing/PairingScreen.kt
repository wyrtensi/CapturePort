package dev.captureport.app.pairing

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.captureport.app.camera.CameraController

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    onPairingCompleted: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val uiState by viewModel.uiState.collectAsState()
    
    // Instantiate camera controller specifically for scanning QR code
    val cameraController = remember {
        CameraController(
            context,
            enableImageCapture = false,
            enableVideoCapture = false,
            enableImageAnalysis = true
        )
    }
    
    var isScanned by remember { mutableStateOf(false) }
    val latestUiState by rememberUpdatedState(uiState)
    val latestIsScanned by rememberUpdatedState(isScanned)

    LaunchedEffect(uiState) {
        if (uiState is PairingState.Success) {
            onPairingCompleted()
        }
    }

    LaunchedEffect(Unit) {
        isScanned = false
        viewModel.resetScanning()
    }

    val scanCallback: (String) -> Unit = { rawUrl ->
        if (!isScanned) {
            isScanned = true
            cameraController.stopQrScanning()

            val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
            if (uri == null || uri.scheme != "captureport" || uri.host != "pair") {
                viewModel.showError("Invalid pairing QR. Regenerate the QR code on your PC and scan again.")
            } else {
                val host = uri.getQueryParameter("host") ?: ""
                val hosts = buildList {
                    addAll(
                        uri.getQueryParameter("hosts")
                            ?.split(',')
                            .orEmpty()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                    )
                    if (host.isNotBlank()) {
                        add(host)
                    }
                }.distinct()
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: -1
                val pk = uri.getQueryParameter("pk") ?: ""
                val name = uri.getQueryParameter("name") ?: ""
                val os = uri.getQueryParameter("os") ?: ""
                val nonce = uri.getQueryParameter("nonce") ?: ""
                val sig = uri.getQueryParameter("sig") ?: ""

                if (hosts.isEmpty() || port !in 1..65535 || pk.isBlank() || nonce.isBlank() || sig.isBlank()) {
                    viewModel.showError("Invalid pairing QR. Regenerate the QR code on your PC and scan again.")
                } else {
                    viewModel.startPairing(hosts, port, pk, name, os, nonce, sig)
                }
            }
        }
    }

    val latestScanCallback by rememberUpdatedState(scanCallback)

    DisposableEffect(cameraController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    cameraController.bindToLifecycle(lifecycleOwner)
                    if (latestUiState is PairingState.Scanning && !latestIsScanned) {
                        cameraController.startQrScanning(latestScanCallback)
                    }
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    cameraController.stopQrScanning()
                    cameraController.unbind()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            cameraController.bindToLifecycle(lifecycleOwner)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraController.release()
        }
    }

    LaunchedEffect(uiState, isScanned, lifecycleOwner) {
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return@LaunchedEffect
        }

        if (uiState is PairingState.Scanning && !isScanned) {
            cameraController.startQrScanning(scanCallback)
        } else {
            cameraController.stopQrScanning()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101114))) {
        // Camera analysis scanner view finder
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController.cameraController
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // QR Scanner overlay guides
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
                .border(2.dp, Color(0xFFA4B4FF), RoundedCornerShape(16.dp))
                .background(Color(0x10FFFFFF))
        )

        Text(
            text = "Show the QR code on your PC screen within the frame",
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp, start = 32.dp, end = 32.dp)
                .background(Color(0x90101114), RoundedCornerShape(8.dp))
                .padding(12.dp)
        )

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x90101114))
            ) {
                Text("← Back", color = Color.White)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Pair Device",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 16.dp)
            )
        }

        // Overlay pairing state dialogs
        AnimatedVisibility(
            visible = uiState !is PairingState.Idle && uiState !is PairingState.Scanning,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCE101114))
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2128)),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth().wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (val state = uiState) {
                            PairingState.Connecting -> {
                                CircularProgressIndicator(color = Color(0xFFA4B4FF))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Connecting to PC...", color = Color.White, fontSize = 16.sp)
                            }
                            PairingState.Handshaking -> {
                                CircularProgressIndicator(color = Color(0xFFA4B4FF))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Authenticating handshake...", color = Color.White, fontSize = 16.sp)
                            }
                            is PairingState.FingerprintVerification -> {
                                Text(
                                    "Confirm Fingerprint",
                                    color = Color(0xFFA4B4FF),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Verify that this fingerprint matches the fingerprint displayed on your PC screen:",
                                    color = Color(0xFF8C8E96),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF101114), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0xFF44464F), RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = state.fingerprint,
                                        color = Color(0xFFDEE0FF),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = state.onConfirm,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B5BFF)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("Confirm & Connect", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            is PairingState.Error -> {
                                Text(
                                    "Pairing Failed",
                                    color = Color(0xFFFFB4AB),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    state.message,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = {
                                        isScanned = false
                                        viewModel.resetScanning()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF44464F)),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) {
                                    Text("Retry Scanning", color = Color.White)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}
