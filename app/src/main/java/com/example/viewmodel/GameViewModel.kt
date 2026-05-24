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
import com.example.validator.PoseValidationResult
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
    ModelDownloadRequired,
    ModelDownloading,
    Idle,
    StartingDelay,
    CheckingStartPose,
    HoldingPose,
    CheckingControlPose,
    CheckingFinalPose,
    Success,
    Failed
}

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

    private val _isGemmaChecking = MutableStateFlow(false)
    val isGemmaChecking: StateFlow<Boolean> = _isGemmaChecking.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo.asStateFlow()

    private val _isAIVersionAvailable = MutableStateFlow(false)
    val isAIVersionAvailable: StateFlow<Boolean> = _isAIVersionAvailable.asStateFlow()
    private val _voiceEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val voiceEvents: SharedFlow<String> = _voiceEvents.asSharedFlow()

    private var latestBitmap: Bitmap? = null
    private var latestLandmarks: PoseLandmarks? = null
    private val movementTracker = MovementTracker()
    private var startDelayJob: Job? = null
    private var timerJob: Job? = null
    private var activeGemmaCheckJob: Job? = null

    private fun setGemmaChecking(value: Boolean, checkName: String) {
        _isGemmaChecking.value = value
        Log.i(TAG, "$checkName: isGemmaChecking=$value")
    }

    init {
        if (GemmaModelManager.isModelDownloaded(application)) {
            _gameState.value = GameState.Idle
        } else {
            _gameState.value = GameState.ModelDownloadRequired
            _statusMessage.value = "Требуется скачать локальную Gemma модель"
        }
        _isAIVersionAvailable.value = true
    }

    fun updateSelectedDurationMinutes(minutes: Int) {
        val normalizedMinutes = minutes.coerceAtLeast(3)
        _selectedDurationSeconds.value = normalizedMinutes * 60
        if (_gameState.value == GameState.Idle || _gameState.value == GameState.Failed || _gameState.value == GameState.Success) {
            _timerSeconds.value = _selectedDurationSeconds.value
        }
    }

    fun startModelDownload() { /* unchanged */
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

    fun setLatestBitmap(bitmap: Bitmap) { latestBitmap = bitmap }

    fun processMediaPipeResults(pose: PoseLandmarks, timestamp: Long) {
        latestLandmarks = pose
        val state = _gameState.value
        val scale = pose.getBodyScale()
        _driftThreshold.value = movementTracker.driftThresholdFactor * scale
        _motionThreshold.value = movementTracker.motionThresholdFactor * scale

        if (state != GameState.HoldingPose) return

        val violation = movementTracker.trackFrame(pose, timestamp)
        movementTracker.referencePose?.let { _driftScore.value = calculateSingleDisplacement(pose, it) }
        movementTracker.previousPose?.let { _motionScore.value = calculateSingleDisplacement(pose, it) }

        when (violation) {
            is MovementTracker.Violation.DriftLimitExceeded -> triggerDefeat("Пользователь сильно сдвинулся")
            is MovementTracker.Violation.MotionLimitExceeded -> triggerDefeat("Пользователь резко двинулся")
            is MovementTracker.Violation.PersonDisappeared -> triggerDefeat("Человек пропал из кадра")
            else -> {}
        }
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return
        speak("Займите позицию")

        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        _isGemmaChecking.value = false
        _startDelayRemainingSeconds.value = 0
        movementTracker.reset()

        startDelayJob?.cancel()
        timerJob?.cancel()
        activeGemmaCheckJob?.cancel()

        startDelayJob = viewModelScope.launch {
            _gameState.value = GameState.StartingDelay
            for (seconds in startDelaySeconds downTo 1) {
                _startDelayRemainingSeconds.value = seconds
                _statusMessage.value = "Старт через $seconds сек. Прими позу"
                delay(1000)
            }

            _startDelayRemainingSeconds.value = 0

            val snapshot = latestBitmap
            val initialPose = latestLandmarks

            if (snapshot == null) {
                triggerDefeat("Камера не предоставила кадр")
                return@launch
            }

            if (initialPose == null || !initialPose.hasEnoughKeypoints()) {
                triggerDefeat("Камера не видит тело. Встань полностью в кадр.")
                return@launch
            }

            _timerSeconds.value = _selectedDurationSeconds.value.coerceAtLeast(minimumDurationSeconds)
            movementTracker.reset()
            movementTracker.startTracking(initialPose)
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = "Таймер запущен. Проверяю стартовую позу..."
            startTimerLoop()
            speak("Время пошло")

            launchGemmaCheck(
                checkName = "Стартовая проверка",
                snapshot = snapshot,
                isFinal = false
            )
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
                val totalDuration = _selectedDurationSeconds.value.coerceAtLeast(minimumDurationSeconds)
                val elapsed = totalDuration - remaining

                if (remaining > 0 && elapsed > 0 && elapsed % 60 == 0) {
                    launchGemmaCheck("Контрольная проверка", latestBitmap, isFinal = false)
                }
            }
            if (_timerSeconds.value <= 0 && _gameState.value == GameState.HoldingPose) {
                performFinalChecking()
            }
        }
    }

    private fun performFinalChecking() {
        timerJob?.cancel()
        speak("Время вышло")
        val finalSnapshot = latestBitmap
        if (finalSnapshot == null) {
            triggerDefeat("Финальная проверка: камера не предоставила кадр")
            return
        }
        _gameState.value = GameState.CheckingFinalPose
        _statusMessage.value = "Финальная проверка..."
        launchGemmaCheck("Финальная проверка", finalSnapshot, isFinal = true)
    }

    private fun launchGemmaCheck(checkName: String, snapshot: Bitmap?, isFinal: Boolean) {
        if (snapshot == null) {
            triggerDefeat("$checkName: камера не предоставила кадр")
            return
        }
        if (activeGemmaCheckJob?.isActive == true) {
            Log.i(TAG, "$checkName пропущена: предыдущая Gemma-проверка ещё выполняется")
            return
        }

        activeGemmaCheckJob = viewModelScope.launch {
            setGemmaChecking(true, checkName)
            try {
                Log.i(TAG, "$checkName: validatePose started")
                val result = GemmaPoseValidator.validatePose(getApplication(), snapshot)
                Log.i(TAG, "$checkName: validatePose returned, passed=${result.isPassed}, rawJson=${result.rawJson}")
                setGemmaChecking(false, checkName)

                if (!result.isPassed) {
                    triggerDefeat(buildGemmaFailReason(result, checkName))
                    return@launch
                }

                if (isFinal) {
                    _gameState.value = GameState.Success
                    _statusMessage.value = "Победа"
                } else if (_gameState.value == GameState.HoldingPose) {
                    _statusMessage.value = "$checkName пройдена"
                }
            } catch (e: Throwable) {
                Log.e(TAG, "$checkName failed", e)
                triggerDefeat("$checkName: ошибка Gemma: ${e.message}")
            } finally {
                setGemmaChecking(false, checkName)
                activeGemmaCheckJob = null
            }
        }
    }

    private fun buildGemmaFailReason(result: PoseValidationResult, checkName: String): String {
        val failed = mutableListOf<String>()
        if (!result.personPresent) failed += "person_present"
        if (!result.facingAway) failed += "facing_away"
        if (!result.kneeling) failed += "kneeling"
        return if (failed.isEmpty()) {
            "$checkName: не удалось распарсить ответ Gemma"
        } else {
            "$checkName: Gemma не подтвердила: ${failed.joinToString(", ")}"
        }
    }

    fun triggerDefeat(reason: String) {
        val alreadyFailed = _gameState.value == GameState.Failed
        startDelayJob?.cancel()
        timerJob?.cancel()
        _isGemmaChecking.value = false
        Log.i(TAG, "triggerDefeat: isGemmaChecking=false")
        _startDelayRemainingSeconds.value = 0
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = "Проверка не пройдена"
        if (!alreadyFailed) {
            speak("Вы не справились.")
        }
    }

    private fun speak(text: String) {
        _voiceEvents.tryEmit(text)
    }

    fun stopSession() {
        startDelayJob?.cancel()
        timerJob?.cancel()
        activeGemmaCheckJob?.cancel()
        _isGemmaChecking.value = false
        _startDelayRemainingSeconds.value = 0
        _gameState.value = GameState.Idle
        _statusMessage.value = "Поставь телефон и встань в позу"
        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        _timerSeconds.value = _selectedDurationSeconds.value
        movementTracker.reset()
    }

    private fun calculateSingleDisplacement(p1: PoseLandmarks, p2: PoseLandmarks): Float {
        var total = 0f; var count = 0
        fun add(pA: Point3D?, pB: Point3D?) { if (pA != null && pB != null) { total += pA.distanceTo(pB); count++ } }
        add(p1.leftShoulder, p2.leftShoulder); add(p1.rightShoulder, p2.rightShoulder); add(p1.leftHip, p2.leftHip); add(p1.rightHip, p2.rightHip)
        return if (count > 0) total / count else 0f
    }

    private fun formatTime(seconds: Int): String = String.format("%02d:%02d", seconds / 60, seconds % 60)

    override fun onCleared() { super.onCleared(); GemmaPoseValidator.close() }
}
