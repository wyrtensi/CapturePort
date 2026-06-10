package dev.captureport.app.receivers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import android.view.OrientationEventListener
import android.view.Surface
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
import androidx.compose.ui.text.style.TextOverflow
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
import java.net.HttpURLConnection
import java.net.URL
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject

enum class RotationLockMode {
    AUTO, PORTRAIT, LANDSCAPE
}

private const val RECEIVER_PREFS = "receiver_preferences"
private const val KEY_HIDE_PAIRED_PCS = "hide_paired_pcs"
private const val LATEST_RELEASE_URL = "https://github.com/wyrtensi/CapturePort/releases/latest"

private fun appVersionName(context: Context): String {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            ).versionName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }
    }.getOrNull().orEmpty()
}

private fun loadHidePairedPcs(context: Context): Boolean {
    return context.getSharedPreferences(RECEIVER_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_HIDE_PAIRED_PCS, false)
}

private fun saveHidePairedPcs(context: Context, hidden: Boolean) {
    context.getSharedPreferences(RECEIVER_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_HIDE_PAIRED_PCS, hidden)
        .apply()
}

private fun normalizeVersionTag(version: String): String {
    return version.trim().removePrefix("v").removePrefix("V")
}

private fun compareVersionTags(left: String, right: String): Int {
    val leftParts = normalizeVersionTag(left).split(".").map { it.toIntOrNull() ?: 0 }
    val rightParts = normalizeVersionTag(right).split(".").map { it.toIntOrNull() ?: 0 }
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val diff = (leftParts.getOrNull(index) ?: 0) - (rightParts.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

private fun versionStatusText(current: String, latest: String?): String {
    val latestVersion = latest?.takeIf { it.isNotBlank() } ?: return "v$current"
    return if (compareVersionTags(current, latestVersion) < 0) {
        "v$current · update v$latestVersion"
    } else {
        "v$current · current"
    }
}

private suspend fun fetchLatestReleaseVersion(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL("https://api.github.com/repos/wyrtensi/CapturePort/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CapturePort-Android")
        }
        connection.inputStream.bufferedReader().use { reader ->
            normalizeVersionTag(JSONObject(reader.readText()).optString("tag_name"))
        }.also {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun openLatestRelease(context: Context) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(LATEST_RELEASE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun shouldShowReceiverXiaomiAutostartHint(
    manufacturer: String,
    display: String
): Boolean {
    val manufacturerText = manufacturer.lowercase()
    val displayText = display.lowercase()
    return manufacturerText.contains("xiaomi") ||
        manufacturerText.contains("poco") ||
        displayText.contains("hyperos") ||
        displayText.contains("miui")
}

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
    val appVersion = remember(context) { appVersionName(context) }

    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    val connectionState by (app.wsClient?.connectionState?.collectAsState() ?: remember { mutableStateOf("Disconnected") })

    val cameraController = remember { app.cameraController }
    val torchEnabled by cameraController.torchEnabledFlow.collectAsState()
    
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by rememberSaveable { mutableStateOf(0) }
    var showSettingsMenu by rememberSaveable { mutableStateOf(false) }
    var currentPolicy by remember { mutableStateOf(app.cameraCapturePolicy) }
    var receiverConnectionMode by remember { mutableStateOf(app.receiverConnectionMode) }
    val isBackgroundCameraArmed by app.isBackgroundCameraArmedFlow.collectAsState()
    val isBackgroundMicrophoneArmed by app.isBackgroundMicrophoneArmedFlow.collectAsState()
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    val isXiaomiDevice = remember {
        shouldShowReceiverXiaomiAutostartHint(Build.MANUFACTURER, Build.DISPLAY)
    }
    var hidePairedPcs by rememberSaveable { mutableStateOf(loadHidePairedPcs(context)) }
    var latestAppVersion by rememberSaveable { mutableStateOf<String?>(null) }

    var rotationLockMode by rememberSaveable { mutableStateOf(RotationLockMode.AUTO) }
    var physicalRotation by remember { mutableStateOf(0) }
    val currentRotation = when (rotationLockMode) {
        RotationLockMode.AUTO -> physicalRotation
        RotationLockMode.PORTRAIT -> 0
        RotationLockMode.LANDSCAPE -> if (physicalRotation == 270) 270 else 90
    }

    val iconRotation by animateFloatAsState(
        targetValue = currentRotation.toFloat(),
        animationSpec = tween(durationMillis = 300)
    )

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val newRotation = when (orientation) {
                    in 45 until 135 -> 270
                    in 135 until 225 -> 180
                    in 225 until 315 -> 90
                    else -> 0
                }
                if (physicalRotation != newRotation) {
                    physicalRotation = newRotation
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        }
        onDispose {
            listener.disable()
        }
    }

    LaunchedEffect(currentRotation) {
        val targetRotationValue = when (currentRotation) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        try {
            val superclass = cameraController.cameraController::class.java.superclass
            
            // 1. mImageCapture
            try {
                val field = superclass.getDeclaredField("mImageCapture")
                field.isAccessible = true
                val imageCapture = field.get(cameraController.cameraController)
                if (imageCapture != null) {
                    var clazz: Class<*>? = imageCapture.javaClass
                    var methodFound = false
                    while (clazz != null && !methodFound) {
                        try {
                            val method = clazz.getDeclaredMethod("setTargetRotation", java.lang.Integer.TYPE)
                            method.isAccessible = true
                            method.invoke(imageCapture, targetRotationValue)
                            methodFound = true
                        } catch (ex: Exception) {
                            clazz = clazz.superclass
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // 2. mImageAnalysis
            try {
                val field = superclass.getDeclaredField("mImageAnalysis")
                field.isAccessible = true
                val imageAnalysis = field.get(cameraController.cameraController)
                if (imageAnalysis != null) {
                    var clazz: Class<*>? = imageAnalysis.javaClass
                    var methodFound = false
                    while (clazz != null && !methodFound) {
                        try {
                            val method = clazz.getDeclaredMethod("setTargetRotation", java.lang.Integer.TYPE)
                            method.isAccessible = true
                            method.invoke(imageAnalysis, targetRotationValue)
                            methodFound = true
                        } catch (ex: Exception) {
                            clazz = clazz.superclass
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. mVideoCapture
            try {
                val field = superclass.getDeclaredField("mVideoCapture")
                field.isAccessible = true
                val videoCapture = field.get(cameraController.cameraController)
                if (videoCapture != null) {
                    var clazz: Class<*>? = videoCapture.javaClass
                    var methodFound = false
                    while (clazz != null && !methodFound) {
                        try {
                            val method = clazz.getDeclaredMethod("setTargetRotation", java.lang.Integer.TYPE)
                            method.isAccessible = true
                            method.invoke(videoCapture, targetRotationValue)
                            methodFound = true
                        } catch (ex: Exception) {
                            clazz = clazz.superclass
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
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

    LaunchedEffect(appVersion) {
        latestAppVersion = fetchLatestReleaseVersion()
    }

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

    fun backgroundCameraPermissions(): Array<String> {
        return buildList {
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun missingPermissions(permissions: Array<String>): Array<String> {
        return permissions.filter { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    var pendingCameraPolicy by remember { mutableStateOf<CameraCapturePolicy?>(null) }
    val backgroundCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val policy = pendingCameraPolicy
        pendingCameraPolicy = null
        val allGranted = backgroundCameraPermissions().all { permission ->
            grants[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (policy == CameraCapturePolicy.Background && allGranted) {
            app.applyCameraCapturePolicy(policy)
            currentPolicy = policy
            app.updateBackgroundService()
        } else if (policy == CameraCapturePolicy.Background) {
            Toast.makeText(
                context,
                "Camera, microphone, and notification permissions are required for background capture.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            app.updateBackgroundService()
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for video recording.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    DisposableEffect(cameraController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                    app.isCameraScreenVisible = true
                    cameraController.bindToLifecycle(lifecycleOwner)
                    app.updateBackgroundService()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    app.isCameraScreenVisible = false
                    cameraController.unbind()
                    app.updateBackgroundService()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            app.isCameraScreenVisible = false
            app.updateBackgroundService()
        }
    }

    LaunchedEffect(selectedDevice) {
        val dev = selectedDevice
        if (dev != null) {
            app.reconnectToDevice(dev)
        } else {
            app.disconnectFromReceiver()
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

    fun applyCameraMode(policy: CameraCapturePolicy) {
        if (currentPolicy == policy) return
        if (policy == CameraCapturePolicy.Background) {
            val missing = missingPermissions(backgroundCameraPermissions())
            if (missing.isNotEmpty()) {
                pendingCameraPolicy = policy
                backgroundCameraPermissionLauncher.launch(missing)
                return
            }
        }
        app.applyCameraCapturePolicy(policy)
        currentPolicy = policy
    }

    fun applyConnectionMode(mode: ReceiverConnectionMode) {
        if (receiverConnectionMode == mode) return
        app.applyReceiverConnectionMode(mode)
        receiverConnectionMode = mode
        selectedDevice?.let { app.reconnectToDevice(it) }
    }

    fun updateHidePairedPcs(hidden: Boolean) {
        hidePairedPcs = hidden
        saveHidePairedPcs(context, hidden)
    }

    fun requestBackgroundReadinessPermissions() {
        val missing = missingPermissions(backgroundCameraPermissions())
        if (missing.isNotEmpty()) {
            pendingCameraPolicy = CameraCapturePolicy.Background
            backgroundCameraPermissionLauncher.launch(missing)
        } else {
            app.applyCameraCapturePolicy(CameraCapturePolicy.Background)
            currentPolicy = CameraCapturePolicy.Background
            app.updateBackgroundService()
        }
    }

    fun openBatterySettings() {
        val intent = if (!isIgnoringBatteryOptimizations(context)) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun openXiaomiAutostartSettings() {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
        val intent = candidates.firstOrNull {
            it.resolveActivity(context.packageManager) != null
        } ?: candidates.last()
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    BackHandler(enabled = showSettingsMenu) {
        showSettingsMenu = false
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

        // Dashboard overlay
        AnimatedVisibility(
            visible = !showSettingsMenu,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                if (pairedDevices.isEmpty() || !hidePairedPcs) {
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
                } else if (!hidePairedPcs) {
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
                                val bgCol = if (isSelected) Color(0x661F2128) else Color(0x331F2128)
                                val isCollapsed = collapsedDevices[device.id] ?: true
                                val displayName = device.alias.ifBlank { device.name }

                                val cardWidthModifier = if (pairedDevices.size == 1) {
                                    Modifier.fillParentMaxWidth()
                                } else {
                                    Modifier.fillParentMaxWidth(0.8f).widthIn(max = 280.dp)
                                }

                                Row(
                                    modifier = Modifier
                                        .then(cardWidthModifier)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(bgCol)
                                        .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (isSelected) {
                                                app.reconnectToDevice(device)
                                                Toast.makeText(context, "Reconnecting to ${displayName}...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                viewModel.selectDevice(device)
                                            }
                                        }
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
                                                    imageVector = if (isCollapsed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                    contentDescription = if (isCollapsed) "Expand card" else "Collapse card",
                                                    tint = Color(0xFF8C8E96),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        if (!isCollapsed) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            val hosts = device.localHosts.ifBlank { device.host }
                                                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                            val firstHost = hosts.firstOrNull() ?: device.host
                                            val localPort = device.localPort.takeIf { it > 0 } ?: device.port
                                            val firstHostText = if (localPort > 0) "$firstHost:$localPort" else firstHost
                                            val moreCount = (hosts.size - 1).coerceAtLeast(0)

                                            // Local endpoint
                                            EndpointLine(
                                                label = device.os.uppercase(),
                                                value = if (moreCount > 0) "$firstHostText  +$moreCount" else firstHostText
                                            )
                                            // Internet endpoint, when configured
                                            if (device.internetHost.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(3.dp))
                                                val inetPort = device.internetPort.takeIf { it > 0 }
                                                EndpointLine(
                                                    label = "WAN",
                                                    value = if (inetPort != null) "${device.internetHost}:$inetPort" else device.internetHost
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(12.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                CardActionButton(
                                                    label = "Edit",
                                                    icon = Icons.Default.Edit,
                                                    tint = Color(0xFFA4B4FF),
                                                    background = Color(0x263B5BFF),
                                                    border = Color(0x553B5BFF),
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { deviceToEditId = device.id }
                                                )
                                                CardActionButton(
                                                    label = "Remove",
                                                    icon = Icons.Default.Delete,
                                                    tint = Color(0xFFFF8A80),
                                                    background = Color(0x26FF3B30),
                                                    border = Color(0x55FF3B30),
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { deviceToDeleteId = device.id }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Right-edge pocket marker suggests the row continues off-screen
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
                                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
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
                        Icon(
                            imageVector = VideocamIcon,
                            contentDescription = if (isRecording) "Stop Video Recording" else "Record Video",
                            tint = if (isRecording) Color(0xFFFF8A80) else Color.White,
                            modifier = Modifier.size(24.dp).rotate(iconRotation)
                        )
                    }

                    // Snap Photo (Center, main action, larger)
                    Button(
                        onClick = {
                            cameraController.takePhoto(
                                onSuccess = { file ->
                                    val ws = app.wsClient
                                    if (ws != null) {
                                        ws.pushPhoto(file)
                                        if (connectionState == "Connected") {
                                            Toast.makeText(context, "Photo copied to PC clipboard!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Saved locally. Will upload once connected.", Toast.LENGTH_LONG).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Saved locally. Receiver not set up.", Toast.LENGTH_LONG).show()
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
                        Icon(
                            imageVector = CameraIcon,
                            contentDescription = "Snap Photo",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp).rotate(iconRotation)
                        )
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
                        Icon(
                            imageVector = ImageIcon,
                            contentDescription = "Gallery",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp).rotate(iconRotation)
                        )
                    }
                }
            }
        }

        // Settings dropdown overlay
        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE60A0B0D))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettingsMenu = false }
            )
        }

        AnimatedVisibility(
            visible = showSettingsMenu,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(250)) + scaleIn(tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(200)) + scaleOut(tween(250, easing = FastOutSlowInEasing))
        ) {
            val isLandscape = currentRotation == 90 || currentRotation == 270
            val cameraPermissionGranted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val microphonePermissionGranted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            RotatedLayout(
                rotation = iconRotation,
                modifier = Modifier
                    .wrapContentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Consume clicks to prevent closing the menu when clicking on it */ }
            ) {
                if (isLandscape) {
                    LandscapeSettingsMenu(
                        pairedDevices = pairedDevices,
                        selectedDevice = selectedDevice,
                        connectionState = connectionState,
                        currentPolicy = currentPolicy,
                        receiverConnectionMode = receiverConnectionMode,
                        rotationLockMode = rotationLockMode,
                        cameraPermissionGranted = cameraPermissionGranted,
                        microphonePermissionGranted = microphonePermissionGranted,
                        notificationsGranted = notificationsGranted,
                        batteryUnrestricted = batteryUnrestricted,
                        isBackgroundCameraArmed = isBackgroundCameraArmed,
                        isBackgroundMicrophoneArmed = isBackgroundMicrophoneArmed,
                        isXiaomiDevice = isXiaomiDevice,
                        onCameraModeChange = { applyCameraMode(it) },
                        onConnectionModeChange = { applyConnectionMode(it) },
                        onRotationLockChange = { rotationLockMode = it },
                        onRequestReadinessPermissions = { requestBackgroundReadinessPermissions() },
                        onOpenBatterySettings = { openBatterySettings() },
                        onOpenXiaomiAutostartSettings = { openXiaomiAutostartSettings() },
                        hidePairedPcs = hidePairedPcs,
                        onHidePairedPcsChange = { updateHidePairedPcs(it) },
                        onEditPairedDevice = { device ->
                            showSettingsMenu = false
                            deviceToEditId = device.id
                        },
                        onDeletePairedDevice = { device ->
                            showSettingsMenu = false
                            deviceToDeleteId = device.id
                        },
                        appVersion = appVersion,
                        latestAppVersion = latestAppVersion,
                        onOpenLatestRelease = { openLatestRelease(context) },
                        onNavigateToPairing = {
                            showSettingsMenu = false
                            onNavigateToPairing()
                        }
                    )
                } else {
                    PortraitSettingsMenu(
                        pairedDevices = pairedDevices,
                        selectedDevice = selectedDevice,
                        connectionState = connectionState,
                        currentPolicy = currentPolicy,
                        receiverConnectionMode = receiverConnectionMode,
                        rotationLockMode = rotationLockMode,
                        cameraPermissionGranted = cameraPermissionGranted,
                        microphonePermissionGranted = microphonePermissionGranted,
                        notificationsGranted = notificationsGranted,
                        batteryUnrestricted = batteryUnrestricted,
                        isBackgroundCameraArmed = isBackgroundCameraArmed,
                        isBackgroundMicrophoneArmed = isBackgroundMicrophoneArmed,
                        isXiaomiDevice = isXiaomiDevice,
                        onCameraModeChange = { applyCameraMode(it) },
                        onConnectionModeChange = { applyConnectionMode(it) },
                        onRotationLockChange = { rotationLockMode = it },
                        onRequestReadinessPermissions = { requestBackgroundReadinessPermissions() },
                        onOpenBatterySettings = { openBatterySettings() },
                        onOpenXiaomiAutostartSettings = { openXiaomiAutostartSettings() },
                        hidePairedPcs = hidePairedPcs,
                        onHidePairedPcsChange = { updateHidePairedPcs(it) },
                        onEditPairedDevice = { device ->
                            showSettingsMenu = false
                            deviceToEditId = device.id
                        },
                        onDeletePairedDevice = { device ->
                            showSettingsMenu = false
                            deviceToDeleteId = device.id
                        },
                        appVersion = appVersion,
                        latestAppVersion = latestAppVersion,
                        onOpenLatestRelease = { openLatestRelease(context) },
                        onNavigateToPairing = {
                            showSettingsMenu = false
                            onNavigateToPairing()
                        }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AnimatedVisibility(
                visible = !showSettingsMenu,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { cameraController.toggleCamera() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = CameraswitchIcon,
                            contentDescription = "Switch camera",
                            tint = Color.White,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(iconRotation)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (!cameraController.toggleTorch()) {
                                Toast.makeText(context, "Flashlight unavailable on this camera", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = FlashlightIcon,
                            contentDescription = if (torchEnabled) "Turn flashlight off" else "Turn flashlight on",
                            tint = if (torchEnabled) Color(0xFFFFD54F) else Color.White,
                            modifier = Modifier
                                .size(19.dp)
                                .rotate(iconRotation)
                        )
                    }
                }
            }

            if (isRecording) {
                val infiniteTransition = rememberInfiniteTransition()
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f, targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(30.dp)
                        .offset(y = if (currentRotation == 90 || currentRotation == 270) 80.dp else 0.dp)
                        .rotate(iconRotation)
                        .background(Color(0x99FF3B30), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp),
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

            AnimatedVisibility(
                visible = !showSettingsMenu,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                IconButton(
                    onClick = { showSettingsMenu = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Receiver settings",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (showSettingsMenu) {
                IconButton(
                    onClick = { showSettingsMenu = false },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Receiver settings",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
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

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

@Composable
private fun SettingsStatusPill(
    text: String,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (isPositive) Color(0xFF4CAF50) else Color(0xFFFFC107)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x241F2128))
            .border(BorderStroke(1.dp, Color(0x18FFFFFF)), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ReadinessChecklistRow(
    label: String,
    ready: Boolean,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (isLandscape) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (isLandscape) 5.dp else 6.dp)
                .clip(CircleShape)
                .background(if (ready) Color(0xFF4CAF50) else Color(0xFFFFC107))
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = if (ready) 0.82f else 0.95f),
            fontSize = if (isLandscape) 9.sp else 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!ready && actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(if (isLandscape) 24.dp else 28.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = Color(0xFFA4B4FF),
                    fontSize = if (isLandscape) 9.sp else 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BackgroundReadinessSection(
    currentPolicy: CameraCapturePolicy,
    cameraPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
    notificationsGranted: Boolean,
    batteryUnrestricted: Boolean,
    isBackgroundCameraArmed: Boolean,
    isBackgroundMicrophoneArmed: Boolean,
    isXiaomiDevice: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    onRequestPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenXiaomiAutostartSettings: () -> Unit
) {
    val backgroundSelected = currentPolicy == CameraCapturePolicy.Background
    SettingsSectionCard(
        title = "Background mode readiness",
        caption = if (backgroundSelected) {
            "Checks needed for remote camera and video while the app is not open."
        } else {
            "Switch camera capture to Background to arm remote capture outside the app."
        },
        isLandscape = isLandscape,
        modifier = modifier
    ) {
        val readyCount = listOf(
            cameraPermissionGranted,
            microphonePermissionGranted,
            notificationsGranted,
            batteryUnrestricted,
            !backgroundSelected || isBackgroundCameraArmed,
            !backgroundSelected || isBackgroundMicrophoneArmed
        ).count { it }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (isLandscape) 5.dp else 7.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x18101114))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = if (isLandscape) 8.dp else 10.dp, vertical = if (isLandscape) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Permissions",
                color = Color.White.copy(alpha = 0.86f),
                fontSize = if (isLandscape) 10.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$readyCount/6",
                color = Color(0xFFA4B4FF),
                fontSize = if (isLandscape) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Hide permissions" else "Show permissions",
                tint = Color(0xFFA4B4FF),
                modifier = Modifier.size(if (isLandscape) 15.dp else 16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = if (isLandscape) 4.dp else 6.dp)) {
                ReadinessChecklistRow(
                    label = "Camera permission",
                    ready = cameraPermissionGranted,
                    isLandscape = isLandscape,
                    actionLabel = "Allow",
                    onAction = onRequestPermissions
                )
                ReadinessChecklistRow(
                    label = "Microphone permission",
                    ready = microphonePermissionGranted,
                    isLandscape = isLandscape,
                    actionLabel = "Allow",
                    onAction = onRequestPermissions
                )
                ReadinessChecklistRow(
                    label = "Notifications",
                    ready = notificationsGranted,
                    isLandscape = isLandscape,
                    actionLabel = "Allow",
                    onAction = onRequestPermissions
                )
                ReadinessChecklistRow(
                    label = "Battery unrestricted",
                    ready = batteryUnrestricted,
                    isLandscape = isLandscape,
                    actionLabel = "Open",
                    onAction = onOpenBatterySettings
                )
                ReadinessChecklistRow(
                    label = "Camera service armed",
                    ready = !backgroundSelected || isBackgroundCameraArmed,
                    isLandscape = isLandscape
                )
                ReadinessChecklistRow(
                    label = "Microphone service armed",
                    ready = !backgroundSelected || isBackgroundMicrophoneArmed,
                    isLandscape = isLandscape
                )
                if (isXiaomiDevice) {
                    ReadinessChecklistRow(
                        label = "Xiaomi autostart",
                        ready = false,
                        isLandscape = isLandscape,
                        actionLabel = "Check",
                        onAction = onOpenXiaomiAutostartSettings
                    )
                }
            }
        }
        if (isXiaomiDevice && !expanded) {
            ReadinessChecklistRow(
                label = "Xiaomi autostart",
                ready = false,
                isLandscape = isLandscape,
                actionLabel = "Check",
                onAction = onOpenXiaomiAutostartSettings
            )
        }
    }
}

// A single endpoint row inside an expanded receiver card: a small label chip + monospace value.
@Composable
private fun EndpointLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x1FFFFFFF))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFFB7B9C4),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = value,
            color = Color(0xFF9A9CA6),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Labeled pill button used for the Edit / Remove actions on a receiver card.
@Composable
private fun CardActionButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    background: Color,
    border: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PairedPcSettingsSection(
    pairedDevices: List<PairedDevice>,
    selectedDevice: PairedDevice?,
    hidePairedPcs: Boolean,
    onHidePairedPcsChange: (Boolean) -> Unit,
    onEditDevice: (PairedDevice) -> Unit,
    onDeleteDevice: (PairedDevice) -> Unit,
    isLandscape: Boolean = false
) {
    SettingsSectionCard(
        title = "Paired PCs",
        caption = if (hidePairedPcs) {
            "Receivers are hidden from the camera screen and shown here."
        } else {
            "Keep receiver cards visible on the camera screen."
        },
        isLandscape = isLandscape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (isLandscape) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hide paired PCs",
                color = Color.White.copy(alpha = 0.86f),
                fontSize = if (isLandscape) 10.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = hidePairedPcs,
                onCheckedChange = onHidePairedPcsChange,
                modifier = Modifier.height(if (isLandscape) 28.dp else 32.dp)
            )
        }
        if (hidePairedPcs) {
            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 6.dp))
            if (pairedDevices.isEmpty()) {
                Text(
                    text = "No paired PCs yet",
                    color = Color(0x8CFFFFFF),
                    fontSize = if (isLandscape) 9.sp else 10.sp
                )
            } else {
                pairedDevices.forEach { device ->
                    val displayName = device.alias.ifBlank { device.name }
                    val selected = selectedDevice?.id == device.id
                    val hostText = device.localHosts.ifBlank { device.host }
                        .split(',').firstOrNull()?.trim().orEmpty()
                    val btnSize = if (isLandscape) 26.dp else 30.dp
                    val iconSize = if (isLandscape) 12.dp else 14.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = if (isLandscape) 2.dp else 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isLandscape) 5.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color(0xFF4CAF50) else Color(0x4DFFFFFF))
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                color = Color.White.copy(alpha = if (selected) 0.95f else 0.72f),
                                fontSize = if (isLandscape) 9.sp else 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hostText.isNotEmpty()) {
                                Text(
                                    text = "${device.os.uppercase()} · $hostText",
                                    color = Color(0x66FFFFFF),
                                    fontSize = if (isLandscape) 8.sp else 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { onEditDevice(device) },
                            modifier = Modifier
                                .size(btnSize)
                                .clip(CircleShape)
                                .background(Color(0x263B5BFF), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit $displayName",
                                tint = Color(0xFFA4B4FF),
                                modifier = Modifier.size(iconSize)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { onDeleteDevice(device) },
                            modifier = Modifier
                                .size(btnSize)
                                .clip(CircleShape)
                                .background(Color(0x26FF3B30), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove $displayName",
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    caption: String = "",
    isLandscape: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x261F2128))
            .border(BorderStroke(1.dp, Color(0x18FFFFFF)), RoundedCornerShape(20.dp))
            .padding(if (isLandscape) 8.dp else 12.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = if (isLandscape) 12.sp else 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
        if (caption.isNotEmpty()) {
            Text(
                text = caption,
                color = Color(0x8CFFFFFF),
                fontSize = if (isLandscape) 10.sp else 11.sp,
                lineHeight = if (isLandscape) 13.sp else 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        content()
    }
}

@Composable
private fun CompactSettingsChoiceTile(
    title: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    onClick: () -> Unit
) {
    val horizontalPadding = if (isLandscape) 4.dp else 4.dp
    val verticalPadding = if (isLandscape) 5.dp else 7.dp
    val dotSize = if (isLandscape) 4.dp else 4.dp
    val spacing = if (isLandscape) 4.dp else 4.dp
    val fontSize = if (isLandscape) 10.sp else 10.5.sp

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) Color(0x303B5BFF) else Color(0x18101114))
            .border(
                BorderStroke(1.dp, if (active) Color(0x663B5BFF) else Color(0x12FFFFFF)),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFFA4B4FF) else Color(0x4DFFFFFF))
            )
            Spacer(modifier = Modifier.width(spacing))
            Text(
                text = title,
                color = if (active) Color(0xFFDEE0FF) else Color.White.copy(alpha = 0.82f),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RotatedLayout(
    rotation: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = content,
        modifier = modifier.rotate(rotation)
    ) { measurables, constraints ->
        val isRotated = rotation == 90f || rotation == 270f
        val childConstraints = if (isRotated) {
            constraints.copy(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth
            )
        } else {
            constraints
        }

        val placeables = measurables.map { it.measure(childConstraints) }

        val width = if (isRotated) {
            placeables.maxOfOrNull { it.height } ?: 0
        } else {
            placeables.maxOfOrNull { it.width } ?: 0
        }

        val height = if (isRotated) {
            placeables.maxOfOrNull { it.width } ?: 0
        } else {
            placeables.maxOfOrNull { it.height } ?: 0
        }

        layout(width, height) {
            placeables.forEach { placeable ->
                if (isRotated) {
                    val x = (width - placeable.width) / 2
                    val y = (height - placeable.height) / 2
                    placeable.place(x, y)
                } else {
                    placeable.place(0, 0)
                }
            }
        }
    }
}

@Composable
private fun PortraitSettingsMenu(
    pairedDevices: List<PairedDevice>,
    selectedDevice: PairedDevice?,
    connectionState: String,
    currentPolicy: CameraCapturePolicy,
    receiverConnectionMode: ReceiverConnectionMode,
    rotationLockMode: RotationLockMode,
    cameraPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
    notificationsGranted: Boolean,
    batteryUnrestricted: Boolean,
    isBackgroundCameraArmed: Boolean,
    isBackgroundMicrophoneArmed: Boolean,
    isXiaomiDevice: Boolean,
    onCameraModeChange: (CameraCapturePolicy) -> Unit,
    onConnectionModeChange: (ReceiverConnectionMode) -> Unit,
    onRotationLockChange: (RotationLockMode) -> Unit,
    onRequestReadinessPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenXiaomiAutostartSettings: () -> Unit,
    hidePairedPcs: Boolean,
    onHidePairedPcsChange: (Boolean) -> Unit,
    onEditPairedDevice: (PairedDevice) -> Unit,
    onDeletePairedDevice: (PairedDevice) -> Unit,
    appVersion: String,
    latestAppVersion: String?,
    onOpenLatestRelease: () -> Unit,
    onNavigateToPairing: () -> Unit
) {
    var readinessExpanded by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth(0.92f)
            .heightIn(max = 560.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFB16171C),
                        Color(0xFB0F1013)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, Color(0x20FFFFFF)),
                RoundedCornerShape(28.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Receiver Control",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    Text(
                        text = selectedDevice?.let { it.alias.ifBlank { it.name } } ?: "No PC selected",
                        color = Color(0xA6FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appVersion.isNotBlank()) {
                        Text(
                            text = versionStatusText(appVersion, latestAppVersion),
                            color = Color(0x66FFFFFF),
                            fontSize = 10.sp,
                            maxLines = 1,
                            modifier = Modifier.clickable(onClick = onOpenLatestRelease)
                        )
                    }
                }
                SettingsStatusPill(
                    text = connectionState,
                    isPositive = connectionState == "Connected"
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 1. Camera Capture
            SettingsSectionCard(
                title = "Camera capture",
                caption = "Choose whether remote photo and video capture works only on this screen or can use the armed background service."
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CameraCapturePolicy.values().forEach { policy ->
                        CompactSettingsChoiceTile(
                            title = policy.label.removePrefix("Camera: ").replaceFirstChar { it.uppercase() },
                            active = currentPolicy == policy,
                            modifier = Modifier.weight(1f),
                            onClick = { onCameraModeChange(policy) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            BackgroundReadinessSection(
                currentPolicy = currentPolicy,
                cameraPermissionGranted = cameraPermissionGranted,
                microphonePermissionGranted = microphonePermissionGranted,
                notificationsGranted = notificationsGranted,
                batteryUnrestricted = batteryUnrestricted,
                isBackgroundCameraArmed = isBackgroundCameraArmed,
                isBackgroundMicrophoneArmed = isBackgroundMicrophoneArmed,
                isXiaomiDevice = isXiaomiDevice,
                expanded = readinessExpanded,
                onExpandedChange = { readinessExpanded = it },
                onRequestPermissions = onRequestReadinessPermissions,
                onOpenBatterySettings = onOpenBatterySettings,
                onOpenXiaomiAutostartSettings = onOpenXiaomiAutostartSettings
            )

            Spacer(modifier = Modifier.height(10.dp))

            PairedPcSettingsSection(
                pairedDevices = pairedDevices,
                selectedDevice = selectedDevice,
                hidePairedPcs = hidePairedPcs,
                onHidePairedPcsChange = onHidePairedPcsChange,
                onEditDevice = onEditPairedDevice,
                onDeleteDevice = onDeletePairedDevice
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Connection Route
            SettingsSectionCard(title = "Connection route") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReceiverConnectionMode.values().forEach { mode ->
                        CompactSettingsChoiceTile(
                            title = when (mode) {
                                ReceiverConnectionMode.LocalOnly -> "Local"
                                ReceiverConnectionMode.LocalThenInternet -> "Mixed"
                                ReceiverConnectionMode.InternetOnly -> "Internet"
                            },
                            active = receiverConnectionMode == mode,
                            modifier = Modifier.weight(1f),
                            onClick = { onConnectionModeChange(mode) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Screen Rotation
            SettingsSectionCard(title = "Screen rotation") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RotationLockMode.values().forEach { mode ->
                        CompactSettingsChoiceTile(
                            title = when (mode) {
                                RotationLockMode.AUTO -> "Auto"
                                RotationLockMode.PORTRAIT -> "Portrait"
                                RotationLockMode.LANDSCAPE -> "Landscape"
                            },
                            active = rotationLockMode == mode,
                            modifier = Modifier.weight(1f),
                            onClick = { onRotationLockChange(mode) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Add & Pair Device
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x18FFFFFF))
                    .border(BorderStroke(1.dp, Color(0x18FFFFFF)), RoundedCornerShape(18.dp))
                    .clickable { onNavigateToPairing() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0x12FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add & Pair a New PC",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Open scanner and pair another receiver",
                        color = Color(0x8CFFFFFF),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hint
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tap outside or press Back to close",
                    color = Color(0x73FFFFFF),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                )
            }
        }
    }
}

@Composable
private fun LandscapeSettingsMenu(
    pairedDevices: List<PairedDevice>,
    selectedDevice: PairedDevice?,
    connectionState: String,
    currentPolicy: CameraCapturePolicy,
    receiverConnectionMode: ReceiverConnectionMode,
    rotationLockMode: RotationLockMode,
    cameraPermissionGranted: Boolean,
    microphonePermissionGranted: Boolean,
    notificationsGranted: Boolean,
    batteryUnrestricted: Boolean,
    isBackgroundCameraArmed: Boolean,
    isBackgroundMicrophoneArmed: Boolean,
    isXiaomiDevice: Boolean,
    onCameraModeChange: (CameraCapturePolicy) -> Unit,
    onConnectionModeChange: (ReceiverConnectionMode) -> Unit,
    onRotationLockChange: (RotationLockMode) -> Unit,
    onRequestReadinessPermissions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenXiaomiAutostartSettings: () -> Unit,
    hidePairedPcs: Boolean,
    onHidePairedPcsChange: (Boolean) -> Unit,
    onEditPairedDevice: (PairedDevice) -> Unit,
    onDeletePairedDevice: (PairedDevice) -> Unit,
    appVersion: String,
    latestAppVersion: String?,
    onOpenLatestRelease: () -> Unit,
    onNavigateToPairing: () -> Unit
) {
    var readinessExpanded by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(480.dp)
            .height(268.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFB16171C),
                        Color(0xFB0F1013)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, Color(0x20FFFFFF)),
                RoundedCornerShape(24.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Column 1 (Left): Header info, Screen Rotation, Add Device button, Hint
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Info
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Receiver Control",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        SettingsStatusPill(
                            text = connectionState,
                            isPositive = connectionState == "Connected"
                        )
                    }
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = selectedDevice?.let { it.alias.ifBlank { it.name } } ?: "No PC selected",
                        color = Color(0xA6FFFFFF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (appVersion.isNotBlank()) {
                        Text(
                            text = versionStatusText(appVersion, latestAppVersion),
                            color = Color(0x66FFFFFF),
                            fontSize = 9.sp,
                            maxLines = 1,
                            modifier = Modifier.clickable(onClick = onOpenLatestRelease)
                        )
                    }
                }

                PairedPcSettingsSection(
                    pairedDevices = pairedDevices,
                    selectedDevice = selectedDevice,
                    hidePairedPcs = hidePairedPcs,
                    onHidePairedPcsChange = onHidePairedPcsChange,
                    onEditDevice = onEditPairedDevice,
                    onDeleteDevice = onDeletePairedDevice,
                    isLandscape = true
                )

                // Screen Rotation Section (Row of 3 choices)
                SettingsSectionCard(
                    title = "Screen rotation",
                    isLandscape = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RotationLockMode.values().forEach { mode ->
                            CompactSettingsChoiceTile(
                                title = when (mode) {
                                    RotationLockMode.AUTO -> "Auto"
                                    RotationLockMode.PORTRAIT -> "Portrait"
                                    RotationLockMode.LANDSCAPE -> "Landscape"
                                },
                                active = rotationLockMode == mode,
                                isLandscape = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onRotationLockChange(mode) }
                            )
                        }
                    }
                }

                // Add Device Button (Sleeker and more compact)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x18FFFFFF))
                        .border(BorderStroke(1.dp, Color(0x18FFFFFF)), RoundedCornerShape(12.dp))
                        .clickable { onNavigateToPairing() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0x12FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add & Pair a New PC",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Hint
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tap outside/Back to close",
                        color = Color(0x66FFFFFF),
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(Color(0x22FFFFFF))
                    )
                }
            }

            // Column 2 (Right): Camera Capture and Connection Route
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Camera Capture
                SettingsSectionCard(
                    title = "Camera capture",
                    caption = "Choose whether remote capture uses this screen or the armed background service.",
                    isLandscape = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CameraCapturePolicy.values().forEach { policy ->
                            CompactSettingsChoiceTile(
                                title = policy.label.removePrefix("Camera: ").replaceFirstChar { it.uppercase() },
                                active = currentPolicy == policy,
                                isLandscape = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onCameraModeChange(policy) }
                            )
                        }
                    }
                }

                BackgroundReadinessSection(
                    currentPolicy = currentPolicy,
                    cameraPermissionGranted = cameraPermissionGranted,
                    microphonePermissionGranted = microphonePermissionGranted,
                    notificationsGranted = notificationsGranted,
                    batteryUnrestricted = batteryUnrestricted,
                    isBackgroundCameraArmed = isBackgroundCameraArmed,
                    isBackgroundMicrophoneArmed = isBackgroundMicrophoneArmed,
                    isXiaomiDevice = isXiaomiDevice,
                    expanded = readinessExpanded,
                    onExpandedChange = { readinessExpanded = it },
                    isLandscape = true,
                    modifier = Modifier.fillMaxWidth(),
                    onRequestPermissions = onRequestReadinessPermissions,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenXiaomiAutostartSettings = onOpenXiaomiAutostartSettings
                )

                // 2. Connection Route
                SettingsSectionCard(
                    title = "Connection route",
                    isLandscape = true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ReceiverConnectionMode.values().forEach { mode ->
                            CompactSettingsChoiceTile(
                                title = when (mode) {
                                    ReceiverConnectionMode.LocalOnly -> "Local"
                                    ReceiverConnectionMode.LocalThenInternet -> "Mixed"
                                    ReceiverConnectionMode.InternetOnly -> "Internet"
                                },
                                active = receiverConnectionMode == mode,
                                isLandscape = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onConnectionModeChange(mode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsChoiceTile(
    title: String,
    subtitle: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0x303B5BFF) else Color(0x18101114))
            .border(
                BorderStroke(1.dp, if (active) Color(0x663B5BFF) else Color(0x12FFFFFF)),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFFA4B4FF) else Color(0x4DFFFFFF))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = if (active) Color(0xFFDEE0FF) else Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = subtitle,
            color = Color(0x80FFFFFF),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, top = 3.dp)
        )
    }
}

@Composable
private fun ConnectionModeRow(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Color(0x303B5BFF) else Color(0x12101114))
            .border(
                BorderStroke(1.dp, if (active) Color(0x663B5BFF) else Color(0x10FFFFFF)),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (active) Color(0xFFDEE0FF) else Color.White.copy(alpha = 0.86f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color(0x78FFFFFF),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(if (active) 18.dp else 14.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFFA4B4FF) else Color(0x22FFFFFF))
                .border(BorderStroke(1.dp, Color(0x24FFFFFF)), CircleShape)
        )
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

val CameraswitchIcon: ImageVector
    get() = ImageVector.Builder(
        name = "CameraswitchIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // First arrow/curve
            moveTo(12f, 6f)
            verticalLineTo(9f)
            lineTo(16f, 5f)
            lineTo(12f, 1f)
            verticalLineTo(4f)
            curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
            curveTo(4f, 13.57f, 4.46f, 15.03f, 5.24f, 16.26f)
            lineTo(6.7f, 14.8f)
            curveTo(6.25f, 14.1f, 6f, 13.08f, 6f, 12f)
            curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
            close()

            // Second arrow/curve
            moveTo(18.76f, 7.74f)
            lineTo(17.3f, 9.2f)
            curveTo(17.74f, 9.91f, 18f, 10.75f, 18f, 11.64f)
            curveTo(18f, 14.95f, 15.31f, 17.64f, 12f, 17.64f)
            verticalLineTo(14.64f)
            lineTo(8f, 18.64f)
            lineTo(12f, 22.64f)
            verticalLineTo(19.64f)
            curveTo(16.42f, 19.64f, 20f, 16.06f, 20f, 11.64f)
            curveTo(20f, 10.07f, 19.54f, 8.61f, 18.76f, 7.74f)
            close()
        }
    }.build()

val FlashlightIcon: ImageVector
    get() = ImageVector.Builder(
        name = "FlashlightIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(8f, 2f)
            horizontalLineTo(16f)
            curveTo(16.55f, 2f, 17f, 2.45f, 17f, 3f)
            verticalLineTo(7f)
            curveTo(17f, 7.34f, 16.83f, 7.66f, 16.55f, 7.84f)
            lineTo(15f, 8.87f)
            verticalLineTo(20f)
            curveTo(15f, 21.1f, 14.1f, 22f, 13f, 22f)
            horizontalLineTo(11f)
            curveTo(9.9f, 22f, 9f, 21.1f, 9f, 20f)
            verticalLineTo(8.87f)
            lineTo(7.45f, 7.84f)
            curveTo(7.17f, 7.66f, 7f, 7.34f, 7f, 7f)
            verticalLineTo(3f)
            curveTo(7f, 2.45f, 7.45f, 2f, 8f, 2f)
            close()
            moveTo(9f, 4f)
            verticalLineTo(6.46f)
            lineTo(10.55f, 7.49f)
            curveTo(10.83f, 7.68f, 11f, 7.99f, 11f, 8.33f)
            verticalLineTo(20f)
            horizontalLineTo(13f)
            verticalLineTo(8.33f)
            curveTo(13f, 7.99f, 13.17f, 7.68f, 13.45f, 7.49f)
            lineTo(15f, 6.46f)
            verticalLineTo(4f)
            horizontalLineTo(9f)
            close()
        }
    }.build()

val LockOpenIcon: ImageVector
    get() = ImageVector.Builder(
        name = "LockOpenIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 17f)
            curveTo(13.1f, 17f, 14f, 16.1f, 14f, 15f)
            reflectiveCurveTo(13.1f, 13f, 12f, 13f)
            reflectiveCurveTo(10f, 13.9f, 10f, 15f)
            reflectiveCurveTo(10.9f, 17f, 12f, 17f)
            close()
            moveTo(18f, 8f)
            horizontalLineTo(17f)
            verticalLineTo(6f)
            curveTo(17f, 3.24f, 14.76f, 1f, 12f, 1f)
            reflectiveCurveTo(7f, 3.24f, 7f, 6f)
            horizontalLineTo(9f)
            curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f)
            reflectiveCurveTo(15f, 4.34f, 15f, 6f)
            verticalLineTo(8f)
            horizontalLineTo(6f)
            curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f)
            verticalLineTo(20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(10f)
            curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f)
            close()
            moveTo(18f, 20f)
            horizontalLineTo(6f)
            verticalLineTo(10f)
            horizontalLineTo(18f)
            verticalLineTo(20f)
            close()
        }
    }.build()

