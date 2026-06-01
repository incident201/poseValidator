package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.tracker.FaceDetectionStatus
import com.example.tracker.FaceCandidateCropper
import com.example.tracker.FaceDetectorService
import com.example.tracker.MovementTracker
import com.example.tracker.Point3D
import com.example.tracker.PoseFrameCropper
import com.example.tracker.PoseLandmarks
import com.example.tracker.PoseSmoother
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

enum class GameState {
    Idle,
    WaitingForStabilization,
    StartingDelay,
    HoldingPose,
    Success,
    Failed
}

enum class FaceCheckMode {
    FaceToCamera,
    FaceAwayFromCamera,
    Disabled
}

enum class AppLanguage { Russian, English }

data class GameSettings(
    val language: AppLanguage = AppLanguage.English,
    val faceCheckMode: FaceCheckMode = FaceCheckMode.FaceAwayFromCamera,
    val faceDetectionConfidence: Float = 0.8f,
    val driftThresholdFactor: Float = 0.12f,
    val motionThresholdFactor: Float = 0.06f,
    val minimumPenaltyIntervalSeconds: Int = 5,
    val maxViolations: Int = 4,
    val penaltiesEnabled: Boolean = true,
    val firstViolationPenaltyMinutes: Int = 1,
    val secondViolationPenaltyMinutes: Int = 3,
    val thirdViolationPenaltyMinutes: Int = 3,
    val subsequentViolationPenaltyMinutes: Int = 3,
    val timelapseRecordingEnabled: Boolean = true
)

data class FaceOverlayPoint(val x: Float, val y: Float)

data class FaceOverlayState(
    val status: FaceDetectionStatus = FaceDetectionStatus.NotProcessed,
    val faceRect: PoseOverlayRect? = null,
    val keypoints: List<FaceOverlayPoint> = emptyList(),
    val score: Float = 0f,
    val detectorInputRect: PoseOverlayRect? = null,
    val detectorInputWidth: Int = 0,
    val detectorInputHeight: Int = 0,
    val debugMessage: String = ""
)

data class PoseOverlayState(
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val landmarks: List<Point3D> = emptyList(),
    val cropRect: PoseOverlayRect? = null,
    val face: FaceOverlayState = FaceOverlayState()
)

data class PoseOverlayRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class MovementGaugeState(
    val active: Boolean = false,
    val driftNormalizedScore: Float = 0f,
    val driftThresholdFactor: Float = 0f,
    val motionNormalizedScore: Float = 0f,
    val motionThresholdFactor: Float = 0f
)

data class RuleViolationCounts(
    val drift: Int = 0,
    val motion: Int = 0,
    val face: Int = 0
)

data class SessionSummary(
    val result: GameState,
    val initialTimerSeconds: Int,
    val actualTimerSeconds: Int,
    val violationCounts: RuleViolationCounts,
    val settings: GameSettings,
    val defeatReason: String = ""
)

class GameViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val tag = "GameViewModel"
    private val defaultDurationSeconds = 180
    private val minimumDurationMinutes = 1
    private val maximumDurationMinutes = 120
    private val startDelaySeconds = 10
    private val stabilizationDurationMs = 5_000L
    private val gyroscopeStillThresholdRadPerSec = 0.08f

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscopeSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var stabilizationStableSinceMs: Long? = null
    private var stabilizationCompleted = false

    private val prefs: SharedPreferences = application.getSharedPreferences("game_settings", Context.MODE_PRIVATE)

    private val _gameState = MutableStateFlow(GameState.Idle)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    private val _timerSeconds = MutableStateFlow(defaultDurationSeconds)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()
    private val _violationCount = MutableStateFlow(0)
    val violationCount: StateFlow<Int> = _violationCount.asStateFlow()
    private val _ruleViolationCounts = MutableStateFlow(RuleViolationCounts())
    val ruleViolationCounts: StateFlow<RuleViolationCounts> = _ruleViolationCounts.asStateFlow()
    private val _selectedDurationSeconds = MutableStateFlow(defaultDurationSeconds)
    val selectedDurationSeconds: StateFlow<Int> = _selectedDurationSeconds.asStateFlow()
    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    private val _defeatReason = MutableStateFlow("")
    val defeatReason: StateFlow<String> = _defeatReason.asStateFlow()
    private val _movementGaugeState = MutableStateFlow(MovementGaugeState())
    val movementGaugeState: StateFlow<MovementGaugeState> = _movementGaugeState.asStateFlow()
    private val _startDelayRemainingSeconds = MutableStateFlow(0)
    val startDelayRemainingSeconds: StateFlow<Int> = _startDelayRemainingSeconds.asStateFlow()
    private val _poseOverlayState = MutableStateFlow(PoseOverlayState())
    val poseOverlayState: StateFlow<PoseOverlayState> = _poseOverlayState.asStateFlow()
    private val _voiceEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val voiceEvents: SharedFlow<String> = _voiceEvents.asSharedFlow()
    private val _gameSettings = MutableStateFlow(loadSettings())
    val gameSettings: StateFlow<GameSettings> = _gameSettings.asStateFlow()
    private val _sessionSummary = MutableStateFlow<SessionSummary?>(null)
    val sessionSummary: StateFlow<SessionSummary?> = _sessionSummary.asStateFlow()

    private var sessionInitialTimerSeconds = defaultDurationSeconds
    private var sessionActualTimerSeconds = defaultDurationSeconds
    private var sessionSettingsSnapshot = _gameSettings.value

    private data class AnalyzedPoseFrame(
        val pose: PoseLandmarks,
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val face: FaceOverlayState
    )

    private val faceDetectorService = FaceDetectorService(application.applicationContext, _gameSettings.value.faceDetectionConfidence)
    private val movementTracker = MovementTracker()
    private val poseSmoother = PoseSmoother()

    private val frameLock = Any()
    private val processingLock = Any()
    private var processingGeneration = 0L
    private val mediaPipeResultExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isCleared = false
    private val pendingFrames = LinkedHashMap<Long, Bitmap>()
    private var latestAnalyzedFrame: AnalyzedPoseFrame? = null
    private var startDelayJob: Job? = null
    private var timerJob: Job? = null

    private var currentViolationCount = 0
    private var lastPenaltyAtMs = 0L
    private var consecutiveFaceFailFrames = 0
    private val faceFailFramesThreshold = 5

    private enum class RuleViolationType { Drift, Motion, PersonDisappeared, FaceNotMatchingMode }


    private fun tr(resId: Int, vararg args: Any): String {
        val locale = if (_gameSettings.value.language == AppLanguage.Russian) Locale("ru", "RU") else Locale.US
        val config = android.content.res.Configuration(getApplication<Application>().resources.configuration)
        config.setLocale(locale)
        val res = getApplication<Application>().createConfigurationContext(config).resources
        return if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
    }

    init {
        applySettingsToEngines(_gameSettings.value)
        _statusMessage.value = tr(R.string.status_initial)
    }

    private fun loadSettings(): GameSettings {
        val mode = runCatching { FaceCheckMode.valueOf(prefs.getString("face_mode", FaceCheckMode.FaceAwayFromCamera.name) ?: FaceCheckMode.FaceAwayFromCamera.name) }
            .getOrDefault(FaceCheckMode.FaceAwayFromCamera)
        val language = runCatching { AppLanguage.valueOf(prefs.getString("app_language", AppLanguage.English.name) ?: AppLanguage.English.name) }.getOrDefault(AppLanguage.English)
        val (driftThresholdFactor, motionThresholdFactor) = normalizeSensitivityThresholds(
            drift = prefs.getFloat("pose_drift_factor_v2", 0.12f).coerceIn(0.05f, 0.40f),
            motion = prefs.getFloat("pose_motion_factor_v2", 0.06f).coerceIn(0.03f, 0.25f)
        )
        return GameSettings(
            language = language,
            faceCheckMode = mode,
            faceDetectionConfidence = 0.8f,
            driftThresholdFactor = driftThresholdFactor,
            motionThresholdFactor = motionThresholdFactor,
            minimumPenaltyIntervalSeconds = prefs.getInt("penalty_interval_sec", 5).coerceIn(0, 30),
            maxViolations = prefs.getInt("max_violations", 4).coerceIn(0, 9999),
            penaltiesEnabled = prefs.getBoolean("penalties_enabled", true),
            firstViolationPenaltyMinutes = prefs.getInt("penalty_1_min", 1).coerceIn(0, 9999),
            secondViolationPenaltyMinutes = prefs.getInt("penalty_2_min", 3).coerceIn(0, 9999),
            thirdViolationPenaltyMinutes = prefs.getInt("penalty_3_min", 3).coerceIn(0, 9999),
            subsequentViolationPenaltyMinutes = prefs.getInt("penalty_subsequent_min", 3).coerceIn(0, 9999),
            timelapseRecordingEnabled = prefs.getBoolean("timelapse_recording_enabled", true)
        )
    }


    private fun normalizeSensitivityThresholds(drift: Float, motion: Float): Pair<Float, Float> {
        val presets = listOf(
            0.12f to 0.06f,
            0.10f to 0.04f,
            0.15f to 0.08f,
            0.17f to 0.09f
        )
        return presets.firstOrNull { (presetDrift, presetMotion) ->
            abs(presetDrift - drift) < 0.001f && abs(presetMotion - motion) < 0.001f
        } ?: (0.12f to 0.06f)
    }

    private fun applySettingsToEngines(settings: GameSettings) {
        synchronized(processingLock) {
            movementTracker.driftThresholdFactor = settings.driftThresholdFactor
            movementTracker.motionThresholdFactor = settings.motionThresholdFactor
        }
        faceDetectorService.setMinDetectionConfidence(settings.faceDetectionConfidence)
    }

    fun updateFaceCheckMode(mode: FaceCheckMode) {
        _gameSettings.value = _gameSettings.value.copy(faceCheckMode = mode)
        prefs.edit().putString("face_mode", mode.name).apply()
        synchronized(processingLock) { consecutiveFaceFailFrames = 0 }
    }

    fun updateFaceDetectionConfidence(value: Float) {
        val normalized = value.coerceIn(0.5f, 0.95f)
        _gameSettings.value = _gameSettings.value.copy(faceDetectionConfidence = normalized)
        prefs.edit().putFloat("face_conf", normalized).apply()
        faceDetectorService.setMinDetectionConfidence(normalized)
        synchronized(processingLock) { consecutiveFaceFailFrames = 0 }
    }

    fun updateDriftThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.05f, 0.40f)
        _gameSettings.value = _gameSettings.value.copy(driftThresholdFactor = normalized)
        prefs.edit().putFloat("pose_drift_factor_v2", normalized).apply()
        synchronized(processingLock) { movementTracker.driftThresholdFactor = normalized }
    }

    fun updateMotionThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.03f, 0.25f)
        _gameSettings.value = _gameSettings.value.copy(motionThresholdFactor = normalized)
        prefs.edit().putFloat("pose_motion_factor_v2", normalized).apply()
        synchronized(processingLock) { movementTracker.motionThresholdFactor = normalized }
    }


    fun updateMinimumPenaltyIntervalSeconds(value: Int) {
        val normalized = value.coerceIn(0, 30)
        _gameSettings.value = _gameSettings.value.copy(minimumPenaltyIntervalSeconds = normalized)
        prefs.edit().putInt("penalty_interval_sec", normalized).apply()
    }

    fun updateMaxViolations(value: Int) {
        val normalized = value.coerceIn(0, 9999)
        _gameSettings.value = _gameSettings.value.copy(maxViolations = normalized)
        prefs.edit().putInt("max_violations", normalized).apply()
    }


    fun updateLanguage(language: AppLanguage) {
        _gameSettings.value = _gameSettings.value.copy(language = language)
        prefs.edit().putString("app_language", language.name).apply()
        if (_gameState.value == GameState.Idle) {
            _statusMessage.value = tr(R.string.status_initial)
        }
    }

    fun updatePenaltiesEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(penaltiesEnabled = enabled)
        prefs.edit().putBoolean("penalties_enabled", enabled).apply()
    }

    fun updateTimelapseRecordingEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(timelapseRecordingEnabled = enabled)
        prefs.edit().putBoolean("timelapse_recording_enabled", enabled).apply()
    }

    fun updateFirstViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_1_min", value) { copy(firstViolationPenaltyMinutes = it) }
    fun updateSecondViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_2_min", value) { copy(secondViolationPenaltyMinutes = it) }
    fun updateThirdViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_3_min", value) { copy(thirdViolationPenaltyMinutes = it) }
    fun updateSubsequentViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_subsequent_min", value) { copy(subsequentViolationPenaltyMinutes = it) }

    private fun updatePenaltyMinutes(prefKey: String, value: Int, apply: GameSettings.(Int) -> GameSettings) {
        val normalized = value.coerceIn(0, 9999)
        _gameSettings.value = _gameSettings.value.apply(normalized)
        prefs.edit().putInt(prefKey, normalized).apply()
    }

    fun updateSelectedDurationMinutes(minutes: Int) {
        val normalizedMinutes = minutes.coerceIn(minimumDurationMinutes, maximumDurationMinutes)
        _selectedDurationSeconds.value = normalizedMinutes * 60
        if (_gameState.value == GameState.Idle || _gameState.value == GameState.Failed || _gameState.value == GameState.Success) _timerSeconds.value = _selectedDurationSeconds.value
    }

    fun registerCameraFrame(bitmap: Bitmap, timestampMs: Long) {
        val removedBitmaps = mutableListOf<Bitmap>()
        synchronized(frameLock) {
            val previous = pendingFrames.put(timestampMs, bitmap)
            if (previous != null && previous !== bitmap) {
                removedBitmaps.add(previous)
            }

            while (pendingFrames.size > 20) {
                val oldestTimestamp = pendingFrames.keys.first()
                val removed = pendingFrames.remove(oldestTimestamp)
                if (removed != null && removed !== bitmap) {
                    removedBitmaps.add(removed)
                }
            }

            val minTs = timestampMs - 3000
            val iterator = pendingFrames.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < minTs) {
                    val removed = entry.value
                    iterator.remove()
                    if (removed !== bitmap) {
                        removedBitmaps.add(removed)
                    }
                }
            }
        }
        removedBitmaps.forEach { it.recycleIfNeeded() }
    }

    fun dropCameraFrame(timestampMs: Long, recycle: Boolean) {
        val bitmap = synchronized(frameLock) { pendingFrames.remove(timestampMs) }
        if (recycle) bitmap?.recycleIfNeeded()
    }

    fun clearCameraFrameCache(recycle: Boolean) {
        val bitmaps = synchronized(frameLock) {
            val values = pendingFrames.values.toList()
            pendingFrames.clear()
            values
        }
        if (recycle) bitmaps.forEach { it.recycleIfNeeded() }
    }

    fun processMediaPipeResults(rawPose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        if (isCleared) return
        try {
            mediaPipeResultExecutor.execute {
                processMediaPipeResultsInternal(rawPose, timestamp, imageWidth, imageHeight)
            }
        } catch (e: RejectedExecutionException) {
            Log.w(tag, "Dropping MediaPipe result after executor shutdown", e)
        }
    }

    private fun processMediaPipeResultsInternal(rawPose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        if (isCleared) return
        val (frameGeneration, pose) = synchronized(processingLock) {
            processingGeneration to poseSmoother.smooth(rawPose, timestamp)
        }
        if (isCleared) return
        Log.v(tag, "MediaPipe frame ts=$timestamp size=${imageWidth}x$imageHeight landmarks=${pose.allLandmarks.size}")
        val matchedBitmap = synchronized(frameLock) { pendingFrames.remove(timestamp) }
        val nextOverlayState = try {
            if (matchedBitmap == null) {
                buildOverlayStateWithoutFace(pose, imageWidth, imageHeight)
            } else {
                buildOverlayState(matchedBitmap, pose, timestamp)
            }
        } finally {
            matchedBitmap?.recycleIfNeeded()
        }
        synchronized(processingLock) {
            if (processingGeneration != frameGeneration) return
            synchronized(frameLock) {
                latestAnalyzedFrame = AnalyzedPoseFrame(
                    pose = pose,
                    timestampMs = timestamp,
                    imageWidth = nextOverlayState.imageWidth,
                    imageHeight = nextOverlayState.imageHeight,
                    face = nextOverlayState.face
                )
            }
            _poseOverlayState.value = nextOverlayState
            if (_gameState.value != GameState.HoldingPose) return

            if (!pose.hasEnoughKeypoints()) {
                resetMovementGaugeState()
                val trackingResult = movementTracker.trackFrame(pose, timestamp)
                if (handleMovementViolation(trackingResult.violation, pose)) return
                processFaceRule(nextOverlayState.face.status, pose)
                return
            }

            val trackingResult = movementTracker.trackFrame(pose, timestamp)
            _movementGaugeState.value = MovementGaugeState(
                active = trackingResult.metrics.active,
                driftNormalizedScore = trackingResult.metrics.driftNormalizedScore,
                driftThresholdFactor = trackingResult.metrics.driftThresholdFactor,
                motionNormalizedScore = trackingResult.metrics.motionNormalizedScore,
                motionThresholdFactor = trackingResult.metrics.motionThresholdFactor
            )

            if (handleMovementViolation(trackingResult.violation, pose)) return

            processFaceRule(nextOverlayState.face.status, pose)
        }
    }

    private fun handleMovementViolation(violation: MovementTracker.Violation, pose: PoseLandmarks): Boolean {
        return when (violation) {
            is MovementTracker.Violation.DriftLimitExceeded -> handleRuleViolation(RuleViolationType.Drift, pose)
            is MovementTracker.Violation.MotionLimitExceeded -> handleRuleViolation(RuleViolationType.Motion, pose)
            is MovementTracker.Violation.PersonDisappeared -> handleRuleViolation(RuleViolationType.PersonDisappeared, pose)
            else -> false
        }
    }

    private fun processFaceRule(status: FaceDetectionStatus, pose: PoseLandmarks) {
        when (_gameSettings.value.faceCheckMode) {
            FaceCheckMode.Disabled -> return
            FaceCheckMode.FaceToCamera -> when (status) {
                FaceDetectionStatus.FaceVisible -> consecutiveFaceFailFrames = 0
                FaceDetectionStatus.FaceNotVisible -> checkFaceFail(pose)
                FaceDetectionStatus.NotProcessed, FaceDetectionStatus.Error -> Unit
            }
            FaceCheckMode.FaceAwayFromCamera -> when (status) {
                FaceDetectionStatus.FaceNotVisible -> consecutiveFaceFailFrames = 0
                FaceDetectionStatus.FaceVisible -> checkFaceFail(pose)
                FaceDetectionStatus.NotProcessed, FaceDetectionStatus.Error -> Unit
            }
        }
    }

    private fun checkFaceFail(pose: PoseLandmarks) {
        consecutiveFaceFailFrames += 1
        if (consecutiveFaceFailFrames >= faceFailFramesThreshold) {
            consecutiveFaceFailFrames = 0
            handleRuleViolation(RuleViolationType.FaceNotMatchingMode, pose)
        }
    }

    private fun handleRuleViolation(type: RuleViolationType, pose: PoseLandmarks): Boolean {
        val now = System.currentTimeMillis()
        if (lastPenaltyAtMs > 0L && now - lastPenaltyAtMs < _gameSettings.value.minimumPenaltyIntervalSeconds * 1000L) {
            return false
        }

        currentViolationCount += 1
        _violationCount.value = currentViolationCount
        updateRuleViolationCounts(type)
        if (currentViolationCount >= _gameSettings.value.maxViolations) {
            val defeatReason = when (type) {
                RuleViolationType.Drift -> tr(R.string.defeat_drift)
                RuleViolationType.Motion -> tr(R.string.defeat_motion)
                RuleViolationType.PersonDisappeared -> tr(R.string.defeat_disappeared)
                RuleViolationType.FaceNotMatchingMode -> if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera)
                    tr(R.string.defeat_face_not_to_camera)
                else tr(R.string.defeat_face_to_camera)
            }
            triggerDefeat(defeatReason, tr(R.string.defeat_try_again))
            return true
        }

        applyPenalty(type, penaltyMinutesForViolation(currentViolationCount))
        lastPenaltyAtMs = now
        return false
    }

    private fun updateRuleViolationCounts(type: RuleViolationType) {
        _ruleViolationCounts.value = when (type) {
            RuleViolationType.Drift -> _ruleViolationCounts.value.copy(drift = _ruleViolationCounts.value.drift + 1)
            RuleViolationType.Motion -> _ruleViolationCounts.value.copy(motion = _ruleViolationCounts.value.motion + 1)
            RuleViolationType.FaceNotMatchingMode -> _ruleViolationCounts.value.copy(face = _ruleViolationCounts.value.face + 1)
            RuleViolationType.PersonDisappeared -> _ruleViolationCounts.value
        }
    }

    private fun resetRuleViolationCounts() {
        _ruleViolationCounts.value = RuleViolationCounts()
    }

    private fun penaltyMinutesForViolation(violationIndex: Int): Int {
        val settings = _gameSettings.value
        return when (violationIndex) {
            1 -> settings.firstViolationPenaltyMinutes
            2 -> settings.secondViolationPenaltyMinutes
            3 -> settings.thirdViolationPenaltyMinutes
            else -> settings.subsequentViolationPenaltyMinutes
        }.coerceIn(0, 9999)
    }

    private fun applyPenalty(type: RuleViolationType, minutes: Int) {
        val sec = minutes * 60
        if (_gameSettings.value.penaltiesEnabled && sec > 0) {
            _timerSeconds.value += sec
            sessionActualTimerSeconds += sec
        }

        if (type == RuleViolationType.FaceNotMatchingMode) {
            val statusText = if (_gameSettings.value.penaltiesEnabled) {
                tr(R.string.face_rule_violated_with_penalty, minutes)
            } else {
                tr(R.string.face_rule_violated)
            }
            val prefix = if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera) {
                tr(R.string.you_turned_away)
            } else {
                tr(R.string.you_looked_at_camera)
            }
            _statusMessage.value = statusText
            if (_gameSettings.value.penaltiesEnabled) {
                speak("$prefix. ${tr(R.string.penalty_added_to_timer, minutes)}")
            } else {
                speak(prefix)
            }
            return
        }

        val voicePrefix = when (type) {
            RuleViolationType.Motion -> tr(R.string.motion_violation_voice)
            RuleViolationType.Drift -> tr(R.string.drift_violation_voice)
            else -> tr(R.string.violation_recorded)
        }

        if (_gameSettings.value.penaltiesEnabled) {
            _statusMessage.value = tr(R.string.violation_recorded_with_penalty, minutes)
            speak("$voicePrefix. ${tr(R.string.penalty_added_to_timer, minutes)}")
        } else {
            _statusMessage.value = tr(R.string.violation_recorded)
            speak(voicePrefix)
        }
    }

    private fun buildOverlayState(bitmap: Bitmap, pose: PoseLandmarks, timestamp: Long): PoseOverlayState { /* trimmed from old implementation */
        val cropRect = PoseFrameCropper.calculateCropRect(bitmap.width, bitmap.height, pose)
        val faceOverlayState = if (cropRect != null) {
            val faceCandidateRect = FaceCandidateCropper.calculateFaceCandidateRect(bitmap.width, bitmap.height, pose, cropRect)
            if (faceCandidateRect == null) FaceOverlayState(status = FaceDetectionStatus.NotProcessed, debugMessage = "face=NotProcessed no face candidate crop")
            else {
                var faceCropBitmap: Bitmap? = null
                try {
                    faceCropBitmap = Bitmap.createBitmap(bitmap, faceCandidateRect.left, faceCandidateRect.top, faceCandidateRect.width, faceCandidateRect.height)
                    val faceResult = faceDetectorService.detectOnCrop(faceCropBitmap)
                    val inputRect = PoseOverlayRect(faceCandidateRect.left / bitmap.width.toFloat(), faceCandidateRect.top / bitmap.height.toFloat(), faceCandidateRect.right / bitmap.width.toFloat(), faceCandidateRect.bottom / bitmap.height.toFloat())
                    if (faceResult.status == FaceDetectionStatus.FaceVisible && faceResult.boundingBox != null) {
                        val box = faceResult.boundingBox
                        FaceOverlayState(
                            status = FaceDetectionStatus.FaceVisible,
                            faceRect = PoseOverlayRect(
                                ((faceCandidateRect.left + box.leftPx) / bitmap.width).coerceIn(0f, 1f),
                                ((faceCandidateRect.top + box.topPx) / bitmap.height).coerceIn(0f, 1f),
                                ((faceCandidateRect.left + box.rightPx) / bitmap.width).coerceIn(0f, 1f),
                                ((faceCandidateRect.top + box.bottomPx) / bitmap.height).coerceIn(0f, 1f)
                            ),
                            keypoints = faceResult.keypoints.map { FaceOverlayPoint(((faceCandidateRect.left + it.x * faceCandidateRect.width) / bitmap.width).coerceIn(0f, 1f), ((faceCandidateRect.top + it.y * faceCandidateRect.height) / bitmap.height).coerceIn(0f, 1f)) },
                            score = faceResult.score,
                            detectorInputRect = inputRect,
                            detectorInputWidth = faceCropBitmap.width,
                            detectorInputHeight = faceCropBitmap.height,
                            debugMessage = "face=FaceVisible input=${faceCropBitmap.width}x${faceCropBitmap.height} score=${faceResult.score}"
                        )
                    } else {
                        FaceOverlayState(status = faceResult.status, score = faceResult.score, detectorInputRect = inputRect, detectorInputWidth = faceCropBitmap.width, detectorInputHeight = faceCropBitmap.height, debugMessage = "face=${faceResult.status} input=${faceCropBitmap.width}x${faceCropBitmap.height}")
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to prepare face crop", t)
                    FaceOverlayState(status = FaceDetectionStatus.Error, debugMessage = "face=Error input=0x0")
                } finally { faceCropBitmap?.recycleIfNeeded() }
            }
        } else FaceOverlayState(status = FaceDetectionStatus.NotProcessed, debugMessage = "face=NotProcessed no body crop")

        val normalizedRect = cropRect?.let { PoseOverlayRect(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height, it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height) }
        return PoseOverlayState(bitmap.width, bitmap.height, pose.allLandmarks, normalizedRect, faceOverlayState)
    }

    private fun buildOverlayStateWithoutFace(pose: PoseLandmarks, imageWidth: Int, imageHeight: Int): PoseOverlayState {
        val safeWidth = imageWidth.coerceAtLeast(0)
        val safeHeight = imageHeight.coerceAtLeast(0)
        val normalizedRect = if (safeWidth > 0 && safeHeight > 0) {
            PoseFrameCropper.calculateCropRect(safeWidth, safeHeight, pose)?.let {
                PoseOverlayRect(
                    it.left.toFloat() / safeWidth,
                    it.top.toFloat() / safeHeight,
                    it.right.toFloat() / safeWidth,
                    it.bottom.toFloat() / safeHeight
                )
            }
        } else {
            null
        }
        val faceOverlayState = FaceOverlayState(
            status = FaceDetectionStatus.NotProcessed,
            debugMessage = "face=NotProcessed no matched bitmap"
        )
        return PoseOverlayState(safeWidth, safeHeight, pose.allLandmarks, normalizedRect, faceOverlayState)
    }

    private suspend fun getFreshAnalyzedFrame(maxAgeMs: Long): AnalyzedPoseFrame? {
        val deadline = SystemClock.elapsedRealtime() + maxAgeMs
        while (SystemClock.elapsedRealtime() <= deadline) {
            val frame = synchronized(frameLock) { latestAnalyzedFrame }
            if (frame != null) {
                val ageMs = SystemClock.elapsedRealtime() - frame.timestampMs
                if (ageMs in 0..maxAgeMs) {
                    return frame
                }
            }
            delay(40)
        }
        return null
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_gameState.value == GameState.HoldingPose && _timerSeconds.value > 0) {
                delay(1000)
                if (_gameState.value != GameState.HoldingPose) return@launch
                val next = (_timerSeconds.value - 1).coerceAtLeast(0)
                _timerSeconds.value = next
            }
            if (_gameState.value == GameState.HoldingPose && _timerSeconds.value <= 0) {
                _sessionSummary.value = SessionSummary(
                    result = GameState.Success,
                    initialTimerSeconds = sessionInitialTimerSeconds,
                    actualTimerSeconds = sessionActualTimerSeconds,
                    violationCounts = _ruleViolationCounts.value,
                    settings = sessionSettingsSnapshot
                )
                _gameState.value = GameState.Success
                resetMovementGaugeState()
                _statusMessage.value = tr(R.string.victory)
                speak(tr(R.string.time_is_up))
            }
        }
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return

        _sessionSummary.value = null
        sessionInitialTimerSeconds = _selectedDurationSeconds.value
        sessionActualTimerSeconds = _selectedDurationSeconds.value
        sessionSettingsSnapshot = _gameSettings.value
        _timerSeconds.value = _selectedDurationSeconds.value
        _defeatReason.value = ""
        resetMovementGaugeState()
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            movementTracker.reset()
            poseSmoother.reset()
            currentViolationCount = 0
            _violationCount.value = 0
            resetRuleViolationCounts()
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
        }
        startDelayJob?.cancel()
        timerJob?.cancel()

        if (gyroscopeSensor == null) {
            triggerDefeat(tr(R.string.gyroscope_unavailable), tr(R.string.gyroscope_unavailable))
            return
        }

        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _gameState.value = GameState.WaitingForStabilization
        _statusMessage.value = tr(R.string.place_device_still)
        speak(tr(R.string.place_device_still))
        sensorManager.unregisterListener(this)
        sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun startPoseCountdownAfterDeviceStabilized() {
        speak(tr(R.string.take_position))
        _gameState.value = GameState.StartingDelay
        startDelayJob?.cancel()
        startDelayJob = viewModelScope.launch {
            for (i in startDelaySeconds downTo 1) {
                _startDelayRemainingSeconds.value = i
                _statusMessage.value = tr(R.string.start_in_pose, i)
                delay(1000)
            }
            _startDelayRemainingSeconds.value = 0
            val analyzedFrame = getFreshAnalyzedFrame(1200)
            val initialPose = analyzedFrame?.pose
            if (analyzedFrame == null) { triggerDefeat(tr(R.string.camera_no_frame)); return@launch }
            if (initialPose == null || !initialPose.hasEnoughKeypoints()) { triggerDefeat(tr(R.string.camera_no_body)); return@launch }
            _timerSeconds.value = sessionInitialTimerSeconds
            synchronized(processingLock) {
                processingGeneration += 1
                movementTracker.reset()
                currentViolationCount = 0
                _violationCount.value = 0
                resetRuleViolationCounts()
                lastPenaltyAtMs = 0L
                consecutiveFaceFailFrames = 0
                movementTracker.startTracking(initialPose)
            }
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = tr(R.string.time_started_hold_position)
            startTimerLoop()
            speak(tr(R.string.time_started_hold_position))
        }
    }

    private fun completeDeviceStabilization() {
        if (_gameState.value != GameState.WaitingForStabilization || stabilizationCompleted) return
        stabilizationCompleted = true
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        startPoseCountdownAfterDeviceStabilized()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (_gameState.value != GameState.WaitingForStabilization) return

        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )

        val now = SystemClock.elapsedRealtime()
        if (magnitude <= gyroscopeStillThresholdRadPerSec) {
            val stableSince = stabilizationStableSinceMs ?: now.also { stabilizationStableSinceMs = it }
            if (now - stableSince >= stabilizationDurationMs) {
                viewModelScope.launch {
                    completeDeviceStabilization()
                }
            }
        } else {
            stabilizationStableSinceMs = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun resetMovementGaugeState() { _movementGaugeState.value = MovementGaugeState() }

    fun triggerDefeat(reason: String, voiceMessage: String = tr(R.string.defeat_try_again)) {
        val alreadyFailed = _gameState.value == GameState.Failed
        startDelayJob?.cancel()
        timerJob?.cancel()
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseSmoother.reset()
        }
        resetMovementGaugeState()
        _sessionSummary.value = SessionSummary(
            result = GameState.Failed,
            initialTimerSeconds = sessionInitialTimerSeconds,
            actualTimerSeconds = sessionActualTimerSeconds,
            violationCounts = _ruleViolationCounts.value,
            settings = sessionSettingsSnapshot,
            defeatReason = reason
        )
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = tr(R.string.check_failed)
        if (!alreadyFailed) speak(voiceMessage)
    }
    private fun speak(text: String) { _voiceEvents.tryEmit(text) }

    fun stopSession() {
        startDelayJob?.cancel()
        timerJob?.cancel()
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseSmoother.reset()
            movementTracker.reset()
            currentViolationCount = 0
            _violationCount.value = 0
            resetRuleViolationCounts()
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
        }
        _sessionSummary.value = null
        resetMovementGaugeState()
        _gameState.value = GameState.Idle
        _statusMessage.value = tr(R.string.status_initial)
        _defeatReason.value = ""
        _timerSeconds.value = _selectedDurationSeconds.value
    }
    override fun onCleared() { isCleared = true; sensorManager.unregisterListener(this); stabilizationStableSinceMs = null; stabilizationCompleted = false; synchronized(processingLock) { processingGeneration += 1; poseSmoother.reset() }; clearCameraFrameCache(recycle = true); mediaPipeResultExecutor.shutdownNow(); runCatching { mediaPipeResultExecutor.awaitTermination(200, TimeUnit.MILLISECONDS) }; faceDetectorService.close(); super.onCleared() }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}
