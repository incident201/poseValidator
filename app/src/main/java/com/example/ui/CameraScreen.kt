package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.tracker.PoseLandmarkerService
import com.example.ui.theme.*
import com.example.viewmodel.GameState
import com.example.viewmodel.GameViewModel
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val gameState by viewModel.gameState.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val defeatReason by viewModel.defeatReason.collectAsState()
    val selectedDurationSeconds by viewModel.selectedDurationSeconds.collectAsState()

    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadBytesInfo by viewModel.downloadBytesInfo.collectAsState()

    if (gameState == GameState.ModelDownloadRequired || gameState == GameState.ModelDownloading) {
        GemmaDownloadScreen(
            gameState = gameState,
            downloadProgress = downloadProgress,
            downloadBytesInfo = downloadBytesInfo,
            onStartDownload = { viewModel.startModelDownload() },
            modifier = modifier
        )
        return
    }

    // Camera Permissions State
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    // Trigger permission request on start
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // MediaPipe Setup
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    var landmarkerService by remember { mutableStateOf<PoseLandmarkerService?>(null) }

    LaunchedEffect(context) {
        landmarkerService = PoseLandmarkerService(context, object : PoseLandmarkerService.LandmarkerListener {
            override fun onError(error: String) {
                Log.e("CameraScreen", "MediaPipe Error: $error")
            }

            override fun onResults(result: com.example.tracker.PoseLandmarks, imageWidth: Int, imageHeight: Int) {
                // Pass landmarks to view model
                viewModel.processMediaPipeResults(result, System.currentTimeMillis())
            }
        })
    }

    // Clean up
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            landmarkerService?.close()
        }
    }

    // Main layout
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 2. Camera feed viewport with overlay graphics
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black)
                .testTag("camera_preview_container")
        ) {
            if (hasCameraPermission) {
                // Initialize CameraX Process
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().apply {
                                surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                try {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val bitmap = imageProxy.toBitmap()
                                    // Make sure it is rotated corrected if required
                                    val finalBitmap = if (rotation != 0) {
                                        rotateBitmap(bitmap, rotation)
                                    } else {
                                        bitmap
                                    }

                                    // Pipe to ViewModel current frame bitmap
                                    viewModel.setLatestBitmap(finalBitmap)

                                    // Run landmarker detection
                                    landmarkerService?.detectLiveStreamFrame(finalBitmap, System.currentTimeMillis())
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Frame analysis failed", e)
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "CameraX binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            }

        }

        // 3. Bottom Controls HUD
        BottomHUDEngine(
            gameState = gameState,
            statusMessage = statusMessage,
            defeatReason = defeatReason,
            timerSeconds = timerSeconds,
            selectedDurationSeconds = selectedDurationSeconds,
            onDurationChanged = { viewModel.updateSelectedDurationMinutes(it) },
            onStart = { viewModel.startSession() },
            onStop = { viewModel.stopSession() }
        )
    }
}


@Composable
fun DurationPicker(selectedMinutes: Int, onMinutesChanged: (Int) -> Unit) {
    OutlinedTextField(
        value = selectedMinutes.toString(),
        onValueChange = { onMinutesChanged(it.toIntOrNull() ?: 3) },
        label = { Text("Минуты (мин. 3)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun BottomHUDEngine(
    gameState: GameState,
    statusMessage: String,
    defeatReason: String,
    timerSeconds: Int,
    selectedDurationSeconds: Int,
    onDurationChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp)
        ) {
            // Status/Instruction Box
            Column(modifier = Modifier.fillMaxWidth()) {
                val stateHeadline = when (gameState) {
                    GameState.ModelDownloadRequired -> "НУЖНА ЗАГРУЗКА"
                    GameState.ModelDownloading -> "СКАЧИВАНИЕ МОДЕЛИ..."
                    GameState.Idle -> "ЖДЁМ СТАРТА"
                    GameState.CheckingStartPose -> "АНАЛИЗИРУЕМ С Gemma VLM..."
                    GameState.HoldingPose -> "СТАБИЛИЗАЦИЯ ТЕЛА"
                    GameState.CheckingControlPose -> "ПРОМЕЖУТОЧНАЯ Gemma ПРОВЕРКА..."
                    GameState.CheckingFinalPose -> "ФИНАЛЬНАЯ ПРОВЕРКА Gemma..."
                    GameState.Success -> "ПОЗДРАВЛЯЕМ! ПОБЕДА"
                    GameState.Failed -> "ПОВАЛЕНО"
                }
                val headlineColor = when (gameState) {
                    GameState.Success -> AccentGreen
                    GameState.Failed -> AccentRed
                    else -> DarkPrimary
                }

                Text(
                    text = stateHeadline,
                    color = headlineColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusMessage,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 24.sp
                )

                if (defeatReason.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Причина: $defeatReason",
                        color = AccentRed.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success) {
                DurationPicker(selectedDurationSeconds / 60, onDurationChanged)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Timer & Action Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Timer styled box
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(80.dp)
                        .background(DarkBg, RoundedCornerShape(24.dp))
                        .border(1.dp, DarkSecondary, RoundedCornerShape(24.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "REMAINING",
                        color = DarkTertiary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = String.format("%02d:%02d", timerSeconds / 60, timerSeconds % 60),
                        color = Color.White,
                        fontSize = 30.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("timer_display")
                    )
                }

                // Action Button
                if (gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .height(80.dp)
                            .weight(1.2f)
                            .testTag("start_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkPrimary),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start",
                            tint = DarkOnPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "START",
                            color = DarkOnPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .height(80.dp)
                            .weight(1.2f)
                            .testTag("stop_on_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color.White, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "STOP",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

        }
    }
}


private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    val matrix = android.graphics.Matrix()
    matrix.postRotate(rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

@Composable
fun GemmaDownloadScreen(
    gameState: GameState,
    downloadProgress: Float,
    downloadBytesInfo: String,
    onStartDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Info icon inside circle
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(DarkPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Download Info",
                        tint = DarkPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Gemma-4-E4B-it",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Для локальной проверки осанки и полной конфиденциальности требуется установить веса модели Gemma-4-E4B-it (LiteRT-LM) размером ~3.66 ГБ. Приложение работает без интернета, кадры не отправляются на сервер.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Network Warning panel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AccentRed.copy(alpha = 0.1f))
                        .border(1.dp, AccentRed.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = DarkPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Внимание: Размер модели ~3.66 ГБ. Для скачивания настоятельно рекомендуется Wi-Fi соединение во избежание расходов мобильного трафика.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Progress Bar or Download Button
                if (gameState == GameState.ModelDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = DarkPrimary,
                            trackColor = DarkSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = downloadBytesInfo,
                            color = DarkPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    androidx.compose.material3.Button(
                        onClick = onStartDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("download_model_button"),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = DarkPrimary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "СКАЧАТЬ МОДЕЛЬ",
                            color = DarkOnPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
