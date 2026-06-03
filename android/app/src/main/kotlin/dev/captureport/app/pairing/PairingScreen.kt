package dev.captureport.app.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    var isVpnActive by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            while (isActive) {
                val activeNetwork = cm.activeNetwork
                val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                isVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                delay(2000)
            }
        }
    }
    
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
    var manualIp by remember { mutableStateOf("") }
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

    // Laser scan animation
    val infiniteTransition = rememberInfiniteTransition(label = "laser")
    val scanPercent by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserPosition"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0D))
    ) {
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

        // Custom premium dim overlay with cutout
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = 0.99f)
                .drawWithContent {
                    drawContent() // Draws AndroidView first
                    
                    // Draw dim layer
                    drawRect(color = Color(0xB3000000))
                    
                    // Viewfinder cutout (260.dp)
                    val cutoutSizePx = 260.dp.toPx()
                    val left = (this.size.width - cutoutSizePx) / 2
                    val top = (this.size.height - cutoutSizePx) / 2
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(cutoutSizePx, cutoutSizePx),
                        cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx()),
                        blendMode = BlendMode.Clear
                    )
                }
        )

        // QR Scanner overlay guides with gradient borders and laser
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.Center)
        ) {
            // Viewfinder border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(Color(0xFF3B5BFF), Color(0xFFA4B4FF))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
            )

            // Scanning laser line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = 260.dp * scanPercent)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFA4B4FF),
                                Color(0xFF3B5BFF),
                                Color(0xFFA4B4FF),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Sleek Instruction text capsule
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
                .background(Color(0xD9101114), RoundedCornerShape(20.dp))
                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Align the PC pairing QR code inside the frame",
                color = Color(0xFFE2E2E6),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        // Top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .background(Color(0x80101114), CircleShape)
                    .border(1.dp, Color(0x1AFFFFFF), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "Pair Device",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Animated VPN Warning Alert (Sliding below the Top Bar)
        AnimatedVisibility(
            visible = isVpnActive,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp)
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xD92C1D1D), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x40FF8A80), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = Color(0xFFFF8A80),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Active VPN detected. Direct pairing might fail unless VPN bypass is active.",
                    color = Color(0xFFFDE8E8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Overlay pairing state dialogs
        AnimatedVisibility(
            visible = uiState !is PairingState.Idle && uiState !is PairingState.Scanning,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD90A0B0D))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1C20)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0x1AFFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (val state = uiState) {
                            PairingState.Connecting -> {
                                CircularProgressIndicator(
                                    color = Color(0xFF3B5BFF),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Connecting to PC...",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            PairingState.Handshaking -> {
                                CircularProgressIndicator(
                                    color = Color(0xFF3B5BFF),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Authenticating handshake...",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            is PairingState.FingerprintVerification -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Security",
                                        tint = Color(0xFF3B5BFF),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Confirm Fingerprint",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Verify that this fingerprint matches the fingerprint displayed on your PC screen:",
                                    color = Color(0xFF8E9099),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF111216), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(12.dp))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = state.fingerprint,
                                        color = Color(0xFF5B7BFF),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = state.onConfirm,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B5BFF)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                ) {
                                    Text("Confirm & Connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                            is PairingState.Error -> {
                                Text(
                                    text = "Pairing Failed",
                                    color = Color(0xFFFF8A80),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.message,
                                    color = Color(0xFFE2E2E6),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                
                                if (state.canRetryManual) {
                                    OutlinedTextField(
                                        value = manualIp,
                                        onValueChange = { manualIp = it },
                                        label = { Text("Manual IP Override (e.g. 192.168.1.5)") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF3B5BFF),
                                            unfocusedBorderColor = Color(0xFF2C2E35),
                                            focusedLabelColor = Color(0xFF3B5BFF),
                                            unfocusedLabelColor = Color(0xFF8E9099)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { viewModel.pairWithManualIp(manualIp) },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B5BFF)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                        ) {
                                            Text("Connect IP", fontWeight = FontWeight.Bold)
                                        }
                                        Button(
                                            onClick = {
                                                isScanned = false
                                                viewModel.resetScanning()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2E35)),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(46.dp)
                                        ) {
                                            Text("Retry Scan", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            isScanned = false
                                            viewModel.resetScanning()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B5BFF)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                    ) {
                                        Text("Retry Scan", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
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
