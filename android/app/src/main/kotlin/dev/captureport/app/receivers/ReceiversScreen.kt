package dev.captureport.app.receivers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.captureport.app.CameraCapturePolicy
import dev.captureport.app.camera.CameraController
import dev.captureport.app.data.PairedDevice
import dev.captureport.app.transfer.TransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
    val app = CapturePortApp.instance

    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val connectionState by (app.wsClient?.connectionState?.collectAsState() ?: remember { mutableStateOf("Disconnected") })

    val cameraController = remember { CameraController(context) }
    
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by rememberSaveable { mutableStateOf(0) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var currentPolicy by remember { mutableStateOf(app.cameraCapturePolicy) }
    
    var deviceToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deviceToDelete = pairedDevices.find { it.id == deviceToDeleteId }

    val collapsedDevices = rememberSaveable(
        saver = Saver(
            save = { map -> map.toMap() },
            restore = { map -> mutableStateMapOf<String, Boolean>().apply { putAll(map as Map<String, Boolean>) } }
        )
    ) { mutableStateMapOf<String, Boolean>() }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val file = copyUriToCache(context, uri)
                if (file != null && file.exists()) {
                    withContext(Dispatchers.Main) {
                        val ws = app.wsClient
                        if (ws != null && connectionState == "Connected") {
                            ws.pushPhoto(file)
                            Toast.makeText(context, "Gallery photo pushed to PC!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Saved locally. Receiver not connected.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(cameraController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    app.activeCameraController = cameraController
                    app.isCameraScreenVisible = true
                    cameraController.bindToLifecycle(lifecycleOwner)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.isCameraScreenVisible = false
            if (app.activeCameraController === cameraController) {
                app.activeCameraController = null
            }
            cameraController.release()
        }
    }

    LaunchedEffect(selectedDevice) {
        val dev = selectedDevice
        if (dev != null) {
            app.wsClient?.connect(dev)
        } else {
            app.wsClient?.disconnect()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            while (isActive) {
                delay(1000)
                recordingDuration++
            }
        }
    }

    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101114))) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController.cameraController
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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

        // Top HUD Area: Status Text & Dot + Dropdown Menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when {
                    connectionState == "Connected" -> Color(0xFF4CAF50)
                    connectionState.startsWith("Connecting") || connectionState.startsWith("Reconnecting") -> Color(0xFFFFC107)
                    else -> Color(0xFFF44336)
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = connectionState,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box {
                TextButton(
                    onClick = { showMenu = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Text(
                        text = currentPolicy.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Color(0xFF1F2128))
                ) {
                    CameraCapturePolicy.values().forEach { policy ->
                        DropdownMenuItem(
                            text = { Text(policy.label, color = Color.White) },
                            onClick = {
                                app.cameraCapturePolicy = policy
                                currentPolicy = policy
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Recording timer
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
                    initialValue = 1f, targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse)
                )
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = alpha)))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "REC ${formatDuration(recordingDuration)}",
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            }
        }

        // Dashboard overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Paired PC Receivers",
                    color = Color(0xFFDEE0FF), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                if (pairedDevices.isNotEmpty()) {
                    IconButton(
                        onClick = onNavigateToPairing,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xD91F2128)),
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, Color(0xFF2C2E35), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Pair Receiver",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth().height(68.dp)
                        .background(Color(0x991F2128), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .clickable { onNavigateToPairing() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ Add and Pair a New PC", color = Color(0xFFA4B4FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pairedDevices, key = { it.id }) { device ->
                        val isSelected = selectedDevice?.id == device.id
                        val borderCol = if (isSelected) Color(0xFF3B5BFF) else Color(0xFF2C2E35)
                        val bgCol = if (isSelected) Color(0xE61F2128) else Color(0x991F2128)
                        val isCollapsed = collapsedDevices[device.id] ?: false

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(bgCol)
                                .border(1.dp, borderCol, RoundedCornerShape(16.dp))
                                .clickable { viewModel.selectDevice(device) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = device.name,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { collapsedDevices[device.id] = !isCollapsed },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                                contentDescription = if (isCollapsed) "Expand card" else "Collapse card",
                                                tint = Color(0xFF8C8E96),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (!isCollapsed) {
                                        Text(
                                            text = "${device.os.uppercase()} · ${device.host}",
                                            color = Color(0xFF8C8E96),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                if (!isCollapsed) {
                                    Spacer(modifier = Modifier.width(16.dp))
                                    IconButton(
                                        onClick = { deviceToDeleteId = device.id },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete device",
                                            tint = Color(0xFFFFB4AB),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Record Video
                Button(
                    onClick = {
                        if (isRecording) {
                            cameraController.stopVideoRecording()
                        } else {
                            val hasAudioPermission = ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.RECORD_AUDIO
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            
                            if (!hasAudioPermission) {
                                Toast.makeText(context, "Microphone permission is required for video recording", Toast.LENGTH_LONG).show()
                            } else {
                                try {
                                    cameraController.startVideoRecording { event ->
                                        if (event is VideoRecordEvent.Finalize) {
                                            isRecording = false
                                            val outputUri = event.outputResults.outputUri
                                            val filePath = outputUri.path
                                            if (!event.hasError() && filePath != null) {
                                                val intent = Intent(context, TransferService::class.java).apply {
                                                    putExtra("file_path", filePath)
                                                    putExtra("request_id", "user_push_video")
                                                }
                                                ContextCompat.startForegroundService(context, intent)
                                                Toast.makeText(context, "Streaming video to PC...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                if (event.hasError()) {
                                                    Toast.makeText(context, "Recording error: ${event.error}", Toast.LENGTH_SHORT).show()
                                                }
                                                filePath?.let { java.io.File(it).delete() }
                                            }
                                        }
                                    }
                                    isRecording = true
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) Color(0xFFFF3B30) else Color(0xFF1F2128)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1.0f).height(56.dp).border(1.dp, if (isRecording) Color.Red else Color(0xFF44464F), RoundedCornerShape(16.dp))
                ) {
                    Text(if (isRecording) "Stop Video" else "Record Video", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
                }

                // Gallery Upload
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2128)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1.0f).height(56.dp).border(1.dp, Color(0xFF44464F), RoundedCornerShape(16.dp))
                ) {
                    Text("Gallery", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Snap Photo
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
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1.0f).height(56.dp)
                ) {
                    Text("Snap Photo", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (deviceToDelete != null) {
        AlertDialog(
            onDismissRequest = { deviceToDeleteId = null },
            title = { Text("Remove PC Receiver", color = Color.White) },
            text = { Text("Are you sure you want to unpair and remove ${deviceToDelete.name}?", color = Color(0xFF8C8E96)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDevice(deviceToDelete)
                    deviceToDeleteId = null
                }) {
                    Text("Remove", color = Color(0xFFFFB4AB))
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToDeleteId = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1F2128)
        )
    }
}

private fun copyUriToCache(context: Context, uri: Uri): File? {
    return try {
        val resolver = context.contentResolver
        val tempFile = File(context.cacheDir, "CP_gallery_${System.currentTimeMillis()}.jpg")
        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        null
    }
}
