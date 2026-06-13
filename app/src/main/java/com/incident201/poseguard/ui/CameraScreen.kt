package com.incident201.poseguard.ui

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
import android.os.SystemClock
import android.view.View
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.annotation.StringRes
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.incident201.poseguard.audio.AudioCuePlaybackSettings
import com.incident201.poseguard.audio.AudioCuePlayer
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseLandmarkerService
import com.incident201.poseguard.viewmodel.AppLanguage
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import com.incident201.poseguard.viewmodel.GameState
import com.incident201.poseguard.viewmodel.GameViewModel
import com.incident201.poseguard.viewmodel.MovementGaugeState
import com.incident201.poseguard.viewmodel.PoseOverlayState
import com.incident201.poseguard.viewmodel.RuleViolationCounts
import com.incident201.poseguard.viewmodel.SessionSummary
import com.incident201.poseguard.viewmodel.TimerMode
import com.incident201.poseguard.video.TimelapseRecorder
import com.incident201.poseguard.util.formatDurationHms
import com.incident201.poseguard.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.Date
import android.util.Size
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.Toast
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

private const val SHOW_POSE_DEBUG_OVERLAY = true
private const val SHOW_POSE_DEBUG_POINTS = true
private const val DEMO_FRAME_DELAY_MS = 40L

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
internal fun localizedFormatString(
    language: AppLanguage,
    @StringRes id: Int,
    vararg args: Any
): String {
    val context = LocalContext.current
    val locale = if (language == AppLanguage.Russian) Locale("ru", "RU") else Locale.US
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config).resources.getString(id, *args)
}

private fun cameraSelectorFor(lensFacing: Int): CameraSelector {
    return when (lensFacing) {
        CameraSelector.LENS_FACING_FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> CameraSelector.DEFAULT_BACK_CAMERA
    }
}

