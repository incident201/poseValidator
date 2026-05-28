package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.speech.tts.TextToSpeech
import android.os.SystemClock
import android.graphics.Paint
import android.util.Log
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.viewmodel.PoseOverlayState
import com.example.posevalidator.R
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import android.util.Size
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SHOW_POSE_DEBUG_OVERLAY = true
private const val SHOW_POSE_DEBUG_POINTS = true


@Composable
private fun localizedString(language: AppLanguage, @StringRes id: Int): String {
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
    VoiceAnnouncer(viewModel = viewModel, language = gameSettings.language)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val canOpenSettings = gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success

    val keepScreenOn = gameState == GameState.StartingDelay ||
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
    var landmarkerService by remember { mutableStateOf<PoseLandmarkerService?>(null) }
    var isDemoMode by rememberSaveable { mutableStateOf(false) }
    var demoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val demoImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val decodedBitmap = decodeBitmapFromUri(context, uri)
        if (decodedBitmap != null) {
            demoBitmap = resizeBitmapLongSide(decodedBitmap, 1280)
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
            cameraExecutor.shutdown()
            landmarkerService?.close()
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
            cameraExecutor.execute {
                val timestampMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
                submitFrameToPosePipeline(
                    bitmap = bitmap,
                    viewModel = viewModel,
                    landmarkerService = landmarkerService,
                    timestampMs = timestampMs
                )
            }
            delay(250L)
        }
    }

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
            onLanguageChanged = viewModel::updateLanguage
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

                                    val timestampMs = SystemClock.elapsedRealtimeNanos() / 1_000_000L
                                    val analysisBitmap = resizeBitmapLongSide(finalBitmap, 1280)
                                    Log.d("CameraScreen", "analysisBitmap=${analysisBitmap.width}x${analysisBitmap.height}")
                                    submitFrameToPosePipeline(
                                        bitmap = analysisBitmap,
                                        viewModel = viewModel,
                                        landmarkerService = landmarkerService,
                                        timestampMs = timestampMs
                                    )
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
                    },
                    onRelease = { previewView ->
                        runCatching {
                            val cameraProvider = ProcessCameraProvider.getInstance(previewView.context).get()
                            cameraProvider.unbindAll()
                        }.onFailure {
                            Log.w("CameraScreen", "Failed to unbind camera on release", it)
                        }
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
private fun SettingsScreen(
    settings: GameSettings,
    onClose: () -> Unit,
    onFaceModeChanged: (FaceCheckMode) -> Unit,
    onFaceConfidenceChanged: (Float) -> Unit,
    onDriftChanged: (Float) -> Unit,
    onMotionChanged: (Float) -> Unit,
    onPenaltyIntervalChanged: (Int) -> Unit,
    onMaxViolationsChanged: (Int) -> Unit,
    onPenaltiesEnabledChanged: (Boolean) -> Unit,
    onFirstViolationPenaltyChanged: (Int) -> Unit,
    onSecondViolationPenaltyChanged: (Int) -> Unit,
    onThirdViolationPenaltyChanged: (Int) -> Unit,
    onSubsequentViolationPenaltyChanged: (Int) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit
 ) {
    var faceConfidenceSlider by remember(settings.faceDetectionConfidence) {
        mutableFloatStateOf(settings.faceDetectionConfidence)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(localizedString(settings.language, R.string.back)) }
            Text(localizedString(settings.language, R.string.settings), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.face_detection), color = Color.White, fontWeight = FontWeight.SemiBold)
                listOf(
                    localizedString(settings.language, R.string.face_away) to FaceCheckMode.FaceAwayFromCamera,
                    localizedString(settings.language, R.string.face_to_camera) to FaceCheckMode.FaceToCamera,
                    localizedString(settings.language, R.string.do_not_check) to FaceCheckMode.Disabled
                ).forEach { (label, mode) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = settings.faceCheckMode == mode, onClick = { onFaceModeChanged(mode) })
                        Text(label, color = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text("${localizedString(settings.language, R.string.face_detection_threshold)}: ${"%.2f".format(faceConfidenceSlider)}", color = Color.White)
                Slider(
                    value = faceConfidenceSlider,
                    onValueChange = { faceConfidenceSlider = it.coerceIn(0.5f, 0.95f) },
                    onValueChangeFinished = { onFaceConfidenceChanged(faceConfidenceSlider) },
                    valueRange = 0.5f..0.95f
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.maximum_violations), color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                IntegerSettingField(
                    label = localizedString(settings.language, R.string.range_0_9999),
                    value = settings.maxViolations,
                    onValueChanged = onMaxViolationsChanged,
                    min = 0,
                    max = 9999
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.min_interval_errors), color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                IntegerSettingField(
                    label = localizedString(settings.language, R.string.seconds_0_30),
                    value = settings.minimumPenaltyIntervalSeconds,
                    onValueChanged = onPenaltyIntervalChanged,
                    min = 0,
                    max = 30
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.penalty_for_violation), color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = settings.penaltiesEnabled, onCheckedChange = onPenaltiesEnabledChanged)
                    Spacer(Modifier.width(8.dp))
                    Text(if (settings.penaltiesEnabled) localizedString(settings.language, R.string.on) else localizedString(settings.language, R.string.off), color = Color.White)
                }
                IntegerSettingField(localizedString(settings.language, R.string.first_violation_min), settings.firstViolationPenaltyMinutes, onFirstViolationPenaltyChanged, 0, 9999)
                IntegerSettingField(localizedString(settings.language, R.string.second_violation_min), settings.secondViolationPenaltyMinutes, onSecondViolationPenaltyChanged, 0, 9999)
                IntegerSettingField(localizedString(settings.language, R.string.third_violation_min), settings.thirdViolationPenaltyMinutes, onThirdViolationPenaltyChanged, 0, 9999)
                IntegerSettingField(localizedString(settings.language, R.string.subsequent_violation_min), settings.subsequentViolationPenaltyMinutes, onSubsequentViolationPenaltyChanged, 0, 9999)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.motion_reaction), color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("${localizedString(settings.language, R.string.drift_threshold)}: ${"%.2f".format(settings.driftThresholdFactor)}", color = Color.White)
                Slider(value = settings.driftThresholdFactor, onValueChange = onDriftChanged, valueRange = 0.1f..0.8f)
                Text("${localizedString(settings.language, R.string.abrupt_motion_threshold)}: ${"%.2f".format(settings.motionThresholdFactor)}", color = Color.White)
                Slider(value = settings.motionThresholdFactor, onValueChange = onMotionChanged, valueRange = 0.1f..0.8f)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.language), color = Color.White, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.language == AppLanguage.English, onClick = { onLanguageChanged(AppLanguage.English) })
                    Text(localizedString(settings.language, R.string.language_english), color = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.language == AppLanguage.Russian, onClick = { onLanguageChanged(AppLanguage.Russian) })
                    Text(localizedString(settings.language, R.string.language_russian), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    min: Int,
    max: Int
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }
            text = digits
            val parsed = digits.toIntOrNull() ?: min
            onValueChanged(parsed.coerceIn(min, max))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
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

private fun submitFrameToPosePipeline(
    bitmap: Bitmap,
    viewModel: GameViewModel,
    landmarkerService: PoseLandmarkerService?,
    timestampMs: Long
) {
    viewModel.registerCameraFrame(bitmap, timestampMs)
    landmarkerService?.detectLiveStreamFrame(bitmap, timestampMs)
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
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    } else {
        bitmap
    }
}
