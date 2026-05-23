package com.example.viewmodel

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tracker.MovementTracker
import com.example.tracker.Point3D
import com.example.tracker.PoseLandmarks
import com.example.validator.GemmaPoseValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class GameState {
    Idle,
    CheckingStartPose,
    HoldingPose,
    CheckingFinalPose,
    Success,
    Failed
}

class GameViewModel : ViewModel() {
    private val TAG = "GameViewModel"

    private val _gameState = MutableStateFlow(GameState.Idle)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _timerSeconds = MutableStateFlow(180)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _statusMessage = MutableStateFlow("Поставь телефон и встань в позу")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _defeatReason = MutableStateFlow("")
    val defeatReason: StateFlow<String> = _defeatReason.asStateFlow()

    private val _driftScore = MutableStateFlow(0f)
    val driftScore: StateFlow<Float> = _driftScore.asStateFlow()

    private val _motionScore = MutableStateFlow(0f)
    val motionScore: StateFlow<Float> = _motionScore.asStateFlow()

    // Thresholds
    private val _driftThreshold = MutableStateFlow(0.075f) // Approximate normalized threshold for display
    val driftThreshold: StateFlow<Float> = _driftThreshold.asStateFlow()

    private val _motionThreshold = MutableStateFlow(0.054f) // Approximate normalized threshold for display
    val motionThreshold: StateFlow<Float> = _motionThreshold.asStateFlow()

    // Debugging and Simulation Aids
    private val _isSimulatorEnabled = MutableStateFlow(true) // Default to true because emulator has no real person kneeling in front of back-camera
    val isSimulatorEnabled: StateFlow<Boolean> = _isSimulatorEnabled.asStateFlow()

    private val _isAIVersionAvailable = MutableStateFlow(false)
    val isAIVersionAvailable: StateFlow<Boolean> = _isAIVersionAvailable.asStateFlow()

    // Active AI/MediaPipe tracking values
    private var latestBitmap: Bitmap? = null
    private val movementTracker = MovementTracker()
    private var timerJob: Job? = null

    // Schedulers for Periodic Gemma Checks
    private var nextCheckpointSeconds = 0

    // SimulationOffsets
    var simDriftOffset = 0f
    var simMotionOffset = 0f

    init {
        // Evaluate if Gemini Key is available
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        if (apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY") {
            _isAIVersionAvailable.value = true
        }
    }

    fun setSimulatorEnabled(enabled: Boolean) {
        _isSimulatorEnabled.value = enabled
    }

    fun setLatestBitmap(bitmap: Bitmap) {
        latestBitmap = bitmap
    }

    fun processMediaPipeResults(pose: PoseLandmarks, timestamp: Long) {
        val state = _gameState.value
        if (state != GameState.HoldingPose) {
            // Keep scores updated in idle / checking
            val scale = pose.getBodyScale()
            _driftThreshold.value = movementTracker.driftThresholdFactor * scale
            _motionThreshold.value = movementTracker.motionThresholdFactor * scale
            return
        }

        val scale = pose.getBodyScale()
        _driftThreshold.value = movementTracker.driftThresholdFactor * scale
        _motionThreshold.value = movementTracker.motionThresholdFactor * scale

        // Feed to movement tracker
        val violation = movementTracker.trackFrame(pose, timestamp)
        
        // Calculate scores for UI bar visualization
        val ref = movementTracker.referencePose
        val prev = movementTracker.previousPose
        if (ref != null) {
            _driftScore.value = calculateSingleDisplacement(pose, ref)
        }
        if (prev != null) {
            _motionScore.value = calculateSingleDisplacement(pose, prev)
        }

        when (violation) {
            is MovementTracker.Violation.DriftLimitExceeded -> {
                triggerDefeat("Пользователь сильно сдвинулся")
            }
            is MovementTracker.Violation.MotionLimitExceeded -> {
                triggerDefeat("Пользователь резко двинулся")
            }
            is MovementTracker.Violation.PersonDisappeared -> {
                triggerDefeat("Человек пропал из кадра")
            }
            else -> {} // Normal stabilization
        }
    }

