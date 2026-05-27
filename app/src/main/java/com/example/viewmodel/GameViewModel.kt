package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

enum class GameState {
    Idle,
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

data class GameSettings(
    val faceCheckMode: FaceCheckMode = FaceCheckMode.Disabled,
    val faceDetectionConfidence: Float = 0.8f,
    val driftThresholdFactor: Float = 0.46f,
    val motionThresholdFactor: Float = 0.32f
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

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "GameViewModel"
    private val minimumDurationSeconds = 180
    private val startDelaySeconds = 10

    private val prefs: SharedPreferences = application.getSharedPreferences("game_settings", Context.MODE_PRIVATE)

    private val _gameState = MutableStateFlow(GameState.Idle)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()
    private val _timerSeconds = MutableStateFlow(minimumDurationSeconds)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()
    private val _selectedDurationSeconds = MutableStateFlow(minimumDurationSeconds)
    val selectedDurationSeconds: StateFlow<Int> = _selectedDurationSeconds.asStateFlow()
    private val _statusMessage = MutableStateFlow("Поставь телефон и встань в позу")
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
    private val penaltyCooldownMs = 3000L
    private var consecutiveFaceFailFrames = 0
    private val faceFailFramesThreshold = 5

    private enum class RuleViolationType { Drift, Motion, PersonDisappeared, FaceNotMatchingMode }

    init {
        applySettingsToEngines(_gameSettings.value)
    }

    private fun loadSettings(): GameSettings {
        val mode = runCatching { FaceCheckMode.valueOf(prefs.getString("face_mode", FaceCheckMode.Disabled.name) ?: FaceCheckMode.Disabled.name) }
            .getOrDefault(FaceCheckMode.Disabled)
        return GameSettings(
            faceCheckMode = mode,
            faceDetectionConfidence = prefs.getFloat("face_conf", 0.8f).coerceIn(0.5f, 0.95f),
            driftThresholdFactor = prefs.getFloat("drift_factor", 0.46f).coerceIn(0.1f, 0.8f),
            motionThresholdFactor = prefs.getFloat("motion_factor", 0.32f).coerceIn(0.1f, 0.8f)
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
        if (lastPenaltyAtMs > 0L && now - lastPenaltyAtMs < penaltyCooldownMs) {
            if (_gameState.value == GameState.HoldingPose) movementTracker.startTracking(pose)
            return false
        }

        violationCount += 1
        if (violationCount >= 4) {
            val defeatReason = when (type) {
                RuleViolationType.Drift -> "Пользователь сильно сдвинулся"
                RuleViolationType.Motion -> "Пользователь резко двинулся"
                RuleViolationType.PersonDisappeared -> "Человек пропал из кадра"
                RuleViolationType.FaceNotMatchingMode -> if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera)
                    "Лицо не было направлено в камеру" else "Лицо было направлено в камеру"
            }
            triggerDefeat(defeatReason, "Вы не справились. Попробуйте снова")
            return true
        }

        when (violationCount) {
            1 -> applyPenalty(type, 60)
            2 -> applyPenalty(type, 180)
            3 -> applyPenalty(type, 180)
        }
        lastPenaltyAtMs = now
        if (_gameState.value == GameState.HoldingPose) movementTracker.startTracking(pose)
        return false
    }

    private fun applyPenalty(type: RuleViolationType, sec: Int) {
        _timerSeconds.value += sec
        if (type == RuleViolationType.FaceNotMatchingMode) {
            val statusText = if (sec == 60) {
                "Нарушено условие положения лица: +1 минута"
            } else {
                "Нарушено условие положения лица: +3 минуты"
            }
            val prefix = if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera) {
                "Вы отвернулись"
            } else {
                "Вы посмотрели в камеру"
            }
            val sanction = if (sec == 60) {
                "Плюс 1 минута к таймеру"
            } else {
                "Плюс 3 минуты к таймеру"
            }
            _statusMessage.value = statusText
            speak("$prefix. $sanction")
            return
        }

        when (sec) {
            60 -> {
                _statusMessage.value = "Зафиксировано движение: +1 минута"
                speak("Вы двинулись. Плюс 1 минута к таймеру")
            }

            else -> {
                val isSecondViolation = violationCount == 2
                _statusMessage.value = if (isSecondViolation) {
                    "Зафиксировано повторное движение: +3 минуты"
                } else {
                    "Зафиксировано третье движение: +3 минуты"
                }
                speak("Вы снова двинулись. Плюс 3 минуты к таймеру")
            }
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
        speak("Займите позицию")
        _defeatReason.value = ""; _driftScore.value = 0f; _motionScore.value = 0f; _startDelayRemainingSeconds.value = 0
        movementTracker.reset(); violationCount = 0; lastPenaltyAtMs = 0L; consecutiveFaceFailFrames = 0
        startDelayJob?.cancel(); timerJob?.cancel()
        startDelayJob = viewModelScope.launch {
            _gameState.value = GameState.StartingDelay
            for (seconds in startDelaySeconds downTo 1) { _startDelayRemainingSeconds.value = seconds; _statusMessage.value = "Старт через $seconds сек. Прими позу"; delay(1000) }
            _startDelayRemainingSeconds.value = 0
            val analyzedFrame = getFreshAnalyzedFrame(); val initialPose = analyzedFrame?.pose
            if (analyzedFrame == null) { triggerDefeat("Камера не предоставила свежий синхронизированный кадр"); return@launch }
            if (initialPose == null || !initialPose.hasEnoughKeypoints()) { triggerDefeat("Камера не видит тело. Встань полностью в кадр."); return@launch }
            _timerSeconds.value = _selectedDurationSeconds.value.coerceAtLeast(minimumDurationSeconds)
            movementTracker.reset(); violationCount = 0; lastPenaltyAtMs = 0L; consecutiveFaceFailFrames = 0
            movementTracker.startTracking(initialPose)
            _gameState.value = GameState.HoldingPose; _statusMessage.value = "Таймер запущен. Удерживай позу"; startTimerLoop(); speak("Время пошло. Удерживайте позицию")
        }
    }

    private fun getFreshAnalyzedFrame(maxAgeMs: Long = 1000L): AnalyzedPoseFrame? {
        val now = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        return synchronized(frameLock) { latestAnalyzedFrame?.takeIf { now - it.timestampMs <= maxAgeMs } }
    }

    private fun startTimerLoop() { timerJob?.cancel(); timerJob = viewModelScope.launch { while (_timerSeconds.value > 0 && _gameState.value == GameState.HoldingPose) { delay(1000); if (_gameState.value != GameState.HoldingPose) break; _timerSeconds.value -= 1; _statusMessage.value = "Осталось: ${formatTime(_timerSeconds.value)}" }; if (_timerSeconds.value <= 0 && _gameState.value == GameState.HoldingPose) { _gameState.value = GameState.Success; _statusMessage.value = "Победа"; speak("Время вышло") } } }

    fun triggerDefeat(reason: String, voiceMessage: String = "Вы не справились. Попробуйте снова") { val alreadyFailed = _gameState.value == GameState.Failed; startDelayJob?.cancel(); timerJob?.cancel(); _startDelayRemainingSeconds.value = 0; _gameState.value = GameState.Failed; _defeatReason.value = reason; _statusMessage.value = "Проверка не пройдена"; if (!alreadyFailed) speak(voiceMessage) }
    private fun speak(text: String) { _voiceEvents.tryEmit(text) }

    fun stopSession() { startDelayJob?.cancel(); timerJob?.cancel(); _startDelayRemainingSeconds.value = 0; _gameState.value = GameState.Idle; _statusMessage.value = "Поставь телефон и встань в позу"; _defeatReason.value = ""; _driftScore.value = 0f; _motionScore.value = 0f; _timerSeconds.value = _selectedDurationSeconds.value; movementTracker.reset(); violationCount = 0; lastPenaltyAtMs = 0L; consecutiveFaceFailFrames = 0 }
    override fun onCleared() { faceDetectorService.close(); super.onCleared() }
    private fun calculateSingleDisplacement(p1: PoseLandmarks, p2: PoseLandmarks): Float { var total = 0f; var count = 0; fun add(a: Point3D?, b: Point3D?) { if (a != null && b != null) { total += a.distanceTo(b); count++ } }; add(p1.leftShoulder, p2.leftShoulder); add(p1.rightShoulder, p2.rightShoulder); add(p1.leftHip, p2.leftHip); add(p1.rightHip, p2.rightHip); return if (count > 0) total / count else 0f }
    private fun formatTime(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)
}
