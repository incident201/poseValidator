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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.annotation.StringRes
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.tracker.Point3D
import com.example.tracker.PoseLandmarkerDelegateMode
import com.example.tracker.PoseLandmarkerService
import com.example.viewmodel.AppLanguage
import com.example.viewmodel.GameState
import com.example.viewmodel.GameViewModel
import com.example.viewmodel.MovementGaugeState
import com.example.viewmodel.PoseOverlayState
import com.example.viewmodel.RuleViolationCounts
import com.example.viewmodel.SessionSummary
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max

private const val SHOW_POSE_DEBUG_OVERLAY = true
private const val SHOW_POSE_DEBUG_POINTS = true

private enum class TimelapseUiState { Preparing, Ready, Saving, Saved, Unavailable, Disabled }

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
    val violationCount by viewModel.violationCount.collectAsState()
    val ruleViolationCounts by viewModel.ruleViolationCounts.collectAsState()
    val sessionSummary by viewModel.sessionSummary.collectAsState()
    VoiceAnnouncer(viewModel = viewModel, language = gameSettings.language)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val currentGameState = rememberUpdatedState(gameState)
    val currentTimelapseRecordingEnabled = rememberUpdatedState(gameSettings.timelapseRecordingEnabled)
    val currentViolationCount = rememberUpdatedState(violationCount)
    val coroutineScope = rememberCoroutineScope()
    val timelapseRecorder = remember(context) { TimelapseRecorder(context.applicationContext) }
    var pendingTimelapseFile by remember { mutableStateOf<File?>(null) }
    var timelapseUiState by remember { mutableStateOf(TimelapseUiState.Disabled) }
    val timelapseSaveErrorText = localizedString(gameSettings.language, R.string.timelapse_save_error)
    val timelapseSavedText = localizedString(gameSettings.language, R.string.final_timelapse_saved)
    val violationsCounterText = localizedString(gameSettings.language, R.string.violations_counter)
    val currentViolationsCounterText = rememberUpdatedState(violationsCounterText)
    val isFinalState = gameState == GameState.Success || gameState == GameState.Failed
    val showFinalScreen = isFinalState && sessionSummary != null
    val sessionTimelapseEnabled = sessionSummary?.settings?.timelapseRecordingEnabled
    val shouldRecordTimelapse = if (isFinalState) {
        sessionTimelapseEnabled ?: gameSettings.timelapseRecordingEnabled
    } else {
        gameSettings.timelapseRecordingEnabled
    }
    val isTimelapseSaving = timelapseUiState == TimelapseUiState.Saving
    val canOpenSettings = (gameState == GameState.Idle ||
        gameState == GameState.Failed ||
        gameState == GameState.Success) && !(isFinalState && isTimelapseSaving)
    val closeFinalScreen = {
        if (!isTimelapseSaving) {
            pendingTimelapseFile?.delete()
            pendingTimelapseFile = null
            timelapseUiState = TimelapseUiState.Disabled
            viewModel.dismissFinalScreen()
        }
    }
    val startFromFinalScreen = {
        if (isFinalState) {
            if (!isTimelapseSaving) {
                pendingTimelapseFile?.delete()
                pendingTimelapseFile = null
                timelapseUiState = TimelapseUiState.Disabled
                viewModel.startSession()
            }
        } else {
            viewModel.startSession()
        }
    }

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
    val imageAnalysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val isCameraScreenDisposed = remember { AtomicBoolean(false) }
    val mediaPipeThread = remember {
        HandlerThread("MediaPipePoseThread").apply { start() }
    }
    val mediaPipeHandler = remember(mediaPipeThread) {
        Handler(mediaPipeThread.looper)
    }
    val landmarkerInitFutureRef = remember {
        AtomicReference<Future<PoseLandmarkerService?>?>(null)
    }
    val lastPoseTimestampMs = remember { AtomicLong(0L) }
    var imageAnalysisRef by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var landmarkerService by remember { mutableStateOf<PoseLandmarkerService?>(null) }
    var poseDelegateMode by remember {
        mutableStateOf(PoseLandmarkerDelegateMode.Initializing)
    }
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
            val oldDemoBitmap = demoBitmap
            demoBitmap = resizedBitmap
            if (oldDemoBitmap != null && oldDemoBitmap !== resizedBitmap) {
                oldDemoBitmap.recycleIfNeeded()
            }
            isDemoMode = true
        }
    }

    LaunchedEffect(context, mediaPipeHandler) {
        poseDelegateMode = PoseLandmarkerDelegateMode.Initializing
        val initCancelled = AtomicBoolean(false)

        val future = submitToHandler(mediaPipeHandler) {
            val service = PoseLandmarkerService(context, object : PoseLandmarkerService.LandmarkerListener {
                override fun onError(error: String) {
                    Log.e("CameraScreen", "MediaPipe Error: $error")
                }

                override fun onDelegateModeChanged(mode: PoseLandmarkerDelegateMode) {
                    coroutineScope.launch {
                        poseDelegateMode = mode
                    }
                }

                override fun onResults(
                    result: com.example.tracker.PoseLandmarks,
                    imageWidth: Int,
                    imageHeight: Int,
                    timestampMs: Long
                ) {
                    viewModel.processMediaPipeResults(result, timestampMs, imageWidth, imageHeight)
                }
            })

            if (isCameraScreenDisposed.get() || initCancelled.get()) {
                service.close()
                null
            } else {
                service
            }
        }
        landmarkerInitFutureRef.set(future)

        var createdService: PoseLandmarkerService? = null

        try {
            createdService = withContext(Dispatchers.IO) {
                future.get(5, TimeUnit.SECONDS)
            }

            if (createdService != null && isActive && !isCameraScreenDisposed.get()) {
                landmarkerService = createdService
                createdService = null
            }
        } catch (t: Throwable) {
            Log.e("CameraScreen", "Failed to initialize PoseLandmarkerService", t)
            if (t is TimeoutException) {
                initCancelled.set(true)
                future.cancel(true)
                mediaPipeThread.quit()
            }
            if (isActive) {
                poseDelegateMode = PoseLandmarkerDelegateMode.Unavailable
                landmarkerService = null
            }
        } finally {
            val ownsFuture = landmarkerInitFutureRef.compareAndSet(future, null)
            val unusedService = createdService
            if (unusedService != null && ownsFuture) {
                runCatching {
                    submitToHandler(mediaPipeHandler) {
                        unusedService.close()
                    }.get(3, TimeUnit.SECONDS)
                }.onFailure {
                    Log.w("CameraScreen", "Failed to close unused PoseLandmarkerService", it)
                }
            }
        }
    }

    // Clean up
    DisposableEffect(Unit) {
        onDispose {
            isCameraScreenDisposed.set(true)
            imageAnalysisRef?.clearAnalyzer()
            imageAnalysisRef = null
            runCatching { cameraProviderRef?.unbindAll() }
                .onFailure { Log.w("CameraScreen", "Failed to unbind camera on dispose", it) }
            cameraProviderRef = null

            val initFuture = landmarkerInitFutureRef.getAndSet(null)
            var serviceToClose = landmarkerService
            landmarkerService = null
            var shutdownNow = false
            var quitMediaPipeImmediately = false

            if (serviceToClose == null && initFuture != null) {
                serviceToClose = runCatching {
                    initFuture.get(3, TimeUnit.SECONDS)
                }.onFailure {
                    Log.w("CameraScreen", "Failed to wait for PoseLandmarkerService initialization", it)
                    initFuture.cancel(true)
                    shutdownNow = true
                    quitMediaPipeImmediately = true
                }.getOrNull()
            }

            val finalServiceToClose = serviceToClose
            if (finalServiceToClose != null) {
                runCatching {
                    submitToHandler(mediaPipeHandler) {
                        finalServiceToClose.close()
                    }.get(3, TimeUnit.SECONDS)
                }.onFailure {
                    Log.w("CameraScreen", "Failed to close PoseLandmarkerService on MediaPipe thread", it)
                    shutdownNow = true
                    quitMediaPipeImmediately = true
                }
            }

            viewModel.clearCameraFrameCache(recycle = true)
            if (shutdownNow) {
                imageAnalysisExecutor.shutdownNow()
            } else {
                imageAnalysisExecutor.shutdown()
            }
            if (quitMediaPipeImmediately) {
                mediaPipeThread.quit()
            } else {
                mediaPipeThread.quitSafely()
            }
            demoBitmap?.recycleIfNeeded()
            demoBitmap = null
            pendingTimelapseFile?.delete()
            pendingTimelapseFile = null
            timelapseUiState = TimelapseUiState.Disabled
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
            val frameBitmap = try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (t: Throwable) {
                Log.e("CameraScreen", "Failed to copy demo frame", t)
                break
            }
            try {
                imageAnalysisExecutor.execute {
                    val timestampMs = nextFrameTimestampMs(
                        lastPoseTimestampMs,
                        SystemClock.elapsedRealtimeNanos() / 1_000_000L
                    )
                    submitFrameToPosePipeline(
                        bitmap = frameBitmap,
                        viewModel = viewModel,
                        landmarkerService = landmarkerService,
                        timestampMs = timestampMs,
                        mediaPipeHandler = mediaPipeHandler
                    )
                }
            } catch (e: RejectedExecutionException) {
                frameBitmap.recycleIfNeeded()
                Log.w("CameraScreen", "Dropping demo frame after image analysis executor shutdown", e)
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(gameState, shouldRecordTimelapse) {
        if (!shouldRecordTimelapse) {
            timelapseRecorder.discard()
            pendingTimelapseFile?.delete()
            pendingTimelapseFile = null
            timelapseUiState = TimelapseUiState.Disabled
            return@LaunchedEffect
        }

        when (gameState) {
            GameState.StartingDelay -> {
                pendingTimelapseFile?.delete()
                pendingTimelapseFile = null
                timelapseUiState = TimelapseUiState.Disabled
                timelapseRecorder.start(SystemClock.elapsedRealtime())
            }
            GameState.HoldingPose -> timelapseRecorder.startTimer(SystemClock.elapsedRealtime())
            GameState.Success, GameState.Failed -> {
                timelapseUiState = TimelapseUiState.Preparing
                val file = withContext(Dispatchers.IO) { timelapseRecorder.stop() }
                if (file != null && file.exists() && file.length() > 0L) {
                    pendingTimelapseFile?.delete()
                    pendingTimelapseFile = file
                    timelapseUiState = TimelapseUiState.Ready
                } else {
                    file?.delete()
                    pendingTimelapseFile?.delete()
                    pendingTimelapseFile = null
                    timelapseUiState = TimelapseUiState.Unavailable
                }
            }
            GameState.Idle -> {
                timelapseRecorder.discard()
                pendingTimelapseFile?.delete()
                pendingTimelapseFile = null
                timelapseUiState = TimelapseUiState.Disabled
            }
            else -> Unit
        }
    }

    BackHandler(enabled = !showSettings && showFinalScreen) {
        if (!isTimelapseSaving) {
            closeFinalScreen()
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(
            settings = gameSettings,
            onClose = { showSettings = false },
            onFaceModeChanged = viewModel::updateFaceCheckMode,
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
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 2. Camera feed viewport with overlay graphics
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black)
                .testTag("camera_preview_container")
        ) {
            when {
                showFinalScreen -> {
                    FinalSessionScreen(
                        summary = sessionSummary!!,
                        language = gameSettings.language,
                        pendingTimelapseFile = pendingTimelapseFile,
                        timelapseUiState = timelapseUiState,
                        onSaveTimelapse = {
                            val savingFile = pendingTimelapseFile
                            if (savingFile != null && timelapseUiState != TimelapseUiState.Saving) {
                                timelapseUiState = TimelapseUiState.Saving
                                coroutineScope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        saveTimelapseToMediaStore(context, savingFile)
                                    }
                                    if (pendingTimelapseFile != savingFile ||
                                        timelapseUiState != TimelapseUiState.Saving
                                    ) {
                                        return@launch
                                    }
                                    if (saved) {
                                        savingFile.delete()
                                        pendingTimelapseFile = null
                                        timelapseUiState = TimelapseUiState.Saved
                                        Toast.makeText(context, timelapseSavedText, Toast.LENGTH_SHORT).show()
                                    } else {
                                        timelapseUiState = TimelapseUiState.Ready
                                        Toast.makeText(context, timelapseSaveErrorText, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onClose = closeFinalScreen,
                        modifier = Modifier
                            .matchParentSize()
                            .zIndex(5f)
                    )
                }
                isDemoMode && demoBitmap != null -> {
                    Image(
                        bitmap = demoBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f),
                        contentScale = ContentScale.Crop
                    )
                }
                hasCameraPermission -> {
                // Initialize CameraX Process
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(0f),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

                            imageAnalysis.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
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
                                            timestampMs = timestampMs,
                                            violationsCount = currentViolationCount.value,
                                            violationsText = currentViolationsCounterText.value
                                        )
                                    }
                                    submitFrameToPosePipeline(
                                        bitmap = pipelineBitmap,
                                        viewModel = viewModel,
                                        landmarkerService = landmarkerService,
                                        timestampMs = timestampMs,
                                        mediaPipeHandler = mediaPipeHandler
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

            if (!showFinalScreen && SHOW_POSE_DEBUG_OVERLAY) {
                PoseDebugOverlay(
                    overlayState = poseOverlayState,
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(2f)
                )
            }

            if (!showFinalScreen) {
                PoseDelegateModeBadge(
                    mode = poseDelegateMode,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .zIndex(4f)
                )
            }

            if (!showFinalScreen && gameState == GameState.HoldingPose) {
                ViolationCountsOverlay(
                    counts = ruleViolationCounts,
                    language = gameSettings.language,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 10.dp)
                        .zIndex(3f)
                )
            }

            if (!showFinalScreen && movementGaugeState.active) {
                MovementGaugeOverlay(
                    state = movementGaugeState,
                    language = gameSettings.language,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .zIndex(3f)
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
            isDemoMode = isDemoMode,
            onDemoClick = {
                if (isDemoMode) {
                    isDemoMode = false
                } else {
                    demoImagePickerLauncher.launch("image/*")
                }
            },
            canOpenSettings = canOpenSettings,
            onSettingsClick = { if (canOpenSettings) showSettings = true },
            startEnabled = !(isFinalState && isTimelapseSaving),
            onStart = startFromFinalScreen,
            onStop = { viewModel.stopSession() }
        )
    }
}


@Composable
private fun PoseDelegateModeBadge(
    mode: PoseLandmarkerDelegateMode,
    modifier: Modifier = Modifier
) {
    val text = when (mode) {
        PoseLandmarkerDelegateMode.Initializing -> "Pose: Initializing"
        PoseLandmarkerDelegateMode.GPU -> "Pose: GPU"
        PoseLandmarkerDelegateMode.CPU -> "Pose: CPU"
        PoseLandmarkerDelegateMode.Unavailable -> "Pose: Unavailable"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.62f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FinalSessionScreen(
    summary: SessionSummary,
    language: AppLanguage,
    pendingTimelapseFile: File?,
    timelapseUiState: TimelapseUiState,
    onSaveTimelapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isSuccess = summary.result == GameState.Success
    val titleColor = if (isSuccess) colorScheme.tertiary else colorScheme.error
    val title = localizedString(
        language,
        if (isSuccess) R.string.final_completed else R.string.final_failed
    )
    val sensitivity = sensitivityPresetFor(summary.settings)

    Surface(
        modifier = modifier.fillMaxSize(),
        color = colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    if (summary.defeatReason.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = summary.defeatReason,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FinalMetricCard(
                            label = localizedString(language, R.string.final_initial_timer),
                            value = formatDuration(summary.initialTimerSeconds),
                            modifier = Modifier.weight(1f)
                        )
                        FinalMetricCard(
                            label = localizedString(language, R.string.final_actual_timer),
                            value = formatDuration(summary.actualTimerSeconds),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    FinalSectionTitle(localizedString(language, R.string.final_errors))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FinalCounterCard(
                            label = localizedString(language, R.string.movement_gauge_drift),
                            value = summary.violationCounts.drift,
                            modifier = Modifier.weight(1f)
                        )
                        FinalCounterCard(
                            label = localizedString(language, R.string.movement_gauge_motion),
                            value = summary.violationCounts.motion,
                            modifier = Modifier.weight(1f)
                        )
                        FinalCounterCard(
                            label = localizedString(language, R.string.violation_count_face),
                            value = summary.violationCounts.face,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    FinalSectionTitle(localizedString(language, R.string.final_session_settings))
                    Spacer(Modifier.height(8.dp))
                    FinalSettingRow(
                        label = localizedString(language, R.string.final_sensitivity),
                        value = localizedString(language, sensitivity.nameRes)
                    )

                    if (summary.settings.timelapseRecordingEnabled) {
                        Spacer(Modifier.height(20.dp))
                        when (timelapseUiState) {
                            TimelapseUiState.Ready -> {
                                Button(
                                    onClick = onSaveTimelapse,
                                    enabled = pendingTimelapseFile != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(
                                        text = localizedString(language, R.string.final_save_timelapse),
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            TimelapseUiState.Saving -> {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(
                                        text = localizedString(language, R.string.final_timelapse_saving),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            TimelapseUiState.Saved -> {
                                Text(
                                    text = localizedString(language, R.string.final_timelapse_saved),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.tertiary,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            TimelapseUiState.Unavailable -> {
                                Text(
                                    text = localizedString(language, R.string.final_timelapse_unavailable),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            }
                            TimelapseUiState.Preparing -> {
                                OutlinedButton(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(
                                        text = localizedString(language, R.string.final_timelapse_preparing),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            TimelapseUiState.Disabled -> Unit
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onClose,
                        enabled = timelapseUiState != TimelapseUiState.Saving,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = localizedString(language, R.string.final_close),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
        }
    }
}

@Composable
private fun FinalSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Start
    )
}

@Composable
private fun FinalMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = colorScheme.surface.copy(alpha = 0.82f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = value,
                color = colorScheme.onSurface,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FinalCounterCard(
    label: String,
    value: Int,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value.toString(),
                color = colorScheme.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = label,
                color = colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FinalSettingRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surface.copy(alpha = 0.82f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = value,
                color = colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.End
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val hours = safeSeconds / 3600
    val minutes = (safeSeconds % 3600) / 60
    val secs = safeSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}

@Composable
private fun ViolationCountsOverlay(
    counts: RuleViolationCounts,
    language: AppLanguage,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .background(colorScheme.surface.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.38f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = localizedString(language, R.string.violation_counts_title),
            color = colorScheme.onSurface.copy(alpha = 0.88f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        ViolationCountText(
            label = localizedString(language, R.string.movement_gauge_drift),
            count = counts.drift
        )
        ViolationCountText(
            label = localizedString(language, R.string.movement_gauge_motion),
            count = counts.motion
        )
        ViolationCountText(
            label = localizedString(language, R.string.violation_count_face),
            count = counts.face
        )
    }
}

@Composable
private fun ViolationCountText(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Text(
        text = "$label: $count",
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 10.sp
    )
}

@Composable
private fun MovementGaugeOverlay(
    state: MovementGaugeState,
    language: AppLanguage,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .background(colorScheme.surface.copy(alpha = 0.78f), RoundedCornerShape(20.dp))
            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MovementGaugeRow(
            label = localizedString(language, R.string.movement_gauge_drift),
            value = state.driftNormalizedScore,
            threshold = state.driftThresholdFactor,
            color = colorScheme.primary
        )
        MovementGaugeRow(
            label = localizedString(language, R.string.movement_gauge_motion),
            value = state.motionNormalizedScore,
            threshold = state.motionThresholdFactor,
            color = colorScheme.tertiary
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
    val colorScheme = MaterialTheme.colorScheme
    val safeThreshold = max(threshold, 0.001f)
    val progress = (value / safeThreshold).coerceIn(0f, 1f)
    val isOverLimit = value > safeThreshold
    val gaugeColor = if (isOverLimit) colorScheme.error else color

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = label,
            color = colorScheme.onSurface,
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
            trackColor = colorScheme.surfaceVariant
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

    if (!SHOW_POSE_DEBUG_POINTS) return

    fun shouldDrawPosePoint(point: Point3D): Boolean {
        return point.x.isFinite() &&
            point.y.isFinite() &&
            point.presence?.let { it >= 0.5f } != false
    }

    fun posePointColor(point: Point3D): Color {
        return if (point.visibility?.let { it < 0.5f } == true) {
            Color(0xFFFF9800)
        } else {
            Color.Cyan
        }
    }

    POSE_CONNECTIONS.forEach { (startIndex, endIndex) ->
        val start = overlayState.landmarks.getOrNull(startIndex) ?: return@forEach
        val end = overlayState.landmarks.getOrNull(endIndex) ?: return@forEach
        if (!shouldDrawPosePoint(start) || !shouldDrawPosePoint(end)) return@forEach

        drawLine(
            color = Color.Cyan.copy(alpha = 0.65f),
            start = Offset(mapX(start.x), mapY(start.y)),
            end = Offset(mapX(end.x), mapY(end.y)),
            strokeWidth = 2.5f
        )
    }

    overlayState.landmarks.forEach { point ->
        if (!shouldDrawPosePoint(point)) return@forEach
        drawCircle(
            color = posePointColor(point).copy(alpha = 0.85f),
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
fun BottomHUDEngine(
    language: AppLanguage,
    gameState: GameState,
    statusMessage: String,
    defeatReason: String,
    timerSeconds: Int,
    selectedDurationSeconds: Int,
    startDelayRemainingSeconds: Int,
    onDurationChanged: (Int) -> Unit,
    isDemoMode: Boolean,
    onDemoClick: () -> Unit,
    canOpenSettings: Boolean,
    onSettingsClick: () -> Unit,
    startEnabled: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val canStart = gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success
    val displaySeconds = if (canStart) selectedDurationSeconds else timerSeconds
    val selectedMinutes = (selectedDurationSeconds / 60).coerceIn(1, 120)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val stateHeadline = when (gameState) {
                        GameState.Idle -> localizedString(language, R.string.waiting_to_start)
                        GameState.WaitingForStabilization -> localizedString(language, R.string.device_stabilization)
                        GameState.StartingDelay -> "${localizedString(language, R.string.start_in)} ${startDelayRemainingSeconds}s"
                        GameState.HoldingPose -> localizedString(language, R.string.holding_pose)
                        GameState.Success -> localizedString(language, R.string.congrats_victory)
                        GameState.Failed -> localizedString(language, R.string.failed)
                    }
                    val headlineColor = when (gameState) {
                        GameState.Success -> colorScheme.tertiary
                        GameState.Failed -> colorScheme.error
                        else -> colorScheme.primary
                    }

                    Text(
                        text = stateHeadline,
                        color = headlineColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1
                    )
                    Text(
                        text = statusMessage,
                        color = colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 19.sp,
                        maxLines = 2
                    )
                    if (defeatReason.isNotEmpty()) {
                        Text(
                            text = "${localizedString(language, R.string.reason)}: $defeatReason",
                            color = colorScheme.error,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 2
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onDemoClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = if (isDemoMode) localizedString(language, R.string.camera) else localizedString(language, R.string.demo),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                FilledTonalIconButton(
                    onClick = onSettingsClick,
                    enabled = canOpenSettings,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = localizedString(language, R.string.settings)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
                        .background(colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                        .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (canStart) {
                        TimerStepButton(
                            symbol = "−",
                            enabled = selectedMinutes > 1,
                            contentDescription = localizedString(language, R.string.decrease),
                            onClick = { onDurationChanged((selectedMinutes - 1).coerceAtLeast(1)) }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = String.format(Locale.US, "%02d:%02d", displaySeconds / 60, displaySeconds % 60),
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 28.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("timer_display")
                        )
                    }

                    if (canStart) {
                        TimerStepButton(
                            symbol = "+",
                            enabled = selectedMinutes < 120,
                            contentDescription = localizedString(language, R.string.increase),
                            onClick = { onDurationChanged((selectedMinutes + 1).coerceAtMost(120)) }
                        )
                    }
                }

                if (canStart) {
                    Button(
                        onClick = onStart,
                        enabled = startEnabled,
                        modifier = Modifier
                            .height(72.dp)
                            .weight(1.05f)
                            .testTag("start_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = localizedString(language, R.string.start)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = localizedString(language, R.string.start),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .height(72.dp)
                            .weight(1.05f)
                            .testTag("stop_on_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colorScheme.error,
                            contentColor = colorScheme.onError
                        ),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(colorScheme.onError, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = localizedString(language, R.string.stop),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerStepButton(
    symbol: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp)
    ) {
        Text(
            text = symbol,
            color = if (enabled) {
                colorScheme.primary
            } else {
                colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
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
    timestampMs: Long,
    mediaPipeHandler: Handler
) {
    viewModel.registerCameraFrame(bitmap, timestampMs)

    val service = landmarkerService
    if (service == null) {
        viewModel.dropCameraFrame(timestampMs, recycle = true)
        return
    }

    val accepted = if (Looper.myLooper() == mediaPipeHandler.looper) {
        service.detectLiveStreamFrame(bitmap, timestampMs)
    } else {
        val future = submitToHandler(mediaPipeHandler) {
            service.detectLiveStreamFrame(bitmap, timestampMs)
        }
        runCatching {
            future.get()
        }.getOrDefault(false)
    }

    if (!accepted) {
        viewModel.dropCameraFrame(timestampMs, recycle = true)
    }
}

private fun <T> submitToHandler(
    handler: Handler,
    task: () -> T
): FutureTask<T> {
    val future = FutureTask<T> { task() }
    if (!handler.post(future)) {
        future.cancel(false)
    }
    return future
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