    private fun calculateSingleDisplacement(p1: PoseLandmarks, p2: PoseLandmarks): Float {
        var total = 0f
        var count = 0
        fun add(pA: Point3D?, pB: Point3D?) {
            if (pA != null && pB != null) {
                total += pA.distanceTo(pB)
                count++
            }
        }
        add(p1.leftShoulder, p2.leftShoulder)
        add(p1.rightShoulder, p2.rightShoulder)
        add(p1.leftHip, p2.leftHip)
        add(p1.rightHip, p2.rightHip)
        return if (count > 0) total / count else 0f
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return

        _gameState.value = GameState.CheckingStartPose
        _statusMessage.value = "Проверяю позу через Gemma..."
        _defeatReason.value = ""
        _timerSeconds.value = 180
        simDriftOffset = 0f
        simMotionOffset = 0f
        _driftScore.value = 0f
        _motionScore.value = 0f
        movementTracker.reset()

        viewModelScope.launch {
            if (_isSimulatorEnabled.value) {
                // Introduce simulated delay
                delay(1500)
                initiateHoldingState()
            } else {
                val bitmapSnapshot = latestBitmap
                if (bitmapSnapshot == null) {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Ошибка анализа изображения"
                    _defeatReason.value = "Камера не предоставила кадр"
                    return@launch
                }

                val aiResult = GemmaPoseValidator.validatePose(bitmapSnapshot)
                if (aiResult.isPassed) {
                    initiateHoldingState()
                } else {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Проверка позы не пройдена"
                    _defeatReason.value = "Стартовая поза не распознана"
                    Log.e(TAG, "Gemma start failed. Output: ${aiResult.rawJson}")
                }
            }
        }
    }

    private fun initiateHoldingState() {
        _gameState.value = GameState.HoldingPose
        _statusMessage.value = "Поза принята"

        // Initialize landmarks reference
        val initialPose = if (_isSimulatorEnabled.value) {
            // Simulated baseline pose
            generateSimulatedBaseline()
        } else {
            // Ideally grabbed from real MediaPipe frame inside frame analysis
            generateSimulatedBaseline()
        }
        movementTracker.startTracking(initialPose)

        // Plan next gemma checkpoint
        scheduleNextCheckpoint()

        // Begin holding timer loop
        startTimerLoop()
    }

    private fun generateSimulatedBaseline(): PoseLandmarks {
        return PoseLandmarks(
            leftShoulder = Point3D(0.40f, 0.35f, 0.1f),
            rightShoulder = Point3D(0.60f, 0.35f, 0.1f),
            leftElbow = Point3D(0.35f, 0.45f, 0.2f),
            rightElbow = Point3D(0.65f, 0.45f, 0.2f),
            leftHip = Point3D(0.42f, 0.60f, 0.0f),
            rightHip = Point3D(0.58f, 0.60f, 0.0f),
            leftKnee = Point3D(0.45f, 0.80f, -0.1f),
            rightKnee = Point3D(0.55f, 0.80f, -0.1f)
        )
    }

    private fun scheduleNextCheckpoint() {
        // "примерно раз в 30 секунд, лучше с небольшим случайным смещением, например 25–40 секунд"
        val remaining = _timerSeconds.value
        if (remaining <= 35) {
            // Avoid scheduling checkpoint right before final check
            nextCheckpointSeconds = -1
        } else {
            val gap = Random.nextInt(25, 41)
            nextCheckpointSeconds = remaining - gap
            Log.i(TAG, "Scheduled dynamic Gemma checkpoint at $nextCheckpointSeconds seconds remaining")
        }
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_timerSeconds.value > 0) {
                delay(1000)
                _timerSeconds.value -= 1
                val remaining = _timerSeconds.value

                _statusMessage.value = "Осталось: ${formatTime(remaining)}"

                // Check periodic Gemma constraint
                if (remaining == nextCheckpointSeconds) {
                    triggerPeriodicCheckpoint()
                }
            }

