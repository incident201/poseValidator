package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tracker.MovementTracker
import com.example.tracker.Point3D
import com.example.tracker.PoseLandmarks
import com.example.validator.GemmaModelManager
import com.example.validator.GemmaPoseValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GameState {
    ModelDownloadRequired,
    ModelDownloading,
    Idle,
    CheckingStartPose,
    HoldingPose,
    CheckingControlPose,
    CheckingFinalPose,
    Success,
    Failed
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
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
    private val _driftThreshold = MutableStateFlow(0.075f) 
    val driftThreshold: StateFlow<Float> = _driftThreshold.asStateFlow()

    private val _motionThreshold = MutableStateFlow(0.054f) 
    val motionThreshold: StateFlow<Float> = _motionThreshold.asStateFlow()

    // Download variables
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo.asStateFlow()
    private val _isAIVersionAvailable = MutableStateFlow(false)
    val isAIVersionAvailable: StateFlow<Boolean> = _isAIVersionAvailable.asStateFlow()

    // Active AI/MediaPipe tracking values
    private var latestBitmap: Bitmap? = null
    private var latestLandmarks: PoseLandmarks? = null
    private val movementTracker = MovementTracker()
    private var timerJob: Job? = null

    // Schedulers for Periodic Gemma Checks
    private var nextCheckpointSeconds = 0

    init {
        // Initialize based on whether local gemma model is downloaded
        if (GemmaModelManager.isModelDownloaded(application)) {
            _gameState.value = GameState.Idle
            _statusMessage.value = "Поставь телефон и встань в позу"
        } else {
            _gameState.value = GameState.ModelDownloadRequired
            _statusMessage.value = "Требуется скачать локальную Gemma модель"
        }

        // Always enable AI designation for local Gemma implementation
        _isAIVersionAvailable.value = true
    }

    fun startModelDownload() {
        if (_gameState.value != GameState.ModelDownloadRequired) return
        _gameState.value = GameState.ModelDownloading
        _statusMessage.value = "Скачивание модели... Пожалуйста, не закрывайте экран."

        viewModelScope.launch {
            val success = GemmaModelManager.downloadModel(getApplication()) { progress, downloaded, total ->
                _downloadProgress.value = progress
                _downloadBytesInfo.value = String.format("%.1f MB / %.1f MB (%.0f%%)", downloaded, total, progress * 100)
            }
            if (success) {
                _gameState.value = GameState.Idle
                _statusMessage.value = "Модель успешно загружена! Камера активна."
            } else {
                _gameState.value = GameState.ModelDownloadRequired
                _statusMessage.value = "Ошибка скачивания. Пожалуйста, попробуйте снова."
            }
    }
    }

    fun setLatestBitmap(bitmap: Bitmap) {
        latestBitmap = bitmap
    }

    fun processMediaPipeResults(pose: PoseLandmarks, timestamp: Long) {
        latestLandmarks = pose
        val state = _gameState.value
        
        // Feed landmarks to movement tracker when we are in posture holding / checking control states
        if (state != GameState.HoldingPose && state != GameState.CheckingControlPose) {
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
        _driftScore.value = 0f
        _motionScore.value = 0f
        movementTracker.reset()

        viewModelScope.launch {
            val bitmapSnapshot = latestBitmap
            if (bitmapSnapshot == null) {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Ошибка анализа изображения"
                    _defeatReason.value = "Камера не предоставила кадр"
                    return@launch
                }

                val aiResult = GemmaPoseValidator.validatePose(getApplication(), bitmapSnapshot)
                if (!aiResult.isPassed) {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "Проверка позы не пройдена"
                    _defeatReason.value = "Стартовая поза не распознана"
                    Log.e(TAG, "Local Gemma verification failed. Result: ${aiResult.rawJson}")
                    return@launch
                }

                _statusMessage.value = "Ожидаем landmarks MediaPipe..."
                var retries = 0
                while ((latestLandmarks?.hasEnoughKeypoints() != true) && retries < 50) {
                    delay(100)
                    retries++
                }

                if (latestLandmarks?.hasEnoughKeypoints() != true) {
                    _gameState.value = GameState.Failed
                    _statusMessage.value = "MediaPipe не видит человека"
                    _defeatReason.value = "Недостаточно ключевых точек (плечи/таз/колени)"
                    return@launch
                }

                initiateHoldingState()
            
        }
    }

    private fun initiateHoldingState() {
        _gameState.value = GameState.HoldingPose
        _statusMessage.value = "Поза принята"

        // Initialize landmarks reference
        val initialPose = latestLandmarks
        if (initialPose == null || !initialPose.hasEnoughKeypoints()) {
            triggerDefeat("Недостаточно ключевых точек для отслеживания")
            return
        }
        movementTracker.startTracking(initialPose)

        // Plan next gemma checkpoint
        scheduleNextCheckpoint()

        // Begin holding timer loop
        startTimerLoop()
    }

    private fun scheduleNextCheckpoint() {
        val remaining = _timerSeconds.value
        if (remaining <= 30) {
            nextCheckpointSeconds = -1
        } else {
            nextCheckpointSeconds = remaining - 30
            Log.i(TAG, "Scheduled Gemma checkpoint at $nextCheckpointSeconds seconds remaining")
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
        
        _gameState.value = GameState.CheckingControlPose
        _statusMessage.value = "Проверяю позу..."

        val bitmapSnapshot = latestBitmap
        if (bitmapSnapshot != null) {
            val aiResult = GemmaPoseValidator.validatePose(getApplication(), bitmapSnapshot)
            if (aiResult.isPassed) {
                _gameState.value = GameState.HoldingPose
                _statusMessage.value = "Поза подтверждена"
                scheduleNextCheckpoint()
            } else {
                triggerDefeat("Контрольная проверка позы не пройдена")
            }
        } else {
            triggerDefeat("Ошибка анализа изображения")
        }
    }

    private fun performFinalChecking() {
        timerJob?.cancel()
        _gameState.value = GameState.CheckingFinalPose
        _statusMessage.value = "Финальная проверка позы..."

        viewModelScope.launch {
            val bitmapSnapshot = latestBitmap
            if (bitmapSnapshot == null) {
                _gameState.value = GameState.Failed
                _statusMessage.value = "Ошибка анализа изображения"
                _defeatReason.value = "Камера не предоставила кадр"
                return@launch
            }

            val aiResult = GemmaPoseValidator.validatePose(getApplication(), bitmapSnapshot)
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
        _statusMessage.value = "Встань на колени спиной к камере. Нажми Старт."
        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        movementTracker.reset()
    }

    private fun formatTime(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    override fun onCleared() {
        super.onCleared()
        GemmaPoseValidator.close()
    }
}
