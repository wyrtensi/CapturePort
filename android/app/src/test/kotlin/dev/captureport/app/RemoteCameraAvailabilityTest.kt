package dev.captureport.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCameraAvailabilityTest {

    @Test
    fun screenOnlyRequiresVisibleCameraScreen() {
        assertTrue(
            isRemoteCameraCaptureAllowed(
                policy = CameraCapturePolicy.ScreenOnly,
                isCameraScreenVisible = true,
                isBackgroundCameraArmed = false
            )
        )

        assertFalse(
            isRemoteCameraCaptureAllowed(
                policy = CameraCapturePolicy.ScreenOnly,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = true
            )
        )
    }

    @Test
    fun backgroundRequiresVisibleCameraScreenOrArmedService() {
        assertTrue(
            isRemoteCameraCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = true,
                isBackgroundCameraArmed = false
            )
        )
        assertTrue(
            isRemoteCameraCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = true
            )
        )
        assertFalse(
            isRemoteCameraCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = false
            )
        )
    }

    @Test
    fun backgroundVideoRequiresCameraAndMicrophoneArmed() {
        assertTrue(
            isRemoteVideoCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = true,
                isBackgroundCameraArmed = false,
                isBackgroundMicrophoneArmed = false
            )
        )
        assertTrue(
            isRemoteVideoCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = true,
                isBackgroundMicrophoneArmed = true
            )
        )
        assertFalse(
            isRemoteVideoCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = true,
                isBackgroundMicrophoneArmed = false
            )
        )
        assertFalse(
            isRemoteVideoCaptureAllowed(
                policy = CameraCapturePolicy.Background,
                isCameraScreenVisible = false,
                isBackgroundCameraArmed = false,
                isBackgroundMicrophoneArmed = true
            )
        )
    }

    @Test
    fun backgroundModeReadinessRequiresPermissionsAndArmedService() {
        assertTrue(
            isBackgroundModeReady(
                policy = CameraCapturePolicy.ScreenOnly,
                cameraPermissionGranted = false,
                microphonePermissionGranted = false,
                notificationsGranted = false,
                isBackgroundCameraArmed = false,
                isBackgroundMicrophoneArmed = false
            )
        )

        assertTrue(
            isBackgroundModeReady(
                policy = CameraCapturePolicy.Background,
                cameraPermissionGranted = true,
                microphonePermissionGranted = true,
                notificationsGranted = true,
                isBackgroundCameraArmed = true,
                isBackgroundMicrophoneArmed = true
            )
        )

        assertFalse(
            isBackgroundModeReady(
                policy = CameraCapturePolicy.Background,
                cameraPermissionGranted = true,
                microphonePermissionGranted = false,
                notificationsGranted = true,
                isBackgroundCameraArmed = true,
                isBackgroundMicrophoneArmed = true
            )
        )
    }

    @Test
    fun xiaomiAutostartHintDetectsHyperOsAndMiuiFamilies() {
        assertTrue(shouldShowXiaomiAutostartHint("Xiaomi", "OS3.0.300"))
        assertTrue(shouldShowXiaomiAutostartHint("POCO", "Android 16"))
        assertTrue(shouldShowXiaomiAutostartHint("Google", "MIUI 14"))
        assertTrue(shouldShowXiaomiAutostartHint("Google", "HyperOS"))
        assertFalse(shouldShowXiaomiAutostartHint("Google", "AP4A"))
    }
}