private fun oppositeLensFacing(lensFacing: Int): Int {
    return when (lensFacing) {
        CameraSelector.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_BACK
        else -> CameraSelector.LENS_FACING_FRONT
    }
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
    val timerMode by viewModel.timerMode.collectAsState()
    val randomMinDurationSeconds by viewModel.randomMinDurationSeconds.collectAsState()
    val randomMaxDurationSeconds by viewModel.randomMaxDurationSeconds.collectAsState()
    val startDelayRemainingSeconds by viewModel.startDelayRemainingSeconds.collectAsState()
    val poseOverlayState by viewModel.poseOverlayState.collectAsState()
    val movementGaugeState by viewModel.movementGaugeState.collectAsState()
    val violationCount by viewModel.violationCount.collectAsState()
    val ruleViolationCounts by viewModel.ruleViolationCounts.collectAsState()
    val sessionSummary by viewModel.sessionSummary.collectAsState()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showOnboarding by rememberSaveable { mutableStateOf(!onboardingCompleted) }
    var onboardingIncludesLanguage by rememberSaveable { mutableStateOf(!onboardingCompleted) }

    LaunchedEffect(onboardingCompleted) {
        if (!onboardingCompleted) {
            showOnboarding = true
            onboardingIncludesLanguage = true
        }
    }

    if (showOnboarding) {
        OnboardingScreen(
            language = gameSettings.language,
            includeLanguageSlide = onboardingIncludesLanguage,
            onLanguageChanged = viewModel::updateLanguage,
            onFinished = {
                if (onboardingIncludesLanguage) {
                    viewModel.markOnboardingCompleted()
                }
                showOnboarding = false
                onboardingIncludesLanguage = false
            },
            modifier = modifier
        )
        return
    }

    val intifaceState by viewModel.intifaceState.collectAsState()
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pauseIntifaceSessionSignals()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseIntifaceSessionSignals()
        }
    }

    AudioCueAnnouncer(viewModel = viewModel, settings = gameSettings)
    val currentGameState = rememberUpdatedState(gameState)
    val currentTimelapseRecordingEnabled = rememberUpdatedState(gameSettings.timelapseRecordingEnabled)
    val debugModeEnabled = gameSettings.debugModeEnabled
    val currentViolationCount = rememberUpdatedState(violationCount)
    val coroutineScope = rememberCoroutineScope()
    val timelapseRecorder = remember(context) { TimelapseRecorder(context.applicationContext) }
    var pendingTimelapseFile by remember { mutableStateOf<File?>(null) }
    var timelapseUiState by remember { mutableStateOf(TimelapseUiState.Disabled) }
    var pendingPoseDebugJson by remember { mutableStateOf<String?>(null) }
    val poseDebugSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingPoseDebugJson
        pendingPoseDebugJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) { savePoseDebugJson(context, uri, json) }
            Toast.makeText(
                context,
                if (saved) "Pose debug JSON saved" else "Failed to save pose debug JSON",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
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
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val lastPoseTimestampMs = remember { AtomicLong(0L) }
    var imageAnalysisRef by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundLensFacing by remember { mutableStateOf<Int?>(null) }
    var availableLensFacings by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedLensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
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
            viewModel.resetPoseInputContinuity()
            val oldDemoBitmap = demoBitmap
            demoBitmap = resizedBitmap
            if (oldDemoBitmap != null && oldDemoBitmap !== resizedBitmap) {
                oldDemoBitmap.recycleIfNeeded()
            }
            isDemoMode = true
        }
    }

    LaunchedEffect(context) {
        landmarkerService = PoseLandmarkerService(context, object : PoseLandmarkerService.LandmarkerListener {
            override fun onError(error: String) {
                Log.e("CameraScreen", "MediaPipe Error: $error")
            }

            override fun onResults(result: com.incident201.poseguard.tracker.PoseLandmarks, imageWidth: Int, imageHeight: Int, timestampMs: Long) {
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
            boundLensFacing = null
            landmarkerService?.close()
            landmarkerService = null
            viewModel.clearCameraFrameCache(recycle = true)
            cameraExecutor.shutdown()
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

    LaunchedEffect(debugModeEnabled) {
        if (!debugModeEnabled && isDemoMode) {
            viewModel.resetPoseInputContinuity()
            isDemoMode = false
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
                cameraExecutor.execute {
                    val timestampMs = nextFrameTimestampMs(
                        lastPoseTimestampMs,
                        SystemClock.elapsedRealtimeNanos() / 1_000_000L
                    )
                    submitFrameToPosePipeline(
                        bitmap = frameBitmap,
                        viewModel = viewModel,
                        landmarkerService = landmarkerService,
                        timestampMs = timestampMs
                    )
                }
            } catch (e: RejectedExecutionException) {
                frameBitmap.recycleIfNeeded()
                Log.w("CameraScreen", "Dropping demo frame after camera executor shutdown", e)
                break
            }
            delay(DEMO_FRAME_DELAY_MS)
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

    fun bindCamera(previewView: PreviewView, requestedLensFacing: Int) {
        fun bindWithProvider(cameraProvider: ProcessCameraProvider) {
            cameraProviderRef = cameraProvider

            val backCameraAvailable = runCatching {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            }.getOrDefault(false)
            val frontCameraAvailable = runCatching {
                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            }.getOrDefault(false)
            val available = buildList {
                if (backCameraAvailable) add(CameraSelector.LENS_FACING_BACK)
                if (frontCameraAvailable) add(CameraSelector.LENS_FACING_FRONT)
            }
            availableLensFacings = available

            val lensFacing = when {
                requestedLensFacing in available -> requestedLensFacing
                CameraSelector.LENS_FACING_BACK in available -> CameraSelector.LENS_FACING_BACK
                CameraSelector.LENS_FACING_FRONT in available -> CameraSelector.LENS_FACING_FRONT
                else -> {
                    Log.w("CameraScreen", "No available cameras to bind")
                    return
                }
            }
            val cameraSelector = cameraSelectorFor(lensFacing)

            imageAnalysisRef?.clearAnalyzer()
            cameraProvider.unbindAll()

            val preview = Preview.Builder()
                .build()
                .apply {
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
                            timestampMs = timestampMs,
                            violationsCount = currentViolationCount.value,
                            violationsText = currentViolationsCounterText.value
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

            try {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                selectedLensFacing = lensFacing
                boundLensFacing = lensFacing
            } catch (e: Exception) {
                Log.e("CameraScreen", "CameraX binding failed", e)
                imageAnalysis.clearAnalyzer()
                if (imageAnalysisRef === imageAnalysis) {
                    imageAnalysisRef = null
                }
                boundLensFacing = null
            }
        }

        val existingProvider = cameraProviderRef
        if (existingProvider != null) {
            bindWithProvider(existingProvider)
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
        cameraProviderFuture.addListener({
            runCatching { bindWithProvider(cameraProviderFuture.get()) }
                .onFailure { Log.e("CameraScreen", "Failed to initialize CameraX", it) }
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    val canSwitchCamera = gameState == GameState.Idle &&
        !showFinalScreen &&
        !isDemoMode &&
        availableLensFacings.size > 1

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
            onTimelapseRecordingEnabledChanged = viewModel::updateTimelapseRecordingEnabled,
            onDebugModeEnabledChanged = viewModel::updateDebugModeEnabled,
            onOcclusionFreezeVisibilityAlwaysChanged = viewModel::updateOcclusionFreezeVisibilityAlways,
            onOcclusionFreezeVisibilityP10AlwaysChanged = viewModel::updateOcclusionFreezeVisibilityP10Always,
            onOcclusionFreezeVisibilityHardChanged = viewModel::updateOcclusionFreezeVisibilityHard,
            onOcclusionFreezeVisibilitySoftChanged = viewModel::updateOcclusionFreezeVisibilitySoft,
            onOcclusionJitterFreezeThresholdChanged = viewModel::updateOcclusionJitterFreezeThreshold,
            onPoseSmootherMinCutoffChanged = viewModel::updatePoseSmootherMinCutoff,
            onPoseSmootherBetaChanged = viewModel::updatePoseSmootherBeta,
            onPoseSmootherDerivativeCutoffChanged = viewModel::updatePoseSmootherDerivativeCutoff,
            onWristDriftWeightChanged = viewModel::updateWristDriftWeight,
            onCustomizeAudioEnabledChanged = viewModel::updateCustomizeAudioEnabled,
            onTtsVoiceModeChanged = viewModel::updateTtsVoiceMode,
            onTtsPhraseTemplateChanged = viewModel::updateTtsPhraseTemplate,
            onAudioCueSettingsChanged = viewModel::updateAudioCueSettings,
            intifaceState = intifaceState,
            onIntifaceConnectionEnabledChanged = viewModel::updateIntifaceConnectionEnabled,
            onIntifaceBackgroundModeChanged = viewModel::updateIntifaceBackgroundMode,
            onIntifaceBackgroundVibrationChanged = viewModel::updateIntifaceBackgroundVibration,
            onIntifaceViolationModeChanged = viewModel::updateIntifaceViolationMode,
            onIntifaceViolationVibrationChanged = viewModel::updateIntifaceViolationVibration,
            onIntifaceViolationPauseSecondsChanged = viewModel::updateIntifaceViolationPauseSeconds,
            onIntifaceWebSocketUrlChanged = viewModel::updateIntifaceWebSocketUrl,
            onIntifaceSearchDevices = viewModel::searchIntifaceDevices,
            onIntifaceDeviceSelected = viewModel::selectIntifaceDevice,
            onIntifaceTestVibration = viewModel::testIntifaceVibration,
            onIntifaceDisconnect = viewModel::disconnectIntiface,
            onShowInstructions = {
                showSettings = false
                showOnboarding = true
                onboardingIncludesLanguage = false
            }
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
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(0f),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }
                        },
                        update = { previewView ->
                            if (boundLensFacing != selectedLensFacing) {
                                bindCamera(previewView, selectedLensFacing)
                            }
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
                            boundLensFacing = null
                        }
                    )
                }
            }

            if (canSwitchCamera) {
                SwitchCameraButton(
                    onClick = {
                        if (canSwitchCamera) {
                            viewModel.resetPoseInputContinuity()
                            selectedLensFacing = oppositeLensFacing(selectedLensFacing)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .zIndex(4f),
                    contentDescription = localizedString(gameSettings.language, R.string.switch_camera)
                )
            }

            if (!showFinalScreen && debugModeEnabled) {
                PoseDebugSaveButton(
                    onClick = {
                        val json = viewModel.buildPoseDebugSnapshotJson()
                        if (json == null) {
                            Toast.makeText(context, "No pose data to save", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingPoseDebugJson = json
                            poseDebugSaveLauncher.launch(poseDebugFileName())
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .zIndex(4f)
                )
            }

            if (!showFinalScreen && gameState == GameState.StartingDelay && startDelayRemainingSeconds >= 0) {
                PreviewCountdownOverlay(
                    seconds = startDelayRemainingSeconds,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(4f)
                )
            }

            if (!showFinalScreen && SHOW_POSE_DEBUG_OVERLAY) {
                PoseDebugOverlay(
                    overlayState = poseOverlayState,
                    mirrorX = selectedLensFacing == CameraSelector.LENS_FACING_FRONT,
                    debugModeEnabled = debugModeEnabled,
                    modifier = Modifier
                        .matchParentSize()
                        .zIndex(2f)
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

            if (!showFinalScreen && intifaceState.isSupported) {
                IntifacePreviewStatusOverlay(
                    state = intifaceState,
                    language = gameSettings.language,
                    enabled = gameSettings.intifaceConnectionEnabled,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .zIndex(4f)
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
            timerMode = timerMode,
            randomMinDurationSeconds = randomMinDurationSeconds,
            randomMaxDurationSeconds = randomMaxDurationSeconds,
            startDelayRemainingSeconds = startDelayRemainingSeconds,
            onDurationSecondsChanged = viewModel::updateSelectedDurationSeconds,
            onTimerModeChanged = viewModel::updateTimerMode,
            onRandomDurationRangeChanged = viewModel::updateRandomDurationRangeSeconds,
            isDemoMode = isDemoMode,
            debugModeEnabled = debugModeEnabled,
            onDemoClick = {
                if (isDemoMode) {
                    viewModel.resetPoseInputContinuity()
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
private fun PreviewCountdownOverlay(
    seconds: Int,
    modifier: Modifier = Modifier
) {
    val text = seconds.toString()

    Box(
        modifier = modifier.size(width = 340.dp, height = 240.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.blur(18.dp),
            color = Color.White.copy(alpha = 0.34f),
            fontSize = 180.sp,
            lineHeight = 180.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 180.sp,
            lineHeight = 180.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color.Black.copy(alpha = 0.46f),
                    offset = Offset(0f, 10f),
                    blurRadius = 30f
                )
            )
        )
    }
}

@Composable
private fun SwitchCameraButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.58f),
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Cameraswitch,
            contentDescription = contentDescription
        )
    }
}

@Composable
private fun PoseDebugSaveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.58f),
            contentColor = Color.White
        )
    ) {
        Text(
            text = "json",
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
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
    val faceDirectionLabel = faceDirectionLabelFor(summary.settings.faceCheckMode)

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
                            value = formatDurationHms(summary.initialTimerSeconds),
                            modifier = Modifier.weight(1f)
                        )
                        FinalMetricCard(
                            label = localizedString(language, R.string.final_actual_timer),
                            value = formatDurationHms(summary.actualTimerSeconds),
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
                        label = localizedString(language, R.string.pose_deviation_tolerance),
                        value = localizedString(
                            language,
                            driftTolerancePresetFor(summary.settings.driftThresholdFactor).nameRes
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    FinalSettingRow(
                        label = localizedString(language, R.string.motion_sensitivity),
                        value = localizedString(
                            language,
                            motionSensitivityPresetFor(summary.settings.motionThresholdFactor).nameRes
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    FinalSettingRow(
                        label = localizedString(language, R.string.final_face_direction),
                        value = localizedString(language, faceDirectionLabel)
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

private fun faceDirectionLabelFor(mode: FaceCheckMode): Int {
    return when (mode) {
        FaceCheckMode.FaceAwayFromCamera -> R.string.face_away
        FaceCheckMode.FaceToCamera -> R.string.face_to_camera
        FaceCheckMode.Disabled -> R.string.do_not_check
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
    mirrorX: Boolean,
    debugModeEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawPoseDebugOverlay(overlayState, mirrorX, debugModeEnabled)
        }
        if (debugModeEnabled && overlayState.identityDebugText.isNotBlank()) {
            Text(
                text = overlayState.identityDebugText,
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 58.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

private fun DrawScope.drawPoseDebugOverlay(
    overlayState: PoseOverlayState,
    mirrorX: Boolean,
    debugModeEnabled: Boolean
) {
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

    fun mapX(normalizedX: Float): Float {
        val clampedX = normalizedX.coerceIn(0f, 1f)
        val x = if (mirrorX) 1f - clampedX else clampedX
        return offsetX + x * imageWidth * scale
    }

    fun mapY(normalizedY: Float): Float =
        offsetY + normalizedY.coerceIn(0f, 1f) * imageHeight * scale

    val rect = overlayState.cropRect
    if (debugModeEnabled && rect != null) {
        val x1 = mapX(rect.left)
        val x2 = mapX(rect.right)
        val left = minOf(x1, x2)
        val right = maxOf(x1, x2)
        val top = mapY(rect.top)
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
    if (debugModeEnabled && detectorInputRect != null) {
        val x1 = mapX(detectorInputRect.left)
        val x2 = mapX(detectorInputRect.right)
        val left = minOf(x1, x2)
        val right = maxOf(x1, x2)
        val top = mapY(detectorInputRect.top)
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
        val x1 = mapX(faceRect.left)
        val x2 = mapX(faceRect.right)
        val left = minOf(x1, x2)
        val right = maxOf(x1, x2)
        val top = mapY(faceRect.top)
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

    if (debugModeEnabled) {
        face.keypoints.forEach { point ->
            if (!point.x.isFinite() || !point.y.isFinite()) return@forEach
            drawCircle(
                color = Color.Red.copy(alpha = 0.95f),
                radius = 5f,
                center = Offset(mapX(point.x), mapY(point.y))
            )
        }
    }

    if (!SHOW_POSE_DEBUG_POINTS) return

    fun shouldDrawPosePoint(point: Point3D): Boolean {
        return point.x.isFinite() &&
            point.y.isFinite() &&
            point.presence?.let { it >= 0.5f } != false
    }

    fun posePointColor(index: Int): Color {
        return if (index in overlayState.frozenLandmarkIndices) {
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

    overlayState.landmarks.forEachIndexed { index, point ->
        if (!shouldDrawPosePoint(point)) return@forEachIndexed
        drawCircle(
            color = posePointColor(index).copy(alpha = 0.85f),
            radius = 4f,
            center = Offset(mapX(point.x), mapY(point.y))
        )
    }
}

@Composable
private fun AudioCueAnnouncer(viewModel: GameViewModel, settings: GameSettings) {
    val context = LocalContext.current
    val currentPlaybackSettings = rememberUpdatedState(
        AudioCuePlaybackSettings(
            customizeAudioEnabled = settings.customizeAudioEnabled,
            cueSettings = settings.audioCueSettings
        )
    )
    val player = remember(context) {
        AudioCuePlayer(context) { currentPlaybackSettings.value }
    }

    LaunchedEffect(player, settings.language, settings.ttsVoiceMode) {
        val locale = if (settings.language == AppLanguage.Russian) Locale("ru", "RU") else Locale.US
        player.setTtsConfig(locale, settings.ttsVoiceMode)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player, settings.language, settings.ttsVoiceMode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val locale = if (settings.language == AppLanguage.Russian) {
                    Locale("ru", "RU")
                } else {
                    Locale.US
                }
                player.setTtsConfig(locale, settings.ttsVoiceMode)
                player.refreshTtsEngineIfSystemDefaultChanged()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(viewModel, player) {
        viewModel.audioCueEvents.collect { event ->
            player.play(event.cue, event.ttsText)
        }
    }

    DisposableEffect(player) {
        onDispose { player.close() }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomHUDEngine(
    language: AppLanguage,
    gameState: GameState,
    statusMessage: String,
    defeatReason: String,
    timerSeconds: Int,
    selectedDurationSeconds: Int,
    timerMode: TimerMode,
    randomMinDurationSeconds: Int,
    randomMaxDurationSeconds: Int,
    startDelayRemainingSeconds: Int,
    onDurationSecondsChanged: (Int) -> Unit,
    onTimerModeChanged: (TimerMode) -> Unit,
    onRandomDurationRangeChanged: (Int, Int) -> Unit,
    isDemoMode: Boolean,
    debugModeEnabled: Boolean,
    onDemoClick: () -> Unit,
    canOpenSettings: Boolean,
    onSettingsClick: () -> Unit,
    startEnabled: Boolean = true,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val canStart = gameState == GameState.Idle || gameState == GameState.Failed || gameState == GameState.Success
    val displayTimerText = if (canStart && timerMode == TimerMode.Random) {
        "??:??:??"
    } else {
        formatDurationHms(if (canStart) selectedDurationSeconds else timerSeconds)
    }
    var showTimerSheet by rememberSaveable { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf(timerMode) }
    var pickerHours by rememberSaveable { mutableStateOf(0) }
    var pickerMinutes by rememberSaveable { mutableStateOf(0) }
    var randomMinPickerHours by rememberSaveable { mutableStateOf(0) }
    var randomMinPickerMinutes by rememberSaveable { mutableStateOf(0) }
    var randomMaxPickerHours by rememberSaveable { mutableStateOf(0) }
    var randomMaxPickerMinutes by rememberSaveable { mutableStateOf(0) }
    val selectedPickerSeconds = (pickerHours * 3600) + (pickerMinutes * 60)
    val randomMinPickerSeconds = (randomMinPickerHours * 3600) + (randomMinPickerMinutes * 60)
    val randomMaxPickerSeconds = (randomMaxPickerHours * 3600) + (randomMaxPickerMinutes * 60)
    val hourRange = 0..maxOf(
        99,
        selectedDurationSeconds / 3600,
        randomMinDurationSeconds / 3600,
        randomMaxDurationSeconds / 3600,
        pickerHours,
        randomMinPickerHours,
        randomMaxPickerHours
    )

    fun openTimerSheet() {
        pickerMode = timerMode
        val exactMinutes = (selectedDurationSeconds.coerceAtLeast(1) + 59) / 60
        pickerHours = exactMinutes / 60
        pickerMinutes = exactMinutes % 60
        val minMinutes = (randomMinDurationSeconds.coerceAtLeast(1) + 59) / 60
        randomMinPickerHours = minMinutes / 60
        randomMinPickerMinutes = minMinutes % 60
        val maxMinutes = (randomMaxDurationSeconds.coerceAtLeast(randomMinDurationSeconds) + 59) / 60
        randomMaxPickerHours = maxMinutes / 60
        randomMaxPickerMinutes = maxMinutes % 60
        showTimerSheet = true
    }

    if (showTimerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTimerSheet = false },
            containerColor = colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = localizedString(language, R.string.set_timer),
                    color = colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilterChip(
                        selected = pickerMode == TimerMode.Exact,
                        onClick = { pickerMode = TimerMode.Exact },
                        label = { Text(localizedString(language, R.string.timer_mode_exact)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = pickerMode == TimerMode.Random,
                        onClick = { pickerMode = TimerMode.Random },
                        label = { Text(localizedString(language, R.string.timer_mode_random)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (pickerMode == TimerMode.Exact) {
                    Text(
                        text = formatDurationHms(selectedPickerSeconds),
                        color = colorScheme.primary,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DurationPickerColumn(
                            label = localizedString(language, R.string.hours),
                            value = pickerHours,
                            range = hourRange,
                            wrapSelectorWheel = false,
                            onValueChanged = { pickerHours = it },
                            modifier = Modifier.weight(1f)
                        )
                        DurationPickerColumn(
                            label = localizedString(language, R.string.minutes),
                            value = pickerMinutes,
                            range = 0..59,
                            wrapSelectorWheel = true,
                            onValueChanged = { pickerMinutes = it },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        text = localizedString(language, R.string.random_timer_range),
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = localizedString(language, R.string.timer_minimum),
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        DurationPickerColumn(
                            label = localizedString(language, R.string.hours),
                            value = randomMinPickerHours,
                            range = hourRange,
                            wrapSelectorWheel = false,
                            onValueChanged = { newHours ->
                                randomMinPickerHours = newHours
                                val newMin = newHours * 3600 + randomMinPickerMinutes * 60
                                if (newMin > randomMaxPickerSeconds) {
                                    randomMaxPickerHours = newHours
                                    randomMaxPickerMinutes = randomMinPickerMinutes
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DurationPickerColumn(
                            label = localizedString(language, R.string.minutes),
                            value = randomMinPickerMinutes,
                            range = 0..59,
                            wrapSelectorWheel = true,
                            onValueChanged = { newMinutes ->
                                randomMinPickerMinutes = newMinutes
                                val newMin = randomMinPickerHours * 3600 + newMinutes * 60
                                if (newMin > randomMaxPickerSeconds) {
                                    randomMaxPickerHours = randomMinPickerHours
                                    randomMaxPickerMinutes = newMinutes
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = localizedString(language, R.string.timer_maximum),
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        DurationPickerColumn(
                            label = localizedString(language, R.string.hours),
                            value = randomMaxPickerHours,
                            range = hourRange,
                            wrapSelectorWheel = false,
                            onValueChanged = { newHours ->
                                randomMaxPickerHours = newHours
                                val newMax = newHours * 3600 + randomMaxPickerMinutes * 60
                                if (newMax < randomMinPickerSeconds) {
                                    randomMinPickerHours = newHours
                                    randomMinPickerMinutes = randomMaxPickerMinutes
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        DurationPickerColumn(
                            label = localizedString(language, R.string.minutes),
                            value = randomMaxPickerMinutes,
                            range = 0..59,
                            wrapSelectorWheel = true,
                            onValueChanged = { newMinutes ->
                                randomMaxPickerMinutes = newMinutes
                                val newMax = randomMaxPickerHours * 3600 + newMinutes * 60
                                if (newMax < randomMinPickerSeconds) {
                                    randomMinPickerHours = randomMaxPickerHours
                                    randomMinPickerMinutes = newMinutes
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = localizedString(language, R.string.random_timer_hidden_hint),
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }

                val valuesValid = if (pickerMode == TimerMode.Exact) {
                    selectedPickerSeconds > 0
                } else {
                    randomMinPickerSeconds > 0 && randomMaxPickerSeconds >= randomMinPickerSeconds
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { showTimerSheet = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(localizedString(language, R.string.cancel))
                    }
                    Button(
                        onClick = {
                            if (pickerMode == TimerMode.Exact) {
                                onDurationSecondsChanged(selectedPickerSeconds)
                            } else {
                                onRandomDurationRangeChanged(randomMinPickerSeconds, randomMaxPickerSeconds)
                            }
                            onTimerModeChanged(pickerMode)
                            showTimerSheet = false
                        },
                        enabled = valuesValid,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(localizedString(language, R.string.apply))
                    }
                }
            }
        }
    }

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

                if (debugModeEnabled) {
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

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth()
            ) {
                val compactHud = maxWidth < 360.dp
                val actionButtonWidth = if (compactHud) 104.dp else 136.dp
                val timerFontSize = if (compactHud) 22.sp else 24.sp
                val stepButtonSize = if (compactHud) 34.dp else 36.dp
                val horizontalGap = if (compactHud) 8.dp else 12.dp
                val actionButtonContentPadding = PaddingValues(
                    horizontal = if (compactHud) 8.dp else 12.dp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp)
                            .background(colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                            .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(22.dp))
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (canStart && timerMode == TimerMode.Exact) {
                            TimerStepButton(
                                symbol = "−",
                                enabled = selectedDurationSeconds > 60,
                                contentDescription = localizedString(language, R.string.decrease),
                                onClick = {
                                    onDurationSecondsChanged((selectedDurationSeconds - 60).coerceAtLeast(60))
                                },
                                size = stepButtonSize
                            )
                        }

                        Text(
                            text = displayTimerText,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = timerFontSize,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("timer_display")
                                .then(
                                    if (canStart) {
                                        Modifier.clickable { openTimerSheet() }
                                    } else {
                                        Modifier
                                    }
                                )
                        )

                        if (canStart && timerMode == TimerMode.Exact) {
                            TimerStepButton(
                                symbol = "+",
                                enabled = true,
                                contentDescription = localizedString(language, R.string.increase),
                                onClick = { onDurationSecondsChanged(selectedDurationSeconds + 60) },
                                size = stepButtonSize
                            )
                        }
                    }

                    if (canStart) {
                        Button(
                            onClick = onStart,
                            enabled = startEnabled,
                            modifier = Modifier
                                .height(72.dp)
                                .width(actionButtonWidth)
                                .testTag("start_button"),
                            contentPadding = actionButtonContentPadding,
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
                                fontSize = if (compactHud) 15.sp else 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .height(72.dp)
                                .width(actionButtonWidth)
                                .testTag("stop_on_button"),
                            contentPadding = actionButtonContentPadding,
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = localizedString(language, R.string.stop),
                                fontSize = if (compactHud) 15.sp else 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationPickerColumn(
    label: String,
    value: Int,
    range: IntRange,
    wrapSelectorWheel: Boolean,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        DurationNumberPicker(
            value = value,
            range = range,
            formatter = { String.format(Locale.US, "%02d", it) },
            wrapSelectorWheel = wrapSelectorWheel,
            onValueChanged = onValueChanged,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DurationNumberPicker(
    value: Int,
    range: IntRange,
    formatter: (Int) -> String,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wrapSelectorWheel: Boolean = false
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    AndroidView(
        modifier = modifier.height(160.dp),
        factory = { context ->
            NumberPicker(context).apply {
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                minValue = range.first
                maxValue = range.last
                this.value = value.coerceIn(range)
                this.wrapSelectorWheel = wrapSelectorWheel
                setFormatter { formatter(it) }
                setOnValueChangedListener { _, _, newValue -> onValueChanged(newValue) }
                applyTimerPickerStyle(textColor)
            }
        },
        update = { picker ->
            picker.minValue = range.first
            picker.maxValue = range.last
            picker.value = value.coerceIn(range)
            picker.wrapSelectorWheel = wrapSelectorWheel
            picker.setFormatter { formatter(it) }
            picker.setOnValueChangedListener { _, _, newValue -> onValueChanged(newValue) }
            picker.applyTimerPickerStyle(textColor)
        }
    )
}

private fun NumberPicker.applyTimerPickerStyle(textColor: Int) {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)

    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child is EditText) {
            child.setTextColor(textColor)
            child.textAlignment = View.TEXT_ALIGNMENT_CENTER
            child.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD
            )
            child.textSize = 24f
        }
    }
}

@Composable
private fun TimerStepButton(
    symbol: String,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 36.dp
) {
    val colorScheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size)
    ) {
        Text(
            text = symbol,
            color = if (enabled) {
                colorScheme.primary
            } else {
                colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            fontSize = if (size < 36.dp) 22.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}


private fun poseDebugFileName(): String {
    val formatter = SimpleDateFormat("ddMMyyyyHHmmss", Locale.US)
    return "pose_${formatter.format(Date())}.json"
}

private fun savePoseDebugJson(context: Context, uri: Uri, json: String): Boolean {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("Failed to open output stream for pose debug JSON")
        true
    }.getOrElse {
        Log.e("CameraScreen", "Failed to save pose debug JSON", it)
        false
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

@Composable
private fun IntifacePreviewStatusOverlay(
    state: com.incident201.poseguard.intiface.IntifaceUiState,
    language: AppLanguage,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!state.isSupported) return
    if (!enabled) return

    val statusText = when {
        state.errorMessage != null -> localizedIntifaceMessage(language, state.errorMessage)
        state.isScanning -> localizedString(language, R.string.intiface_overlay_connecting)
        state.isConnected && state.selectedDevice != null -> localizedFormatString(language, R.string.intiface_overlay_connected, state.selectedDevice.displayName)
        state.isConnected && state.selectedDevice == null -> localizedString(language, R.string.intiface_overlay_device_not_selected)
        else -> null
    }

    if (statusText != null) {
        Text(
            text = statusText,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