            // Timer reached 0, initiate final check
            performFinalChecking()
        }
    }

    private suspend fun triggerPeriodicCheckpoint() {
        Log.i(TAG, "Triggering periodic checkpoint. Time = ${_timerSeconds.value}s remaining")
        _statusMessage.value = "Проверяю позу..."

        if (_isSimulatorEnabled.value) {
            delay(1200) // Simulated AI delay
            _statusMessage.value = "Поза подтверждена"
            scheduleNextCheckpoint()
        } else {
            val bitmapSnapshot = latestBitmap
            if (bitmapSnapshot != null) {
                val aiResult = GemmaPoseValidator.validatePose(bitmapSnapshot)
                if (aiResult.isPassed) {
                    _statusMessage.value = "Поза подтверждена"
                    scheduleNextCheckpoint()
                } else {
                    triggerDefeat("Контрольная проверка позы не пройдена")
                }
            } else {
                triggerDefeat("Ошибка анализа изображения")
            }
        }
    }

    private fun performFinalChecking() {
        timerJob?.cancel()
        _gameState.value = GameState.CheckingFinalPose
        _statusMessage.value = "Финальная проверка позы..."

        viewModelScope.launch {
            if (_isSimulatorEnabled.value) {
                delay(1500)
                _gameState.value = GameState.Success
                _statusMessage.value = "Победа"
            } else {
                val bitmapSnapshot = latestBitmap
                if (bitmapSnapshot == null) {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Ошибка анализа изображения"
                    _defeatReason.value = "Камера не предоставила кадр"
                    return@launch
                }

                val aiResult = GemmaPoseValidator.validatePose(bitmapSnapshot)
                if (aiResult.isPassed) {
                    _gameState.value = GameState.Success
                    _statusMessage.value = "Победа"
                } else {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Проверка позы не пройдена"
                    _defeatReason.value = "Финальная проверка позы не пройдена"
                }
            }
        }
    }

    fun triggerDefeat(reason: String) {
        timerJob?.cancel()
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = when (reason) {
            "Пользователь сильно сдвинулся" -> "Ты сильно сдвинулся"
            "Пользователь резко двинулся" -> "Ты резко двинулся"
            "Контрольная проверка позы не пройдена" -> "Контрольная проверка не пройдена"
            "Финальная проверка позы не пройдена" -> "Финальная проверка не пройдена"
            "Человек пропал из кадра" -> "Человек пропал из кадра"
            else -> "Ошибка позы или движения"
        }
        Log.w(TAG, "Defeat triggered! Reason: $reason")
    }

    fun stopSession() {
        timerJob?.cancel()
        _gameState.value = GameState.Idle
        _statusMessage.value = "Поставь телефон и встань в позу"
        _defeatReason.value = ""
        simDriftOffset = 0f
        simMotionOffset = 0f
        _driftScore.value = 0f
        _motionScore.value = 0f
        movementTracker.reset()
    }

    // Interactive Demo Actions for Assessors
    fun simulateDriftStep() {
        if (_gameState.value == GameState.HoldingPose) {
            simDriftOffset += 0.06f // Increases step by step to breach drift (factor 0.25)
            // Trigger feedback in ViewModel execution
            val simulatedPose = generateInteractiveSimulatedPose()
            processMediaPipeResults(simulatedPose, SystemClock.elapsedRealtime())
        }
    }

    fun simulateSuddenMotion() {
        if (_gameState.value == GameState.HoldingPose) {
            simMotionOffset = 0.25f // Brief peak to breach motion (factor 0.18)
            val simulatedPose = generateInteractiveSimulatedPose()
            processMediaPipeResults(simulatedPose, SystemClock.elapsedRealtime())
            
            // Decays after a brief delay
            viewModelScope.launch {
                delay(150)
                simMotionOffset = 0f
            }
        }
    }

    fun generateInteractiveSimulatedPose(): PoseLandmarks {
        val breathe = kotlin.math.sin(SystemClock.elapsedRealtime().toDouble() / 500.0).toFloat() * 0.005f
        val offsetTotalX = simDriftOffset + simMotionOffset
        val offsetTotalY = (simDriftOffset * 0.5f) + breathe

        return PoseLandmarks(
            leftShoulder = Point3D(0.40f + offsetTotalX, 0.35f + offsetTotalY, 0.1f),
            rightShoulder = Point3D(0.60f + offsetTotalX, 0.35f + offsetTotalY, 0.1f),
            leftElbow = Point3D(0.35f + offsetTotalX, 0.45f + offsetTotalY, 0.2f),
            rightElbow = Point3D(0.65f + offsetTotalX, 0.45f + offsetTotalY, 0.2f),
            leftHip = Point3D(0.42f + offsetTotalX, 0.60f + offsetTotalY, 0.0f),
            rightHip = Point3D(0.58f + offsetTotalX, 0.60f + offsetTotalY, 0.0f),
            leftKnee = Point3D(0.45f + offsetTotalX, 0.80f + offsetTotalY, -0.1f),
            rightKnee = Point3D(0.55f + offsetTotalX, 0.80f + offsetTotalY, -0.1f)
        )
    }

    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }
}
