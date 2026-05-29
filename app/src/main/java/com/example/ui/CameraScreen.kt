package com.example.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.os.SystemClock
import android.provider.MediaStore
import android.graphics.Paint
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.annotation.StringRes
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.tracker.PoseLandmarkerService
import com.example.tracker.FaceDetectionStatus
import com.example.ui.theme.*
import com.example.viewmodel.FaceCheckMode
import com.example.viewmodel.AppLanguage
import com.example.viewmodel.GameSettings
import com.example.viewmodel.GameState
import com.example.viewmodel.GameViewModel
import com.example.viewmodel.MovementGaugeState
import com.example.viewmodel.PoseOverlayState
import com.example.video.TimelapseRecorder
import com.example.R
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import android.util.Size
import android.widget.Toast
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

private const val SHOW_POSE_DEBUG_OVERLAY = true
private const val SHOW_POSE_DEBUG_POINTS = true
private val POSE_CONNECTIONS = listOf(
    11 to 12,
    11 to 13,
    13 to 15,
    15 to 17,
    15 to 19,
    15 to 21,
    17 to 19,
    12 to 14,
    14 to 16,
    16 to 18,
    16 to 20,
    16 to 22,
    18 to 20,
    11 to 23,
    12 to 24,
    23 to 24,
    23 to 25,
    25 to 27,
    27 to 29,
    27 to 31,
    29 to 31,
    24 to 26,
    26 to 28,
    28 to 30,
    28 to 32,
    30 to 32
)


@Composable
internal fun localizedString(language: AppLanguage, @StringRes id: Int): String {
    val context = LocalContext.current
    val locale = if (language == AppLanguage.Russian) Locale("ru", "RU") else Locale.US
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config).resources.getString(id)
}

