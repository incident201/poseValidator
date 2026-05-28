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
    val driftThresholdFactor: Float = 0.46f,
    val motionThresholdFactor: Float = 0.32f,
    val minimumPenaltyIntervalSeconds: Int = 5,
    val maxViolations: Int = 4,
    val penaltiesEnabled: Boolean = true,
    val firstViolationPenaltyMinutes: Int = 1,
    val secondViolationPenaltyMinutes: Int = 3,
    val thirdViolationPenaltyMinutes: Int = 3,
    val subsequentViolationPenaltyMinutes: Int = 3
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

class GameViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {
    private val tag = "GameViewModel"
    private val minimumDurationSeconds = 180
    private val startDelaySeconds = 10
    private val stabilizationDurationMs = 3_000L
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
    private val _timerSeconds = MutableStateFlow(minimumDurationSeconds)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()
    private val _selectedDurationSeconds = MutableStateFlow(minimumDurationSeconds)
    val selectedDurationSeconds: StateFlow<Int> = _selectedDurationSeconds.asStateFlow()
    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    private val _defeatReason = MutableStateFlow("")
    val defeatReason: StateFlow<String> = _defeatReason.asStateFlow()
    private val _driftScore = MutableStateFlow(0f)
    val driftScore: StateFlow<Float> = _driftScore.asStateFlow()
    private val _motionScore = MutableStateFlow(0f)
    val motionScore: StateFlow<Float> = _motionScore.asStateFlow()
    private val _driftThreshold = MutableStateFlow(0.075f)
    val driftThreshold: StateFlow<Float> = _driftThreshold.asStateFlow()
    private val _motionThreshold = MutableStateFlow(0.054f)
    val motionThreshold: StateFlow<Float> = _motionThreshold.asStateFlow()
    private val _startDelayRemainingSeconds = MutableStateFlow(0)
    val startDelayRemainingSeconds: StateFlow<Int> = _startDelayRemainingSeconds.asStateFlow()
    private val _poseOverlayState = MutableStateFlow(PoseOverlayState())
    val poseOverlayState: StateFlow<PoseOverlayState> = _poseOverlayState.asStateFlow()
    private val _voiceEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val voiceEvents: SharedFlow<String> = _voiceEvents.asSharedFlow()
    private val _gameSettings = MutableStateFlow(loadSettings())
    val gameSettings: StateFlow<GameSettings> = _gameSettings.asStateFlow()

    private data class AnalyzedPoseFrame(val bitmap: Bitmap, val pose: PoseLandmarks, val timestampMs: Long, val face: FaceOverlayState)

    private val faceDetectorService = FaceDetectorService(application.applicationContext, _gameSettings.value.faceDetectionConfidence)
    private val movementTracker = MovementTracker()

    private val frameLock = Any()
    private val pendingFrames = LinkedHashMap<Long, Bitmap>()
    private var latestAnalyzedFrame: AnalyzedPoseFrame? = null
    private var startDelayJob: Job? = null
    private var timerJob: Job? = null

