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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import dev.captureport.app.ReceiverConnectionMode
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
    var showSettingsMenu by rememberSaveable { mutableStateOf(false) }
    var currentPolicy by remember { mutableStateOf(app.cameraCapturePolicy) }
    var receiverConnectionMode by remember { mutableStateOf(app.receiverConnectionMode) }
    
    var deviceToDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deviceToDelete = pairedDevices.find { it.id == deviceToDeleteId }

    var deviceToEditId by rememberSaveable { mutableStateOf<String?>(null) }
    val deviceToEdit = pairedDevices.find { it.id == deviceToEditId }
    var editAliasText by remember { mutableStateOf("") }
    var editHostText by remember { mutableStateOf("") }
    var editPortText by remember { mutableStateOf("") }
    var editInternetHostText by remember { mutableStateOf("") }
    var editInternetPortText by remember { mutableStateOf("") }

    LaunchedEffect(deviceToEdit) {
        if (deviceToEdit != null) {
            editAliasText = deviceToEdit.alias.ifBlank { deviceToEdit.name }
            editHostText = deviceToEdit.localHosts.ifBlank { deviceToEdit.host }
            editPortText = (deviceToEdit.localPort.takeIf { it > 0 } ?: deviceToEdit.port).toString()
            editInternetHostText = deviceToEdit.internetHost
            editInternetPortText = deviceToEdit.internetPort.takeIf { it > 0 }?.toString().orEmpty()
        }
    }

    val collapsedDevices = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { list -> mutableStateMapOf<String, Boolean>().apply { list.forEach { put(it.first, it.second) } } }
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

        // ── Settings dropdown overlay ──
        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettingsMenu = false }
            )
        }

        AnimatedVisibility(
            visible = showSettingsMenu,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(Color(0xF2101114))
                    .border(
                        BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(top = 64.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // Camera mode section
                Text(
                    text = "Camera Mode",
                    color = Color(0xFF8C8E96),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                )
                CameraCapturePolicy.values().forEach { policy ->
                    val isActive = currentPolicy == policy
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) Color(0x263B5BFF) else Color.Transparent)
                            .clickable {
                                app.applyCameraCapturePolicy(policy)
                                currentPolicy = policy
                                showSettingsMenu = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = policy.label,
                            color = if (isActive) Color(0xFFA4B4FF) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Divider
                HorizontalDivider(
                    color = Color(0x1AFFFFFF),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                // Connection mode section
                Text(
                    text = "Connection Mode",
                    color = Color(0xFF8C8E96),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                )
                ReceiverConnectionMode.values().forEach { mode ->
                    val isActive = receiverConnectionMode == mode
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) Color(0x263B5BFF) else Color.Transparent)
                            .clickable {
                                app.applyReceiverConnectionMode(mode)
                                receiverConnectionMode = mode
                                showSettingsMenu = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = mode.label,
                            color = if (isActive) Color(0xFFA4B4FF) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // Divider
                HorizontalDivider(
                    color = Color(0x1AFFFFFF),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )

                // Add & Pair button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF3B5BFF), Color(0xFF6B82FF))
                            )
                        )
                        .clickable {
                            showSettingsMenu = false
                            onNavigateToPairing()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add & Pair a New PC",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Drag handle at bottom
                Box(
                    modifier = Modifier
                        .padding(top = 16.dp, bottom = 10.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x4DFFFFFF))
                )
            }
        }

        // Top HUD Area (Rendered last to sit on top of the dropdown menu)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Left side — REC timer
            if (isRecording) {
                val infiniteTransition = rememberInfiniteTransition()
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f, targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .background(Color(0x99FF3B30), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = dotAlpha)))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "REC ${formatDuration(recordingDuration)}",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Center — inconspicuous arrow V button
            IconButton(
                onClick = { showSettingsMenu = !showSettingsMenu },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = if (showSettingsMenu) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Receiver settings",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Paired PC Receivers",
                    color = Color(0xFFDEE0FF), fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            if (pairedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0x1E1F2128), Color(0x3E1F2128))
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0x20FFFFFF), Color(0x08FFFFFF))
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onNavigateToPairing() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color(0xFFA4B4FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add & Pair a New PC",
                            color = Color(0xFFDEE0FF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                val receiverRowState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxWidth()) {
                    LazyRow(
                        state = receiverRowState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                    items(pairedDevices, key = { it.id }) { device ->
                        val isSelected = selectedDevice?.id == device.id
                        val borderCol = if (isSelected) Color(0xFF3B5BFF) else Color(0xFF2C2E35)
                        val bgCol = if (isSelected) Color(0xE61F2128) else Color(0x991F2128)
                        val isCollapsed = collapsedDevices[device.id] ?: true
                        val displayName = device.alias.ifBlank { device.name }

                        Row(
                            modifier = Modifier
                                .width(if (isCollapsed) 188.dp else 252.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(bgCol)
                                .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(16.dp))
                                .clickable { viewModel.selectDevice(device) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelected) {
                                        val activeStatusColor = if (connectionState == "Connected") Color(0xFF4CAF50) else Color(0xFFFFC107)
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(activeStatusColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = displayName,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { collapsedDevices[device.id] = !isCollapsed },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            contentDescription = if (isCollapsed) "Expand card" else "Collapse card",
                                            tint = Color(0xFF8C8E96),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (!isCollapsed) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val hosts = device.localHosts.ifBlank { device.host }.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    val firstHost = hosts.firstOrNull() ?: device.host
                                    val moreCount = (hosts.size - 1).coerceAtLeast(0)
                                    val hostText = if (moreCount > 0) "$firstHost +$moreCount" else firstHost
                                    Text(
                                        text = "${device.os.uppercase()} · $hostText",
                                        color = Color(0xFF8C8E96),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        IconButton(
                                            onClick = { deviceToEditId = device.id },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x263B5BFF), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Edit device IP",
                                                tint = Color(0xFFA4B4FF),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        IconButton(
                                            onClick = { deviceToDeleteId = device.id },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0x26FF3B30), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete device",
                                                tint = Color(0xFFFF8A80),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                    // Right-edge "pocket" fade — suggests the row continues off-screen
                    if (receiverRowState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(2.dp)
                                .height(64.dp)
                                .background(Color(0x663B5BFF), RoundedCornerShape(2.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFF2D1A1A) else Color(0x991F2128)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isRecording) Color(0xFFFF8A80) else Color(0xFF2C2E35)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1.0f)
                        .height(58.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = VideocamIcon,
                            contentDescription = "Video",
                            tint = if (isRecording) Color(0xFFFF8A80) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isRecording) "Stop Rec" else "Record",
                            color = if (isRecording) Color(0xFFFF8A80) else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                // Snap Photo (Center, main action, larger)
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .height(64.dp)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF3B5BFF), Color(0xFF6B82FF))
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = CameraIcon,
                            contentDescription = "Camera",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Snap Photo",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                // Gallery Upload (Right)
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x991F2128)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF2C2E35)),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1.0f)
                        .height(58.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = ImageIcon,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Gallery",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (deviceToDelete != null) {
        AlertDialog(
            onDismissRequest = { deviceToDeleteId = null },
            title = {
                Text(
                    text = "Remove PC Receiver",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to unpair and remove ${deviceToDelete.name}?",
                    color = Color(0xFF8E9099),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeDevice(deviceToDelete)
                        deviceToDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D1A1A)),
                    border = BorderStroke(1.dp, Color(0x33FF5C5C)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Remove", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deviceToDeleteId = null }
                ) {
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.Medium)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF1B1C20)
        )
    }

    // Edit receiver dialog
    if (deviceToEdit != null) {
        val isHostValid = editHostText.isNotBlank()
        val portInt = editPortText.toIntOrNull()
        val isPortValid = portInt != null && portInt in 1..65535
        val internetPortInt = editInternetPortText.toIntOrNull()
        val isInternetPortValid = editInternetHostText.isBlank() ||
            (internetPortInt != null && internetPortInt in 1..65535)

        AlertDialog(
            onDismissRequest = { deviceToEditId = null },
            title = {
                Text(
                    text = "PC Receiver",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Name this PC and adjust its local or internet endpoints.",
                        color = Color(0xFF8E9099),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    OutlinedTextField(
                        value = editAliasText,
                        onValueChange = { editAliasText = it },
                        label = { Text("Computer name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B5BFF),
                            unfocusedBorderColor = Color(0xFF2C2E35),
                            focusedLabelColor = Color(0xFF3B5BFF),
                            unfocusedLabelColor = Color(0xFF8E9099)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editHostText,
                        onValueChange = { editHostText = it },
                        label = { Text("Local address(es)") },
                        singleLine = true,
                        isError = !isHostValid,
                        shape = RoundedCornerShape(12.dp),
                        supportingText = {
                            if (!isHostValid) {
                                Text("IP Address cannot be blank", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B5BFF),
                            unfocusedBorderColor = Color(0xFF2C2E35),
                            focusedLabelColor = Color(0xFF3B5BFF),
                            unfocusedLabelColor = Color(0xFF8E9099)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPortText,
                        onValueChange = { editPortText = it },
                        label = { Text("Local port") },
                        singleLine = true,
                        isError = !isPortValid,
                        shape = RoundedCornerShape(12.dp),
                        supportingText = {
                            if (!isPortValid) {
                                Text("Port must be a number between 1 and 65535", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B5BFF),
                            unfocusedBorderColor = Color(0xFF2C2E35),
                            focusedLabelColor = Color(0xFF3B5BFF),
                            unfocusedLabelColor = Color(0xFF8E9099)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editInternetHostText,
                        onValueChange = { editInternetHostText = it },
                        label = { Text("Internet host or DDNS") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B5BFF),
                            unfocusedBorderColor = Color(0xFF2C2E35),
                            focusedLabelColor = Color(0xFF3B5BFF),
                            unfocusedLabelColor = Color(0xFF8E9099)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editInternetPortText,
                        onValueChange = { editInternetPortText = it },
                        label = { Text("Internet port") },
                        singleLine = true,
                        isError = !isInternetPortValid,
                        shape = RoundedCornerShape(12.dp),
                        supportingText = {
                            if (!isInternetPortValid) {
                                Text("Internet port must be a number between 1 and 65535", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF3B5BFF),
                            unfocusedBorderColor = Color(0xFF2C2E35),
                            focusedLabelColor = Color(0xFF3B5BFF),
                            unfocusedLabelColor = Color(0xFF8E9099)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isHostValid && isPortValid && isInternetPortValid) {
                            val portVal = portInt!!
                            scope.launch(Dispatchers.IO) {
                                app.pairedDevicesRepository.renameDeviceAlias(deviceToEdit.id, editAliasText)
                                app.pairedDevicesRepository.updateLocalEndpoint(deviceToEdit.id, editHostText, portVal)
                                app.pairedDevicesRepository.updateInternetEndpoint(
                                    deviceToEdit.id,
                                    editInternetHostText,
                                    internetPortInt ?: 0,
                                )
                            }
                            deviceToEditId = null
                        }
                    },
                    enabled = isHostValid && isPortValid && isInternetPortValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3B5BFF),
                        disabledContainerColor = Color(0x303B5BFF)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Save",
                        color = if (isHostValid && isPortValid && isInternetPortValid) Color.White else Color(0x80FFFFFF),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToEditId = null }) {
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.Medium)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF1B1C20)
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

val CameraIcon: ImageVector
    get() = ImageVector.Builder(
        name = "CameraIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 8f)
            curveTo(9.79f, 8f, 8f, 9.79f, 8f, 12f)
            reflectiveCurveTo(9.79f, 16f, 12f, 16f)
            reflectiveCurveTo(16f, 14.21f, 16f, 12f)
            reflectiveCurveTo(14.21f, 8f, 12f, 8f)
            close()
            moveTo(9f, 2f)
            lineTo(7.17f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            horizontalLineTo(16.83f)
            lineTo(15f, 2f)
            horizontalLineTo(9f)
            close()
            moveTo(12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            reflectiveCurveTo(8.69f, 6f, 12f, 6f)
            reflectiveCurveTo(18f, 8.69f, 18f, 12f)
            reflectiveCurveTo(15.31f, 18f, 12f, 18f)
            close()
        }
    }.build()

val VideocamIcon: ImageVector
    get() = ImageVector.Builder(
        name = "VideocamIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(17f, 10.5f)
            verticalLineTo(7f)
            curveTo(17f, 6.45f, 16.55f, 6f, 16f, 6f)
            horizontalLineTo(4f)
            curveTo(3.45f, 6f, 3f, 6.45f, 3f, 7f)
            verticalLineTo(17f)
            curveTo(3f, 17.55f, 3.45f, 18f, 4f, 18f)
            horizontalLineTo(16f)
            curveTo(16.55f, 18f, 17f, 17.55f, 17f, 17f)
            verticalLineTo(13.5f)
            lineTo(21f, 17.5f)
            verticalLineTo(6.5f)
            lineTo(17f, 10.5f)
            close()
        }
    }.build()

val ImageIcon: ImageVector
    get() = ImageVector.Builder(
        name = "ImageIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(21.02f, 5f)
            horizontalLineTo(3f)
            curveTo(1.9f, 5f, 1f, 5.9f, 1f, 7f)
            verticalLineTo(17f)
            curveTo(1f, 18.1f, 1.9f, 19f, 3f, 19f)
            horizontalLineTo(21f)
            curveTo(22.1f, 19f, 23f, 18.1f, 23f, 17f)
            verticalLineTo(7f)
            curveTo(23f, 5.9f, 22.12f, 5f, 21.02f, 5f)
            close()
            moveTo(19f, 17f)
            horizontalLineTo(5f)
            curveTo(4.45f, 17f, 4f, 16.55f, 4f, 16f)
            verticalLineTo(8f)
            curveTo(4f, 7.45f, 4.45f, 7f, 5f, 7f)
            horizontalLineTo(19f)
            curveTo(19.55f, 7f, 20f, 7.45f, 20f, 8f)
            verticalLineTo(16f)
            curveTo(20f, 16.55f, 19.55f, 17f, 19f, 17f)
            close()
            moveTo(8.5f, 11f)
            curveTo(9.33f, 11f, 10f, 10.33f, 10f, 9.5f)
            reflectiveCurveTo(9.33f, 8f, 8.5f, 8f)
            reflectiveCurveTo(7f, 8.67f, 7f, 9.5f)
            reflectiveCurveTo(7.67f, 11f, 8.5f, 11f)
            close()
            moveTo(6f, 15f)
            horizontalLineTo(18f)
            lineTo(14.25f, 10f)
            lineTo(11.25f, 14f)
            lineTo(9.5f, 11.67f)
            lineTo(6f, 15f)
            close()
        }
    }.build()