@Composable
fun CameraScreen(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    val gameState by viewModel.gameState.collectAsState()
    val gameSettings by viewModel.gameSettings.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val defeatReason by viewModel.defeatReason.collectAsState()
    val selectedDurationSeconds by viewModel.selectedDurationSeconds.collectAsState()
    val startDelayRemainingSeconds by viewModel.startDelayRemainingSeconds.collectAsState()
    val poseOverlayState by viewModel.poseOverlayState.collectAsState()
    val movementGaugeState by viewModel.movementGaugeState.collectAsState()
    VoiceAnnouncer(viewModel = viewModel, language = gameSettings.language)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val canOpenSettings = gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success
    val currentGameState = rememberUpdatedState(gameState)
    val currentTimelapseRecordingEnabled = rememberUpdatedState(gameSettings.timelapseRecordingEnabled)
    val timelapseRecorder = remember(context) { TimelapseRecorder(context.applicationContext) }
    var pendingTimelapseFile by remember { mutableStateOf<File?>(null) }
    val timelapseSaveErrorText = localizedString(gameSettings.language, R.string.timelapse_save_error)
    val saveTimelapseTitleText = localizedString(gameSettings.language, R.string.save_timelapse_title)
    val saveTimelapseMessageText = localizedString(gameSettings.language, R.string.save_timelapse_message)
    val saveText = localizedString(gameSettings.language, R.string.save)
    val dontSaveText = localizedString(gameSettings.language, R.string.dont_save)

    val keepScreenOn = gameState == GameState.WaitingForStabilization ||
        gameState == GameState.StartingDelay ||
        gameState == GameState.HoldingPose

    DisposableEffect(keepScreenOn, view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = previous
        }
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
    val lastPoseTimestampMs = remember { AtomicLong(0L) }
    var imageAnalysisRef by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var landmarkerService by remember { mutableStateOf<PoseLandmarkerService?>(null) }
    var isDemoMode by rememberSaveable { mutableStateOf(false) }
    var demoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val demoImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val decodedBitmap = decodeBitmapFromUri(context, uri)
        if (decodedBitmap != null) {
            val resizedBitmap = resizeBitmapLongSide(decodedBitmap, 1280)
            if (resizedBitmap !== decodedBitmap) {
                decodedBitmap.recycleIfNeeded()
            }
            demoBitmap = resizedBitmap
            isDemoMode = true
        }
    }

    LaunchedEffect(context) {
        landmarkerService = PoseLandmarkerService(context, object : PoseLandmarkerService.LandmarkerListener {
            override fun onError(error: String) {
                Log.e("CameraScreen", "MediaPipe Error: $error")
            }

            override fun onResults(result: com.example.tracker.PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long) {
                viewModel.processMediaPipeResults(result, timestampMs, imageWidth, imageHeight)
            }
        })
    }

    // Clean up
    DisposableEffect(Unit) {
        onDispose {
            imageAnalysisRef?.clearAnalyzer()
            imageAnalysisRef = null
            runCatching { cameraProviderRef?.unbindAll() }
                .onFailure { Log.w("CameraScreen", "Failed to unbind camera on dispose", it) }
            cameraProviderRef = null
            landmarkerService?.close()
            landmarkerService = null
            viewModel.clearCameraFrameCache(recycle = true)
            cameraExecutor.shutdown()
            pendingTimelapseFile?.delete()
            pendingTimelapseFile = null
            timelapseRecorder.release()
        }
    }


    LaunchedEffect(canOpenSettings) {
        if (!canOpenSettings && showSettings) {
            showSettings = false
        }
    }

    LaunchedEffect(isDemoMode, demoBitmap, landmarkerService) {
        val bitmap = demoBitmap
        if (!isDemoMode || bitmap == null) return@LaunchedEffect
        while (isActive && isDemoMode && demoBitmap === bitmap) {
            try {
                cameraExecutor.execute {
                    val timestampMs = nextFrameTimestampMs(
                        lastPoseTimestampMs,
                        SystemClock.elapsedRealtimeNanos() / 1_000_000L
                    )
                    val frameBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    submitFrameToPosePipeline(
                        bitmap = frameBitmap,
                        viewModel = viewModel,
                        landmarkerService = landmarkerService,
                        timestampMs = timestampMs
                    )
                }
            } catch (e: RejectedExecutionException) {
                Log.w("CameraScreen", "Dropping demo frame after camera executor shutdown", e)
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(gameState, gameSettings.timelapseRecordingEnabled) {
        if (!gameSettings.timelapseRecordingEnabled) {
            timelapseRecorder.discard()
            pendingTimelapseFile?.delete()
            pendingTimelapseFile = null
            return@LaunchedEffect
        }

        when (gameState) {
            GameState.StartingDelay -> timelapseRecorder.start(SystemClock.elapsedRealtime())
            GameState.HoldingPose -> timelapseRecorder.startTimer(SystemClock.elapsedRealtime())
            GameState.Success, GameState.Failed -> {
                val file = withContext(Dispatchers.IO) { timelapseRecorder.stop() }
                if (file != null && file.exists() && file.length() > 0L) {
                    pendingTimelapseFile?.delete()
                    pendingTimelapseFile = file
                }
            }
            GameState.Idle -> {
                timelapseRecorder.discard()
                pendingTimelapseFile?.delete()
                pendingTimelapseFile = null
            }
            else -> Unit
        }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(
            settings = gameSettings,
            onClose = { showSettings = false },
            onFaceModeChanged = viewModel::updateFaceCheckMode,
            onFaceConfidenceChanged = viewModel::updateFaceDetectionConfidence,
            onDriftChanged = viewModel::updateDriftThresholdFactor,
            onMotionChanged = viewModel::updateMotionThresholdFactor,
            onPenaltyIntervalChanged = viewModel::updateMinimumPenaltyIntervalSeconds,
            onMaxViolationsChanged = viewModel::updateMaxViolations,
            onPenaltiesEnabledChanged = viewModel::updatePenaltiesEnabled,
            onFirstViolationPenaltyChanged = viewModel::updateFirstViolationPenaltyMinutes,
            onSecondViolationPenaltyChanged = viewModel::updateSecondViolationPenaltyMinutes,
            onThirdViolationPenaltyChanged = viewModel::updateThirdViolationPenaltyMinutes,
            onSubsequentViolationPenaltyChanged = viewModel::updateSubsequentViolationPenaltyMinutes,
            onLanguageChanged = viewModel::updateLanguage,
            onTimelapseRecordingEnabledChanged = viewModel::updateTimelapseRecordingEnabled
        )
        return
    }

    // Main layout
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // 2. Camera feed viewport with overlay graphics
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black)
                .testTag("camera_preview_container")
        ) {
            when {
                isDemoMode && demoBitmap != null -> {
                    Image(
                        bitmap = demoBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                hasCameraPermission -> {
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
                            cameraProviderRef = cameraProvider
                            val preview = Preview.Builder().build().apply {
                                surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()
                                )
                                .build()
                            imageAnalysisRef = imageAnalysis

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                var pipelineBitmap: Bitmap? = null
                                var submittedToPipeline = false
                                try {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    var currentBitmap = imageProxy.toBitmap()
                                    if (rotation != 0) {
                                        val rotatedBitmap = rotateBitmap(currentBitmap, rotation)
                                        if (rotatedBitmap !== currentBitmap) {
                                            currentBitmap.recycleIfNeeded()
                                        }
                                        currentBitmap = rotatedBitmap
                                    }

                                    val resizedBitmap = resizeBitmapLongSide(currentBitmap, 1280)
                                    if (resizedBitmap !== currentBitmap) {
                                        currentBitmap.recycleIfNeeded()
                                    }
                                    pipelineBitmap = resizedBitmap

                                    val timestampMs = nextFrameTimestampMs(
                                        lastPoseTimestampMs,
                                        SystemClock.elapsedRealtimeNanos() / 1_000_000L
                                    )
                                    Log.d("CameraScreen", "analysisBitmap=${pipelineBitmap.width}x${pipelineBitmap.height}")
                                    val state = currentGameState.value
                                    if (currentTimelapseRecordingEnabled.value &&
                                        (state == GameState.StartingDelay || state == GameState.HoldingPose)
                                    ) {
                                        timelapseRecorder.offerFrame(
                                            bitmap = pipelineBitmap,
                                            timestampMs = timestampMs
                                        )
                                    }
                                    submitFrameToPosePipeline(
                                        bitmap = pipelineBitmap,
                                        viewModel = viewModel,
                                        landmarkerService = landmarkerService,
                                        timestampMs = timestampMs
                                    )
                                    submittedToPipeline = true
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Frame analysis failed", e)
                                } finally {
                                    if (!submittedToPipeline) {
                                        pipelineBitmap?.recycleIfNeeded()
                                    }
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
                    },
                    onRelease = { previewView ->
                        imageAnalysisRef?.clearAnalyzer()
                        imageAnalysisRef = null
                        runCatching {
                            val provider = cameraProviderRef
                                ?: ProcessCameraProvider.getInstance(previewView.context).get()
                            provider.unbindAll()
                        }.onFailure {
                            Log.w("CameraScreen", "Failed to unbind camera on release", it)
                        }
                        cameraProviderRef = null
                    }
                )
                }
            }

            FilledTonalButton(
                onClick = {
                    if (isDemoMode) {
                        isDemoMode = false
                    } else {
                        demoImagePickerLauncher.launch("image/*")
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .zIndex(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(if (isDemoMode) localizedString(gameSettings.language, R.string.camera) else localizedString(gameSettings.language, R.string.demo))
            }


            FilledTonalIconButton(
                onClick = { if (canOpenSettings) showSettings = true },
                enabled = canOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .zIndex(1f)
            ) {
                Icon(Icons.Default.Settings, contentDescription = localizedString(gameSettings.language, R.string.settings))
            }

            if (SHOW_POSE_DEBUG_OVERLAY) {
                PoseDebugOverlay(
                    overlayState = poseOverlayState,
                    modifier = Modifier.matchParentSize()
                )
            }

            if (movementGaugeState.active) {
                MovementGaugeOverlay(
                    state = movementGaugeState,
                    language = gameSettings.language,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .zIndex(1f)
                )
            }
        }

        if (pendingTimelapseFile != null) {
            AlertDialog(
                onDismissRequest = {
                    pendingTimelapseFile?.delete()
                    pendingTimelapseFile = null
                },
                title = { Text(saveTimelapseTitleText) },
                text = { Text(saveTimelapseMessageText) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val file = pendingTimelapseFile
                            if (file != null) {
                                val saved = saveTimelapseToMediaStore(context, file)
                                if (!saved) {
                                    Toast.makeText(
                                        context,
                                        timelapseSaveErrorText,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                file.delete()
                                pendingTimelapseFile = null
                            }
                        }
                    ) { Text(saveText) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            pendingTimelapseFile?.delete()
                            pendingTimelapseFile = null
                        }
                    ) { Text(dontSaveText) }
                }
            )
        }

        // 3. Bottom Controls HUD
        BottomHUDEngine(
            language = gameSettings.language,
            gameState = gameState,
            statusMessage = statusMessage,
            defeatReason = defeatReason,
            timerSeconds = timerSeconds,
            selectedDurationSeconds = selectedDurationSeconds,
            startDelayRemainingSeconds = startDelayRemainingSeconds,
            onDurationChanged = { viewModel.updateSelectedDurationMinutes(it) },
            onStart = { viewModel.startSession() },
            onStop = { viewModel.stopSession() }
        )
    }
}

@Composable
private fun MovementGaugeOverlay(
    state: MovementGaugeState,
    language: AppLanguage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.46f), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MovementGaugeRow(
            label = localizedString(language, R.string.movement_gauge_drift),
            value = state.driftNormalizedScore,
            threshold = state.driftThresholdFactor,
            color = DarkPrimary
        )
        MovementGaugeRow(
            label = localizedString(language, R.string.movement_gauge_motion),
            value = state.motionNormalizedScore,
            threshold = state.motionThresholdFactor,
            color = AccentGreen
        )
    }
}

