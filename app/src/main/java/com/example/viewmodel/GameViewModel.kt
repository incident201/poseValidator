package com.example.viewmodel

import android.app.Application
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

data class FaceOverlayPoint(
    val x: Float,
    val y: Float
)

data class FaceOverlayState(
    val status: FaceDetectionStatus = FaceDetectionStatus.NotProcessed,
    val faceRect: PoseOverlayRect? = null,
    val keypoints: List<FaceOverlayPoint> = emptyList(),
    val score: Float = 0f,
    val detectorInputRect: PoseOverlayRect? = null,
    val detectorInputWidth: Int = 0,
    val detectorInputHeight: Int = 0,
    val debugMessage: String = ""
) {
    val isFacingCamera: Boolean
        get() = status == FaceDetectionStatus.FaceVisible

    val hasProcessedFaceDetection: Boolean
        get() = status == FaceDetectionStatus.FaceVisible ||
            status == FaceDetectionStatus.FaceNotVisible
}

data class PoseOverlayState(
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val landmarks: List<Point3D> = emptyList(),
    val cropRect: PoseOverlayRect? = null,
    val face: FaceOverlayState = FaceOverlayState()
)

data class PoseOverlayRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "GameViewModel"
    private val minimumDurationSeconds = 180
    private val startDelaySeconds = 10

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

    private data class AnalyzedPoseFrame(
        val bitmap: Bitmap,
        val pose: PoseLandmarks,
        val timestampMs: Long,
        val face: FaceOverlayState
    )

    private val faceDetectorService = FaceDetectorService(application.applicationContext)

    private val frameLock = Any()
    private val pendingFrames = LinkedHashMap<Long, Bitmap>()
    private var latestAnalyzedFrame: AnalyzedPoseFrame? = null
    private var latestLandmarks: PoseLandmarks? = null
    private val movementTracker = MovementTracker()
    private var startDelayJob: Job? = null
    private var timerJob: Job? = null
    private var movementViolationCount: Int = 0
    private var lastMovementPenaltyAtMs: Long = 0L
    private val movementPenaltyCooldownMs: Long = 3000L

    private fun getFreshAnalyzedFrame(maxAgeMs: Long = 1000L): AnalyzedPoseFrame? {
        val now = SystemClock.elapsedRealtimeNanos() / 1_000_000L
        return synchronized(frameLock) {
            latestAnalyzedFrame?.takeIf { now - it.timestampMs <= maxAgeMs }
        }
    }

    init {
        _gameState.value = GameState.Idle
        _statusMessage.value = "Поставь телефон и встань в позу"
    }

    fun updateSelectedDurationMinutes(minutes: Int) {
        val normalizedMinutes = minutes.coerceAtLeast(3)
        _selectedDurationSeconds.value = normalizedMinutes * 60
        if (_gameState.value == GameState.Idle || _gameState.value == GameState.Failed || _gameState.value == GameState.Success) {
            _timerSeconds.value = _selectedDurationSeconds.value
        }
    }
    fun registerCameraFrame(bitmap: Bitmap, timestampMs: Long) {
        synchronized(frameLock) {
            pendingFrames[timestampMs] = bitmap
            while (pendingFrames.size > 20) {
                val firstKey = pendingFrames.keys.firstOrNull() ?: break
                pendingFrames.remove(firstKey)
            }
            val minTimestampToKeep = timestampMs - 3000
            val iterator = pendingFrames.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key < minTimestampToKeep) iterator.remove()
            }
        }
    }

    fun processMediaPipeResults(pose: PoseLandmarks, timestamp: Long, imageWidth: Int, imageHeight: Int) {
        latestLandmarks = pose
        Log.v(TAG, "MediaPipe frame ts=$timestamp size=${imageWidth}x$imageHeight landmarks=${pose.allLandmarks.size}")

        var matchedBitmap: Bitmap? = null
        synchronized(frameLock) {
            matchedBitmap = pendingFrames.remove(timestamp)
        }

        val nextOverlayState = if (matchedBitmap == null) {
            PoseOverlayState()
        } else {
            val bitmap = matchedBitmap!!
            val cropRect = PoseFrameCropper.calculateCropRect(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                pose = pose
            )

            val faceOverlayState = if (cropRect != null) {
                val faceCandidateRect = FaceCandidateCropper.calculateFaceCandidateRect(
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    pose = pose,
                    bodyCropRect = cropRect
                )
                if (faceCandidateRect == null) {
                    FaceOverlayState(
                        status = FaceDetectionStatus.NotProcessed,
                        debugMessage = "face=NotProcessed no face candidate crop"
                    )
                } else {
                    var faceCropBitmap: Bitmap? = null
                    try {
                        faceCropBitmap = Bitmap.createBitmap(
                            bitmap,
                            faceCandidateRect.left,
                            faceCandidateRect.top,
                            faceCandidateRect.width,
                            faceCandidateRect.height
                        )
                        val faceResult = faceDetectorService.detectOnCrop(faceCropBitmap)
                        val inputRect = PoseOverlayRect(
                            left = faceCandidateRect.left / bitmap.width.toFloat(),
                            top = faceCandidateRect.top / bitmap.height.toFloat(),
                            right = faceCandidateRect.right / bitmap.width.toFloat(),
                            bottom = faceCandidateRect.bottom / bitmap.height.toFloat()
                        )
                        val faceState = if (faceResult.status == FaceDetectionStatus.FaceVisible && faceResult.boundingBox != null) {
                            val faceBox = faceResult.boundingBox
                            val leftNorm = ((faceCandidateRect.left + faceBox.leftPx) / bitmap.width).coerceIn(0f, 1f)
                            val topNorm = ((faceCandidateRect.top + faceBox.topPx) / bitmap.height).coerceIn(0f, 1f)
                            val rightNorm = ((faceCandidateRect.left + faceBox.rightPx) / bitmap.width).coerceIn(0f, 1f)
                            val bottomNorm = ((faceCandidateRect.top + faceBox.bottomPx) / bitmap.height).coerceIn(0f, 1f)
                            val mappedKeypoints = faceResult.keypoints.map { keypoint ->
                                FaceOverlayPoint(
                                    x = ((faceCandidateRect.left + keypoint.x * faceCandidateRect.width) / bitmap.width).coerceIn(0f, 1f),
                                    y = ((faceCandidateRect.top + keypoint.y * faceCandidateRect.height) / bitmap.height).coerceIn(0f, 1f)
                                )
                            }
                            FaceOverlayState(
                                status = FaceDetectionStatus.FaceVisible,
                                faceRect = PoseOverlayRect(leftNorm, topNorm, rightNorm, bottomNorm),
                                keypoints = mappedKeypoints,
                                score = faceResult.score,
                                detectorInputRect = inputRect,
                                detectorInputWidth = faceCropBitmap.width,
                                detectorInputHeight = faceCropBitmap.height,
                                debugMessage = "face=FaceVisible input=${faceCropBitmap.width}x${faceCropBitmap.height} score=${faceResult.score}"
                            )
                        } else {
                            val errorSuffix = faceResult.errorMessage
                                .takeIf { it.isNotBlank() }
                                ?.let { " $it" }
                                ?: ""
                            val debugMessage = when (faceResult.status) {
                                FaceDetectionStatus.FaceNotVisible -> "face=FaceNotVisible input=${faceCropBitmap.width}x${faceCropBitmap.height}"
                                FaceDetectionStatus.Error -> "face=Error input=${faceCropBitmap.width}x${faceCropBitmap.height}$errorSuffix"
                                FaceDetectionStatus.NotProcessed -> "face=NotProcessed input=${faceCropBitmap.width}x${faceCropBitmap.height}"
                                FaceDetectionStatus.FaceVisible -> "face=FaceVisible input=${faceCropBitmap.width}x${faceCropBitmap.height} score=${faceResult.score}"
                            }
                            FaceOverlayState(
                                status = faceResult.status,
                                score = faceResult.score,
                                detectorInputRect = inputRect,
                                detectorInputWidth = faceCropBitmap.width,
                                detectorInputHeight = faceCropBitmap.height,
                                debugMessage = debugMessage
                            )
                        }
                        Log.d(TAG, "Face detection: ${faceState.debugMessage} candidate=$faceCandidateRect body=$cropRect faceRect=${faceState.faceRect}")
                        faceState
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to prepare face crop", t)
                        FaceOverlayState(
                            status = FaceDetectionStatus.Error,
                            debugMessage = "face=Error input=0x0"
                        )
                    } finally {
                        faceCropBitmap?.recycle()
                    }
                }
            } else {
                FaceOverlayState(
                    status = FaceDetectionStatus.NotProcessed,
                    debugMessage = "face=NotProcessed no body crop"
                )
            }

            val analyzedFrame = AnalyzedPoseFrame(bitmap, pose, timestamp, faceOverlayState)
            synchronized(frameLock) {
                latestAnalyzedFrame = analyzedFrame
            }

            val normalizedRect = cropRect?.let {
                PoseOverlayRect(
                    left = it.left.toFloat() / bitmap.width.toFloat(),
                    top = it.top.toFloat() / bitmap.height.toFloat(),
                    right = it.right.toFloat() / bitmap.width.toFloat(),
                    bottom = it.bottom.toFloat() / bitmap.height.toFloat()
                )
            }

            PoseOverlayState(
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                landmarks = pose.allLandmarks,
                cropRect = normalizedRect,
                face = faceOverlayState
            )
        }

        _poseOverlayState.value = nextOverlayState
        val state = _gameState.value
        val scale = pose.getBodyScale()
        _driftThreshold.value = movementTracker.driftThresholdFactor * scale
        _motionThreshold.value = movementTracker.motionThresholdFactor * scale

        if (state != GameState.HoldingPose) return

        val violation = movementTracker.trackFrame(pose, timestamp)
        movementTracker.referencePose?.let { _driftScore.value = calculateSingleDisplacement(pose, it) }
        movementTracker.previousPose?.let { _motionScore.value = calculateSingleDisplacement(pose, it) }

        when (violation) {
            is MovementTracker.Violation.DriftLimitExceeded,
            is MovementTracker.Violation.MotionLimitExceeded,
            is MovementTracker.Violation.PersonDisappeared -> handleMovementViolation(violation, pose)
            else -> {}
        }
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return
        speak("Займите позицию")

        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
                _startDelayRemainingSeconds.value = 0
        movementTracker.reset()
        movementViolationCount = 0
        lastMovementPenaltyAtMs = 0L

        startDelayJob?.cancel()
        timerJob?.cancel()

        startDelayJob = viewModelScope.launch {
            _gameState.value = GameState.StartingDelay
            for (seconds in startDelaySeconds downTo 1) {
                _startDelayRemainingSeconds.value = seconds
                _statusMessage.value = "Старт через $seconds сек. Прими позу"
                delay(1000)
            }

            _startDelayRemainingSeconds.value = 0

            val analyzedFrame = getFreshAnalyzedFrame()
            val initialPose = analyzedFrame?.pose

            if (analyzedFrame == null) {
                triggerDefeat("Камера не предоставила свежий синхронизированный кадр")
                return@launch
            }

            if (initialPose == null || !initialPose.hasEnoughKeypoints()) {
                triggerDefeat("Камера не видит тело. Встань полностью в кадр.")
                return@launch
            }

            _timerSeconds.value = _selectedDurationSeconds.value.coerceAtLeast(minimumDurationSeconds)
            movementTracker.reset()
            movementViolationCount = 0
            lastMovementPenaltyAtMs = 0L
            movementTracker.startTracking(initialPose)
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = "Таймер запущен. Удерживай позу"
            startTimerLoop()
            speak("Время пошло. Удерживайте позицию")
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_timerSeconds.value > 0 && _gameState.value == GameState.HoldingPose) {
                delay(1000)
                if (_gameState.value != GameState.HoldingPose) break
                _timerSeconds.value -= 1
                val remaining = _timerSeconds.value
                _statusMessage.value = "Осталось: ${formatTime(remaining)}"
            }
            if (_timerSeconds.value <= 0 && _gameState.value == GameState.HoldingPose) {
                _gameState.value = GameState.Success
                _statusMessage.value = "Победа"
                speak("Время вышло")
            }
        }
    }

    private fun handleMovementViolation(violation: MovementTracker.Violation, pose: PoseLandmarks) {
        val now = System.currentTimeMillis()
        if (lastMovementPenaltyAtMs > 0L && now - lastMovementPenaltyAtMs < movementPenaltyCooldownMs) {
            if (_gameState.value == GameState.HoldingPose) {
                movementTracker.startTracking(pose)
            }
            return
        }

        movementViolationCount += 1
        if (movementViolationCount >= 4) {
            val reason = when (violation) {
                is MovementTracker.Violation.DriftLimitExceeded -> "Пользователь сильно сдвинулся"
                is MovementTracker.Violation.MotionLimitExceeded -> "Пользователь резко двинулся"
                is MovementTracker.Violation.PersonDisappeared -> "Человек пропал из кадра"
                else -> "Пользователь нарушил условия неподвижности"
            }
            triggerDefeat(reason, "Вы не справились. Попробуйте снова")
            return
        }

        when (movementViolationCount) {
            1 -> {
                _timerSeconds.value += 60
                speak("Вы двинулись. Плюс 1 минута к таймеру")
                _statusMessage.value = "Зафиксировано движение: +1 минута"
                lastMovementPenaltyAtMs = now
            }
            2 -> {
                _timerSeconds.value += 180
                speak("Вы снова двинулись. Плюс 3 минуты к таймеру")
                _statusMessage.value = "Зафиксировано повторное движение: +3 минуты"
                lastMovementPenaltyAtMs = now
            }
            3 -> {
                _timerSeconds.value += 180
                speak("Вы снова двинулись. Плюс 3 минуты к таймеру")
                _statusMessage.value = "Зафиксировано третье движение: +3 минуты"
                lastMovementPenaltyAtMs = now
            }
            else -> Unit
        }

        if (_gameState.value == GameState.HoldingPose) {
            movementTracker.startTracking(pose)
        }
    }

    fun triggerDefeat(reason: String, voiceMessage: String = "Вы не справились. Попробуйте снова") {
        val alreadyFailed = _gameState.value == GameState.Failed
        startDelayJob?.cancel()
        timerJob?.cancel()
        _startDelayRemainingSeconds.value = 0
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = "Проверка не пройдена"
        if (!alreadyFailed) speak(voiceMessage)
    }

    private fun speak(text: String) {
        _voiceEvents.tryEmit(text)
    }

    fun stopSession() {
        startDelayJob?.cancel()
        timerJob?.cancel()
        _startDelayRemainingSeconds.value = 0
        _gameState.value = GameState.Idle
        _statusMessage.value = "Поставь телефон и встань в позу"
        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        _timerSeconds.value = _selectedDurationSeconds.value
        movementTracker.reset()
        movementViolationCount = 0
        lastMovementPenaltyAtMs = 0L
    }

    override fun onCleared() {
        faceDetectorService.close()
        super.onCleared()
    }

    private fun calculateSingleDisplacement(p1: PoseLandmarks, p2: PoseLandmarks): Float {
        var total = 0f; var count = 0
        fun add(pA: Point3D?, pB: Point3D?) { if (pA != null && pB != null) { total += pA.distanceTo(pB); count++ } }
        add(p1.leftShoulder, p2.leftShoulder); add(p1.rightShoulder, p2.rightShoulder); add(p1.leftHip, p2.leftHip); add(p1.rightHip, p2.rightHip)
        return if (count > 0) total / count else 0f
    }

    private fun formatTime(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)
}