    private var violationCount = 0
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
        return GameSettings(
            language = language,
            faceCheckMode = mode,
            faceDetectionConfidence = prefs.getFloat("face_conf", 0.8f).coerceIn(0.5f, 0.95f),
            driftThresholdFactor = prefs.getFloat("drift_factor", 0.46f).coerceIn(0.1f, 0.8f),
            motionThresholdFactor = prefs.getFloat("motion_factor", 0.32f).coerceIn(0.1f, 0.8f),
            minimumPenaltyIntervalSeconds = prefs.getInt("penalty_interval_sec", 5).coerceIn(0, 30),
            maxViolations = prefs.getInt("max_violations", 4).coerceIn(0, 9999),
            penaltiesEnabled = prefs.getBoolean("penalties_enabled", true),
            firstViolationPenaltyMinutes = prefs.getInt("penalty_1_min", 1).coerceIn(0, 9999),
            secondViolationPenaltyMinutes = prefs.getInt("penalty_2_min", 3).coerceIn(0, 9999),
            thirdViolationPenaltyMinutes = prefs.getInt("penalty_3_min", 3).coerceIn(0, 9999),
            subsequentViolationPenaltyMinutes = prefs.getInt("penalty_subsequent_min", 3).coerceIn(0, 9999)
        )
    }

    private fun applySettingsToEngines(settings: GameSettings) {
        movementTracker.driftThresholdFactor = settings.driftThresholdFactor
        movementTracker.motionThresholdFactor = settings.motionThresholdFactor
        faceDetectorService.setMinDetectionConfidence(settings.faceDetectionConfidence)
    }

    fun updateFaceCheckMode(mode: FaceCheckMode) {
        _gameSettings.value = _gameSettings.value.copy(faceCheckMode = mode)
        prefs.edit().putString("face_mode", mode.name).apply()
        consecutiveFaceFailFrames = 0
    }

    fun updateFaceDetectionConfidence(value: Float) {
        val normalized = value.coerceIn(0.5f, 0.95f)
        _gameSettings.value = _gameSettings.value.copy(faceDetectionConfidence = normalized)
        prefs.edit().putFloat("face_conf", normalized).apply()
        faceDetectorService.setMinDetectionConfidence(normalized)
        consecutiveFaceFailFrames = 0
    }

    fun updateDriftThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.1f, 0.8f)
        _gameSettings.value = _gameSettings.value.copy(driftThresholdFactor = normalized)
        prefs.edit().putFloat("drift_factor", normalized).apply()
        movementTracker.driftThresholdFactor = normalized
    }

    fun updateMotionThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.1f, 0.8f)
        _gameSettings.value = _gameSettings.value.copy(motionThresholdFactor = normalized)
        prefs.edit().putFloat("motion_factor", normalized).apply()
        movementTracker.motionThresholdFactor = normalized
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

    fun updateFirstViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_1_min", value) { copy(firstViolationPenaltyMinutes = it) }
    fun updateSecondViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_2_min", value) { copy(secondViolationPenaltyMinutes = it) }
    fun updateThirdViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_3_min", value) { copy(thirdViolationPenaltyMinutes = it) }
    fun updateSubsequentViolationPenaltyMinutes(value: Int) = updatePenaltyMinutes("penalty_subsequent_min", value) { copy(subsequentViolationPenaltyMinutes = it) }

    private fun updatePenaltyMinutes(prefKey: String, value: Int, apply: GameSettings.(Int) -> GameSettings) {
        val normalized = value.coerceIn(0, 9999)
        _gameSettings.value = _gameSettings.value.apply(normalized)
        prefs.edit().putInt(prefKey, normalized).apply()
    }

    fun updateSelectedDurationMinutes(minutes: Int) { /* unchanged behavior */
        val normalizedMinutes = minutes.coerceAtLeast(3)
        _selectedDurationSeconds.value = normalizedMinutes * 60
        if (_gameState.value == GameState.Idle || _gameState.value == GameState.Failed || _gameState.value == GameState.Success) _timerSeconds.value = _selectedDurationSeconds.value
    }

    fun registerCameraFrame(bitmap: Bitmap, timestampMs: Long) {
        synchronized(frameLock) {
            pendingFrames[timestampMs] = bitmap
            while (pendingFrames.size > 20) pendingFrames.remove(pendingFrames.keys.first())
            val minTs = timestampMs - 3000
            pendingFrames.keys.iterator().apply { while (hasNext()) if (next() < minTs) remove() }
        }
    }

    fun processMediaPipeResults(pose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        Log.v(tag, "MediaPipe frame ts=$timestamp size=${imageWidth}x$imageHeight landmarks=${pose.allLandmarks.size}")
        val matchedBitmap = synchronized(frameLock) { pendingFrames.remove(timestamp) }
        val nextOverlayState = if (matchedBitmap == null) PoseOverlayState() else buildOverlayState(matchedBitmap, pose, timestamp)
        _poseOverlayState.value = nextOverlayState

        if (_gameState.value != GameState.HoldingPose) return

        val scale = pose.getBodyScale()
        _driftThreshold.value = movementTracker.driftThresholdFactor * scale
        _motionThreshold.value = movementTracker.motionThresholdFactor * scale

        val violation = movementTracker.trackFrame(pose, timestamp)
        movementTracker.referencePose?.let { _driftScore.value = calculateSingleDisplacement(pose, it) }
        movementTracker.previousPose?.let { _motionScore.value = calculateSingleDisplacement(pose, it) }

        when (violation) {
            is MovementTracker.Violation.DriftLimitExceeded -> if (handleRuleViolation(RuleViolationType.Drift, pose)) return
            is MovementTracker.Violation.MotionLimitExceeded -> if (handleRuleViolation(RuleViolationType.Motion, pose)) return
            is MovementTracker.Violation.PersonDisappeared -> if (handleRuleViolation(RuleViolationType.PersonDisappeared, pose)) return
            else -> Unit
        }

        processFaceRule(nextOverlayState.face.status, pose)
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
            if (_gameState.value == GameState.HoldingPose) movementTracker.startTracking(pose)
            return false
        }

        violationCount += 1
        if (violationCount >= _gameSettings.value.maxViolations) {
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

        applyPenalty(type, penaltyMinutesForViolation(violationCount))
        lastPenaltyAtMs = now
        if (_gameState.value == GameState.HoldingPose) movementTracker.startTracking(pose)
        return false
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
        if (_gameSettings.value.penaltiesEnabled) {
            _timerSeconds.value += sec
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

        if (_gameSettings.value.penaltiesEnabled) {
            _statusMessage.value = tr(R.string.violation_recorded_with_penalty, minutes)
            speak("${tr(R.string.violation_recorded)}. ${tr(R.string.penalty_added_to_timer, minutes)}")
        } else {
            _statusMessage.value = tr(R.string.violation_recorded)
            speak(tr(R.string.violation_recorded))
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
                } finally { faceCropBitmap?.recycle() }
            }
        } else FaceOverlayState(status = FaceDetectionStatus.NotProcessed, debugMessage = "face=NotProcessed no body crop")

        synchronized(frameLock) { latestAnalyzedFrame = AnalyzedPoseFrame(bitmap, pose, timestamp, faceOverlayState) }
        val normalizedRect = cropRect?.let { PoseOverlayRect(it.left.toFloat() / bitmap.width, it.top.toFloat() / bitmap.height, it.right.toFloat() / bitmap.width, it.bottom.toFloat() / bitmap.height) }
        return PoseOverlayState(bitmap.width, bitmap.height, pose.allLandmarks, normalizedRect, faceOverlayState)
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return

        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        _startDelayRemainingSeconds.value = 0
        movementTracker.reset()
        violationCount = 0
        lastPenaltyAtMs = 0L
        consecutiveFaceFailFrames = 0
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
            _timerSeconds.value = _selectedDurationSeconds.value
            movementTracker.reset()
            violationCount = 0
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
            movementTracker.startTracking(initialPose)
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = tr(R.string.remaining_time, formatTime(_timerSeconds.value))
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

    fun triggerDefeat(reason: String, voiceMessage: String = tr(R.string.defeat_try_again)) { val alreadyFailed = _gameState.value == GameState.Failed; startDelayJob?.cancel(); timerJob?.cancel(); sensorManager.unregisterListener(this); stabilizationStableSinceMs = null; stabilizationCompleted = false; _startDelayRemainingSeconds.value = 0; _gameState.value = GameState.Failed; _defeatReason.value = reason; _statusMessage.value = tr(R.string.check_failed); if (!alreadyFailed) speak(voiceMessage) }
    private fun speak(text: String) { _voiceEvents.tryEmit(text) }

    fun stopSession() { startDelayJob?.cancel(); timerJob?.cancel(); sensorManager.unregisterListener(this); stabilizationStableSinceMs = null; stabilizationCompleted = false; _startDelayRemainingSeconds.value = 0; _gameState.value = GameState.Idle; _statusMessage.value = tr(R.string.status_initial); _defeatReason.value = ""; _driftScore.value = 0f; _motionScore.value = 0f; _timerSeconds.value = _selectedDurationSeconds.value; movementTracker.reset(); violationCount = 0; lastPenaltyAtMs = 0L; consecutiveFaceFailFrames = 0 }
    override fun onCleared() { sensorManager.unregisterListener(this); stabilizationStableSinceMs = null; stabilizationCompleted = false; faceDetectorService.close(); super.onCleared() }
    private fun calculateSingleDisplacement(p1: PoseLandmarks, p2: PoseLandmarks): Float { var total = 0f; var count = 0; fun add(a: Point3D?, b: Point3D?) { if (a != null && b != null) { total += a.distanceTo(b); count++ } }; add(p1.leftShoulder, p2.leftShoulder); add(p1.rightShoulder, p2.rightShoulder); add(p1.leftHip, p2.leftHip); add(p1.rightHip, p2.rightHip); return if (count > 0) total / count else 0f }
    private fun formatTime(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)
}
