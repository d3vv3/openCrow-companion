package org.opencrow.app.ui.screens.qrscan

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.theme.LocalSpacing
import java.nio.ByteBuffer

@Composable
fun QrScanScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val viewModel: QrScanViewModel = viewModel(
        factory = QrScanViewModel.Factory(app.container.apiClient)
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        viewModel.onPermissionsResult(permissions[Manifest.permission.CAMERA] == true)
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

    LaunchedEffect(state.paired) {
        if (state.paired) onPaired()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(spacing.xl))

            // App name
            Text(
                text = "openCrow",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = "Pair device",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(spacing.md))
            Text(
                text = "Scan the pairing QR code from your openCrow web UI",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(spacing.xl))

            if (!state.permissionsRequested) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (!state.hasCameraPermission) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Camera permission required to scan the QR code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(spacing.md)
                        )
                    }
                }
            } else {
                // Camera scanner with DESIGN.md corner markers
                val primaryColor = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    QrCameraPreview(
                        scanning = !state.pairing && !state.paired,
                        onQrDetected = viewModel::handleQrScanned
                    )

                    // Corner bracket markers
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val len = 32.dp.toPx()
                        val thick = 3.dp.toPx()
                        val inset = thick / 2

                        // top-left
                        drawLine(primaryColor, Offset(inset, inset), Offset(len, inset), thick)
                        drawLine(primaryColor, Offset(inset, inset), Offset(inset, len), thick)
                        // top-right
                        drawLine(primaryColor, Offset(size.width - inset, inset), Offset(size.width - len, inset), thick)
                        drawLine(primaryColor, Offset(size.width - inset, inset), Offset(size.width - inset, len), thick)
                        // bottom-left
                        drawLine(primaryColor, Offset(inset, size.height - inset), Offset(len, size.height - inset), thick)
                        drawLine(primaryColor, Offset(inset, size.height - inset), Offset(inset, size.height - len), thick)
                        // bottom-right
                        drawLine(primaryColor, Offset(size.width - inset, size.height - inset), Offset(size.width - len, size.height - inset), thick)
                        drawLine(primaryColor, Offset(size.width - inset, size.height - inset), Offset(size.width - inset, size.height - len), thick)
                    }

                    // Pairing overlay
                    if (state.pairing) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.height(spacing.sm))
                                Text(
                                    "Pairing...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Helper text below scanner
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = "Position the QR code within the frame",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            state.error?.let {
                Spacer(Modifier.height(spacing.md))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)
                    )
                }
            }
            Spacer(Modifier.height(spacing.lg))
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
fun QrCameraPreview(scanning: Boolean, onQrDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanningState = rememberUpdatedState(scanning)

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
                            if (scanningState.value) {
                                val result = decodeQr(imageProxy, reader)
                                if (result != null) onQrDetected(result)
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
        bytes, image.width, image.height,
        0, 0, image.width, image.height, false
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
