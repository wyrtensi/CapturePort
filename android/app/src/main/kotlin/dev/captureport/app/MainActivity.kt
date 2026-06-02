package dev.captureport.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.captureport.app.network.WsClient
import dev.captureport.app.pairing.PairingScreen
import dev.captureport.app.pairing.PairingViewModel
import dev.captureport.app.receivers.ReceiversScreen
import dev.captureport.app.receivers.ReceiversViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        
        if (!cameraGranted || !audioGranted) {
            Toast.makeText(
                this,
                "Camera and Audio permissions are required for full CapturePort functionality.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request missing permissions at startup
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        val app = application as CapturePortApp
        
        // Instantiate the global WsClient if not done already
        if (app.wsClient == null) {
            app.wsClient = WsClient(
                context = applicationContext,
                scope = app.applicationScope,
                onCaptureRequest = { onPhotoSnapped ->
                    val activeCam = app.activeCameraController
                    if (activeCam != null) {
                        activeCam.takePhoto(
                            onSuccess = { file -> onPhotoSnapped(file) },
                            onError = { err -> Log.e("MainActivity", "MCP photo snap failed: ${err.message}") }
                        )
                    } else {
                        Log.e("MainActivity", "No active camera controller found for MCP photo capture!")
                    }
                }
            )
        }

        // Build ViewModels
        val receiversViewModel = ViewModelProvider(
            this,
            ReceiversViewModel.Factory(app.pairedDevicesRepository)
        )[ReceiversViewModel::class.java]


        val pairingViewModelFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PairingViewModel(app.pairedDevicesRepository) as T
            }
        }
        val pairingVm = ViewModelProvider(this, pairingViewModelFactory)[PairingViewModel::class.java]

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            ReceiversScreen(
                                viewModel = receiversViewModel,
                                onNavigateToPairing = {
                                    navController.navigate("pairing")
                                }
                            )
                        }
                        composable("pairing") {
                            PairingScreen(
                                viewModel = pairingVm,
                                onPairingCompleted = {
                                    navController.popBackStack()
                                },
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // In clean shutdown, we don't disconnect the wsClient because we want it to survive temporary task shutdowns
        // if the app FGS is still running or we want active camera reconnects.
    }
}
