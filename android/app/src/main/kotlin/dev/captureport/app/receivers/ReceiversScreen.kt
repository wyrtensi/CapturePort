package dev.captureport.app.receivers

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.core.content.ContextCompat
import androidx.camera.video.VideoRecordEvent
import dev.captureport.app.CapturePortApp
import dev.captureport.app.camera.CameraController
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.transfer.TransferService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ReceiversScreen(
    viewModel: ReceiversViewModel,
    onNavigateToPairing: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = CapturePortApp.instance

    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val connectionState by (app.wsClient?.connectionState?.collectAsState() ?: remember { mutableStateOf("Disconnected") })

    // Camera controller for preview, photo snap, and video record
    val cameraController = remember { CameraController(context) }
    
    var isRecording by remember { mutableStateOf(false) }
    var videoFile by remember { mutableStateOf<File?>(null) }
    var recordingDuration by remember { mutableStateOf(0) }

    // Register active camera controller for MCP triggers
    DisposableEffect(cameraController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    app.activeCameraController = cameraController
                    app.isCameraScreenVisible = true
                    cameraController.bindToLifecycle(lifecycleOwner)
                }

                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    if (app.activeCameraController === cameraController) {
                        app.activeCameraController = null
                    }
                    app.isCameraScreenVisible = false
                    cameraController.unbind()
                }

                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            app.activeCameraController = cameraController
            app.isCameraScreenVisible = true
            cameraController.bindToLifecycle(lifecycleOwner)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.isCameraScreenVisible = false
            if (app.activeCameraController === cameraController) {
                app.activeCameraController = null
            }
            cameraController.release()
        }
    }

    // Connect automatically when selected device changes
    LaunchedEffect(selectedDevice) {
        val dev = selectedDevice
        if (dev != null) {
            app.wsClient?.connect(dev)
        } else {
            app.wsClient?.disconnect()
        }
    }

    // Video Recording Duration counter
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            while (isActive) {
                delay(1000)
                recordingDuration++
            }
        }
    }

    // Formatting utility for duration
    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101114))) {
        
        // Background Viewfinder Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController.cameraController
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay for visual clarity
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color(0x99101114),
                            Color.Transparent,
                            Color(0xB3101114)
                        )
                    )
                )
        )

        // Top HUD Area: Connection state and Add Pairing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sleek pill for active receiver and connection status
            val statusColor = when (connectionState) {
                "Connected" -> Color(0xFF4CAF50)
                "Connecting..." -> Color(0xFFFFC107)
                else -> Color(0xFFF44336)
            }

            Row(
                modifier = Modifier
                    .background(Color(0xD91F2128), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = selectedDevice?.name ?: "No Receiver Selected",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = connectionState,
                        color = Color(0xFF8C8E96),
                        fontSize = 11.sp
                    )
                    Text(
                        text = app.cameraCapturePolicy.label,
                        color = Color(0xFF8C8E96),
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (pairedDevices.isNotEmpty()) {
                IconButton(
                    onClick = onNavigateToPairing,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xD91F2128)),
                    modifier = Modifier
                        .size(44.dp)
                        .border(1.dp, Color(0xFF2C2E35), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Pair Receiver",
                        tint = Color.White
                    )
                }
            }
        }

        // Live recording visual indicators
        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 24.dp)
                    .background(Color(0xCCFF3B30), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "REC ${formatDuration(recordingDuration)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Bottom Dashboard
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            
            // Paired receivers listing row
            Text(
                text = "Paired PC Receivers",
                color = Color(0xFFDEE0FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .background(Color(0x991F2128), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { onNavigateToPairing() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+ Add and Pair a New PC",
                        color = Color(0xFFA4B4FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pairedDevices) { device ->
                        val isSelected = selectedDevice?.id == device.id
                        val borderCol = if (isSelected) Color(0xFF3B5BFF) else Color(0xFF2C2E35)
                        val bgCol = if (isSelected) Color(0xE61F2128) else Color(0x991F2128)

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(bgCol)
                                .border(1.dp, borderCol, RoundedCornerShape(16.dp))
                                .clickable { viewModel.selectDevice(device) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = device.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${device.os.uppercase()} · ${device.host}",
                                    color = Color(0xFF8C8E96),
                                    fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete device",
                                tint = Color(0xFFFFB4AB),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { viewModel.removeDevice(device) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons (Capture Photo / Video)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Video Recorder Trigger
                Button(
                    onClick = {
                        if (isRecording) {
                            cameraController.stopVideoRecording()
                        } else {
                            videoFile = cameraController.startVideoRecording { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    isRecording = false
                                    if (!event.hasError()) {
                                        val intent = Intent(context, TransferService::class.java).apply {
                                            putExtra("file_path", videoFile?.absolutePath)
                                            putExtra("request_id", "user_push_video")
                                        }
                                        ContextCompat.startForegroundService(context, intent)
                                        Toast.makeText(context, "Streaming video to PC...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Recording error: ${event.error}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            isRecording = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFFF3B30) else Color(0xFF1F2128)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .border(1.dp, if (isRecording) Color.Red else Color(0xFF44464F), RoundedCornerShape(20.dp))
                ) {
                    Text(
                        text = if (isRecording) "Stop Video" else "Record Video",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Photo Capture Trigger
                Button(
                    onClick = {
                        cameraController.takePhoto(
                            onSuccess = { file ->
                                val ws = app.wsClient
                                if (ws != null && connectionState == "Connected") {
                                    ws.pushPhoto(file)
                                    Toast.makeText(context, "Photo copied to PC clipboard!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Saved locally. Receiver not connected.", Toast.LENGTH_LONG).show()
                                }
                            },
                            onError = { err ->
                                Toast.makeText(context, "Photo capture failed: ${err.message}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B5BFF)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Text(
                        text = "Snap Photo",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