@Composable
private fun MovementGaugeRow(
    label: String,
    value: Float,
    threshold: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val safeThreshold = max(threshold, 0.001f)
    val progress = (value / safeThreshold).coerceIn(0f, 1f)
    val isOverLimit = value > safeThreshold
    val gaugeColor = if (isOverLimit) AccentRed else color

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(68.dp)
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(100.dp)),
            color = gaugeColor,
            trackColor = Color.White.copy(alpha = 0.18f)
        )
        Text(
            text = String.format(Locale.US, "%.3f / %.3f", value, threshold),
            color = gaugeColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(104.dp)
        )
    }
}

@Composable
private fun PoseDebugOverlay(
    overlayState: PoseOverlayState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawPoseDebugOverlay(overlayState)
    }
}

private fun DrawScope.drawPoseDebugOverlay(overlayState: PoseOverlayState) {
    val imageWidth = overlayState.imageWidth
    val imageHeight = overlayState.imageHeight
    if (imageWidth <= 0 || imageHeight <= 0) return

    val canvasWidth = size.width
    val canvasHeight = size.height
    val scale = maxOf(
        canvasWidth / imageWidth.toFloat(),
        canvasHeight / imageHeight.toFloat()
    )
    val offsetX = (canvasWidth - imageWidth * scale) / 2f
    val offsetY = (canvasHeight - imageHeight * scale) / 2f

    fun mapX(normalizedX: Float): Float =
        offsetX + normalizedX.coerceIn(0f, 1f) * imageWidth * scale

    fun mapY(normalizedY: Float): Float =
        offsetY + normalizedY.coerceIn(0f, 1f) * imageHeight * scale

    val rect = overlayState.cropRect
    if (rect != null) {
        val left = mapX(rect.left)
        val top = mapY(rect.top)
        val right = mapX(rect.right)
        val bottom = mapY(rect.bottom)
        if (right > left && bottom > top) {
            drawRect(
                color = Color.Yellow.copy(alpha = 0.95f),
                topLeft = Offset(left, top),
                size = ComposeSize(right - left, bottom - top),
                style = Stroke(width = 4f)
            )
        }
    }

    val face = overlayState.face
    val detectorInputRect = face.detectorInputRect
    if (detectorInputRect != null) {
        val left = mapX(detectorInputRect.left)
        val top = mapY(detectorInputRect.top)
        val right = mapX(detectorInputRect.right)
        val bottom = mapY(detectorInputRect.bottom)
        if (right > left && bottom > top) {
            drawRect(
                color = Color.Green.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = ComposeSize(right - left, bottom - top),
                style = Stroke(width = 3f)
            )
        }
    }

    val faceRect = face.faceRect
    if (faceRect != null) {
        val left = mapX(faceRect.left)
        val top = mapY(faceRect.top)
        val right = mapX(faceRect.right)
        val bottom = mapY(faceRect.bottom)
        if (right > left && bottom > top) {
            drawRect(
                color = Color.Magenta.copy(alpha = 0.95f),
                topLeft = Offset(left, top),
                size = ComposeSize(right - left, bottom - top),
                style = Stroke(width = 4f)
            )
        }
    }

    face.keypoints.forEach { point ->
        if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
        drawCircle(
            color = Color.Red.copy(alpha = 0.95f),
            radius = 5f,
            center = Offset(mapX(point.x), mapY(point.y))
        )
    }

    val debugText = face.debugMessage.ifEmpty { "face=${face.status}" }
    val debugColor = when (face.status) {
        FaceDetectionStatus.FaceVisible -> android.graphics.Color.GREEN
        FaceDetectionStatus.FaceNotVisible -> android.graphics.Color.YELLOW
        FaceDetectionStatus.Error -> android.graphics.Color.RED
        FaceDetectionStatus.NotProcessed -> android.graphics.Color.LTGRAY
    }
    val debugPaint = Paint().apply {
        color = debugColor
        textSize = 30f
        isAntiAlias = true
    }
    val maxTextWidth = (size.width - 48f).coerceAtLeast(1f)
    var lineY = 40f
    var textStart = 0
    while (textStart < debugText.length) {
        val count = debugPaint.breakText(debugText, textStart, debugText.length, true, maxTextWidth, null)
        if (count <= 0) break
        val lineEnd = textStart + count
        drawContext.canvas.nativeCanvas.drawText(
            debugText,
            textStart,
            lineEnd,
            24f,
            lineY,
            debugPaint
        )
        textStart = lineEnd
        while (textStart < debugText.length && debugText[textStart].isWhitespace()) {
            textStart += 1
        }
        lineY += debugPaint.fontSpacing
    }

    if (!SHOW_POSE_DEBUG_POINTS) return

    POSE_CONNECTIONS.forEach { (startIndex, endIndex) ->
        val start = overlayState.landmarks.getOrNull(startIndex) ?: return@forEach
        val end = overlayState.landmarks.getOrNull(endIndex) ?: return@forEach
        if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) return@forEach

        drawLine(
            color = Color.Cyan.copy(alpha = 0.65f),
            start = Offset(mapX(start.x), mapY(start.y)),
            end = Offset(mapX(end.x), mapY(end.y)),
            strokeWidth = 2.5f
        )
    }

    overlayState.landmarks.forEach { point ->
        if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
        drawCircle(
            color = Color.Cyan.copy(alpha = 0.85f),
            radius = 4f,
            center = Offset(mapX(point.x), mapY(point.y))
        )
    }
}

