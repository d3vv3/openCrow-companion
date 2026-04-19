package org.opencrow.app.ui.screens

import android.Manifest
import android.graphics.ImageFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.data.remote.dto.DeviceCapability
import org.opencrow.app.data.remote.dto.QrPayload
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest
import org.opencrow.app.ui.theme.LocalSpacing
import java.nio.ByteBuffer

@Composable
fun QrScanScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current

    var hasCameraPermission by remember { mutableStateOf(false) }
    var permissionsRequested by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        permissionsRequested = true
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    fun handleQrScanned(raw: String) {
        if (pairing) return
        pairing = true
        error = null
        scope.launch {
            try {
                val payload = Gson().fromJson(raw, QrPayload::class.java)
                if (payload.id.isNullOrBlank() || payload.server.isNullOrBlank() ||
                    payload.accessToken.isNullOrBlank() || payload.refreshToken.isNullOrBlank()
                ) {
                    error = "Invalid QR code format"
                    pairing = false
                    return@launch
                }

                Log.d("QrScan", "QR payload: server=${payload.server}, id=${payload.id}")

                app.apiClient.configure(payload.server, payload.accessToken, payload.refreshToken)
                app.apiClient.saveTokens(
                    payload.server, payload.accessToken, payload.refreshToken, payload.id
                )

                // Step 1: Check server is reachable (no auth needed)
                val healthResp = try {
                    app.apiClient.api.health()
                } catch (e: Exception) {
                    Log.e("QrScan", "Health check failed: ${e.message}", e)
                    error = "Cannot reach server at ${payload.server}: ${e.message}"
                    pairing = false
                    return@launch
                }
                if (!healthResp.isSuccessful) {
                    Log.e("QrScan", "Health check returned ${healthResp.code()}")
                    error = "Server at ${payload.server} returned ${healthResp.code()} on health check"
                    pairing = false
                    return@launch
                }

                // Step 2: Validate auth tokens work
                val authResp = try {
                    app.apiClient.api.listConversations()
                } catch (e: Exception) {
                    Log.e("QrScan", "Auth validation failed: ${e.message}", e)
                    error = "Server reachable but auth failed: ${e.message}"
                    pairing = false
                    return@launch
                }
                if (!authResp.isSuccessful) {
                    Log.e("QrScan", "Auth validation returned ${authResp.code()}: ${authResp.errorBody()?.string()}")
                    error = "Server reachable but auth failed (${authResp.code()})"
                    pairing = false
                    return@launch
                }

                // Register device capabilities (best-effort, don't block pairing)
                val capabilities = listOf(
                    DeviceCapability("set_alarm", "Set a one-time or recurring alarm"),
                    DeviceCapability("create_contact", "Add a contact to the phone's address book"),
                    DeviceCapability("make_call", "Initiate a phone call to a number"),
                    DeviceCapability("send_sms", "Send an SMS to a number"),
                    DeviceCapability("create_calendar_event", "Add an event to the calendar")
                )
                try {
                    app.apiClient.api.registerDevice(
                        payload.id, RegisterDeviceRequest(capabilities)
                    )
                } catch (e: Exception) {
                    Log.w("QrScan", "Device register failed (non-fatal): ${e.message}")
                }

                onPaired()
            } catch (e: Exception) {
                Log.e("QrScan", "Pairing failed", e)
                error = "Pairing failed: ${e.message}"
                pairing = false
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "openCrow",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(spacing.sm))
            Text(
                text = "Scan the pairing QR code from your openCrow web UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(spacing.xl))

            if (!permissionsRequested) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else if (!hasCameraPermission) {
                Text(
                    text = "Camera permission is required to scan the QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    QrCameraPreview(onQrDetected = { handleQrScanned(it) })
                    if (pairing) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(spacing.md))
                                    Text("Pairing...", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
fun QrCameraPreview(onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detected by remember { mutableStateOf(false) }

    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            if (!detected) {
                                val result = decodeQr(imageProxy, reader)
                                if (result != null) {
                                    detected = true
                                    onQrDetected(result)
                                }
                            }
                            imageProxy.close()
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    Log.e("QrCamera", "Camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val source = PlanarYUVLuminanceSource(
        bytes,
        image.width,
        image.height,
        0, 0,
        image.width,
        image.height,
        false
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
