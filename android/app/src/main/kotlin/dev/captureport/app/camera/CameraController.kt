package dev.captureport.app.camera

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.QualitySelector
import androidx.camera.video.Quality
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executor

class CameraController(private val context: Context) {

    val cameraController = LifecycleCameraController(context).apply {
        setEnabledUseCases(
            LifecycleCameraController.IMAGE_CAPTURE or
            LifecycleCameraController.VIDEO_CAPTURE or
            LifecycleCameraController.IMAGE_ANALYSIS
        )
        videoCaptureQualitySelector = QualitySelector.from(Quality.HD)
    }

    private var activeRecording: Recording? = null
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    // Snap photo using CameraX ImageCapture pipeline
    fun takePhoto(
        onSuccess: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val file = File(context.cacheDir, "CP_cap_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        cameraController.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSuccess(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    // Start video recording at 720p H.264
    @android.annotation.SuppressLint("MissingPermission")
    fun startVideoRecording(
        onEvent: (VideoRecordEvent) -> Unit
    ): File {
        val file = File(context.cacheDir, "CP_vid_${System.currentTimeMillis()}.mp4")
        val fileOutputOptions = FileOutputOptions.Builder(file).build()
        val audioConfig = androidx.camera.view.video.AudioConfig.create(true)

        // CameraX LifecycleCameraController provides a simple startRecording wrapper
        val recording = cameraController.startRecording(
            fileOutputOptions,
            audioConfig,
            mainExecutor
        ) { event ->
            onEvent(event)
        }

        activeRecording = recording
        return file
    }

    // Stop current video recording session
    fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    // Set real-time ML Kit QR scanner analyzer inside the CameraX ImageAnalysis pipeline
    @OptIn(ExperimentalGetImage::class)
    fun startQrScanning(onQrCodeScanned: (String) -> Unit) {
        cameraController.setImageAnalysisAnalyzer(
            mainExecutor
        ) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val inputImage = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )
                val scanner = BarcodeScanning.getClient()
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val raw = barcode.rawValue
                            if (raw != null && raw.startsWith("captureport://pair")) {
                                onQrCodeScanned(raw)
                            }
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    // Unregisters analysis callbacks to stop scanning
    fun stopQrScanning() {
        cameraController.clearImageAnalysisAnalyzer()
    }
}