@Composable
private fun VoiceAnnouncer(viewModel: GameViewModel, language: AppLanguage) {
    val context = LocalContext.current
    val pendingMessages = remember { ConcurrentLinkedQueue<String>() }
    val ttsRef = remember { AtomicReference<TextToSpeech?>(null) }
    val isReady = remember { AtomicBoolean(false) }

    DisposableEffect(context, language) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = ttsRef.get() ?: return@TextToSpeech
                val locale = if (language == AppLanguage.Russian) Locale("ru", "RU") else Locale.US
                val result = tts.setLanguage(locale)
                val ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                isReady.set(ready)

                if (ready) {
                    while (true) {
                        val message = pendingMessages.poll() ?: break
                        tts.speak(
                            message,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "voice_${System.currentTimeMillis()}"
                        )
                    }
                }
            }
        }
        ttsRef.set(engine)

        onDispose {
            engine.stop()
            engine.shutdown()
            ttsRef.set(null)
            isReady.set(false)
            pendingMessages.clear()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.voiceEvents.collect { message ->
            val engine = ttsRef.get()
            if (isReady.get() && engine != null) {
                engine.speak(
                    message,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "voice_${System.currentTimeMillis()}"
                )
            } else {
                pendingMessages.add(message)
            }
        }

    }
}



@Composable
fun DurationPicker(selectedMinutes: Int, language: AppLanguage, onMinutesChanged: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = localizedString(language, R.string.duration_minutes),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(DarkBg)
                .border(1.dp, DarkSecondary, RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onMinutesChanged((selectedMinutes - 1).coerceAtLeast(3)) }) {
                Text("−", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$selectedMinutes ${localizedString(language, R.string.min_short)}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { onMinutesChanged((selectedMinutes + 1).coerceAtMost(120)) }) {
                Icon(Icons.Default.Add, contentDescription = localizedString(language, R.string.increase), tint = Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun BottomHUDEngine(
    language: AppLanguage,
    gameState: GameState,
    statusMessage: String,
    defeatReason: String,
    timerSeconds: Int,
    selectedDurationSeconds: Int,
    startDelayRemainingSeconds: Int,
    onDurationChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val canStart = gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success

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
                    GameState.Idle -> localizedString(language, R.string.waiting_to_start)
                    GameState.WaitingForStabilization -> localizedString(language, R.string.device_stabilization)
                    GameState.StartingDelay -> "${localizedString(language, R.string.start_in)} ${startDelayRemainingSeconds}s"
                    GameState.HoldingPose -> localizedString(language, R.string.holding_pose)
                    GameState.Success -> localizedString(language, R.string.congrats_victory)
                    GameState.Failed -> localizedString(language, R.string.failed)
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
                        text = "${localizedString(language, R.string.reason)}: $defeatReason",
                        color = AccentRed.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            if (canStart) {
                DurationPicker(selectedDurationSeconds / 60, language, onDurationChanged)
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
                        text = localizedString(language, R.string.remaining),
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
                if (canStart) {
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
                            contentDescription = localizedString(language, R.string.start),
                            tint = DarkOnPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localizedString(language, R.string.start),
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
                            text = localizedString(language, R.string.stop),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

        }
        Spacer(Modifier.height(20.dp))
    }
}


private fun saveTimelapseToMediaStore(
    context: Context,
    sourceFile: File
): Boolean {
    val resolver = context.contentResolver
    val fileName = "pose_timelapse_${System.currentTimeMillis()}.mp4"

    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(
            MediaStore.Video.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/PoseValidator"
        )
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val collection = MediaStore.Video.Media.getContentUri(
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    )

    val uri = runCatching {
        resolver.insert(collection, values)
    }.getOrElse {
        Log.e("CameraScreen", "Failed to create timelapse MediaStore entry", it)
        null
    } ?: return false

    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Failed to open output stream for timelapse video")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        true
    }.getOrElse {
        Log.e("CameraScreen", "Failed to save timelapse to MediaStore", it)
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    val matrix = android.graphics.Matrix()
    matrix.postRotate(rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun resizeBitmapLongSide(bitmap: Bitmap, maxLongSide: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val longSide = maxOf(width, height)
    if (longSide <= maxLongSide) return bitmap

    val scale = maxLongSide.toFloat() / longSide.toFloat()
    val newWidth = (width * scale).toInt().coerceAtLeast(1)
    val newHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

private fun nextFrameTimestampMs(lastTimestampMs: AtomicLong, candidateTimestampMs: Long): Long {
    while (true) {
        val previous = lastTimestampMs.get()
        val next = maxOf(candidateTimestampMs, previous + 1L)
        if (lastTimestampMs.compareAndSet(previous, next)) return next
    }
}

private fun submitFrameToPosePipeline(
    bitmap: Bitmap,
    viewModel: GameViewModel,
    landmarkerService: PoseLandmarkerService?,
    timestampMs: Long
) {
    viewModel.registerCameraFrame(bitmap, timestampMs)
    val accepted = landmarkerService?.detectLiveStreamFrame(bitmap, timestampMs) ?: false
    if (!accepted) {
        viewModel.dropCameraFrame(timestampMs, recycle = true)
    }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}

private fun decodeBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap? {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            normalizeSoftwareArgb8888(decoded)
        } else {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { normalizeSoftwareArgb8888(it) }
            }
        }
    }.getOrNull()
}

private fun normalizeSoftwareArgb8888(bitmap: Bitmap): Bitmap {
    val isHardwareBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        bitmap.config == Bitmap.Config.HARDWARE
    val requiresConfigConversion = bitmap.config != Bitmap.Config.ARGB_8888
    return if (isHardwareBitmap || requiresConfigConversion) {
        val normalized = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        if (normalized !== bitmap) {
            bitmap.recycleIfNeeded()
        }
        normalized
    } else {
        bitmap
    }
}
