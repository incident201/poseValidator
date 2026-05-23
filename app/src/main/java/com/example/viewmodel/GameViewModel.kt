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
    private val minimumDurationSeconds = 180

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

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadBytesInfo = MutableStateFlow("0 / 0 MB")
    val downloadBytesInfo: StateFlow<String> = _downloadBytesInfo.asStateFlow()

    private val _isAIVersionAvailable = MutableStateFlow(false)
    val isAIVersionAvailable: StateFlow<Boolean> = _isAIVersionAvailable.asStateFlow()

    private var latestBitmap: Bitmap? = null
    private var latestLandmarks: PoseLandmarks? = null
    private val movementTracker = MovementTracker()
    private var timerJob: Job? = null
    private var activeGemmaCheckJob: Job? = null

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

        if (state != GameState.HoldingPose && state != GameState.CheckingControlPose) return

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

        _defeatReason.value = ""
        _driftScore.value = 0f
        _motionScore.value = 0f
        movementTracker.reset()

        val bitmapSnapshot = latestBitmap
        val initialPose = latestLandmarks

        if (bitmapSnapshot == null || initialPose == null || !initialPose.hasEnoughKeypoints()) {
            _gameState.value = GameState.Failed
            _statusMessage.value = "Камера не видит тело. Встань полностью в кадр."
            _defeatReason.value = "Камера не видит тело. Встань полностью в кадр."
            return
        }

        movementTracker.startTracking(initialPose)
        _timerSeconds.value = _selectedDurationSeconds.value.coerceAtLeast(minimumDurationSeconds)
        _gameState.value = GameState.HoldingPose
        _statusMessage.value = "Таймер запущен. Проверяю стартовую позу..."

        startTimerLoop()
        launchGemmaCheck(checkName = "Стартовая проверка", snapshot = bitmapSnapshot)
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_timerSeconds.value > 0) {
                delay(1000)
                _timerSeconds.value -= 1
                val remaining = _timerSeconds.value
                _statusMessage.value = "Осталось: ${formatTime(remaining)}"

                if (remaining > 0 && remaining % 60 == 0) {
                    launchGemmaCheck("Контрольная проверка", latestBitmap)
                }
            }
            performFinalChecking()
        }
    }

    private fun performFinalChecking() {
        timerJob?.cancel()
        _gameState.value = GameState.CheckingFinalPose
        _statusMessage.value = "Финальная проверка..."
        val finalSnapshot = latestBitmap
        if (finalSnapshot == null) {
            triggerDefeat("Камера не предоставила кадр")
            return
        }
        launchGemmaCheck("Финальная проверка", finalSnapshot, onSuccess = {
            _gameState.value = GameState.Success
            _statusMessage.value = "Победа"
        })
    }

    private fun launchGemmaCheck(checkName: String, snapshot: Bitmap?, onSuccess: (() -> Unit)? = null) {
        if (snapshot == null || (_gameState.value != GameState.HoldingPose && _gameState.value != GameState.CheckingFinalPose)) return
        if (activeGemmaCheckJob?.isActive == true) {
            Log.i(TAG, "$checkName пропущена: предыдущая Gemma-проверка ещё выполняется")
            return
        }

        activeGemmaCheckJob = viewModelScope.launch {
            if (_gameState.value == GameState.HoldingPose && checkName == "Контрольная проверка") {
                _gameState.value = GameState.CheckingControlPose
            }
            val result = GemmaPoseValidator.validatePose(getApplication(), snapshot)
            Log.i(TAG, "$checkName rawJson=${result.rawJson}")

            if (!result.isPassed) {
                triggerDefeat(buildGemmaFailReason(result, checkName))
                return@launch
            }

            if (_gameState.value == GameState.CheckingControlPose) {
                _gameState.value = GameState.HoldingPose
                _statusMessage.value = "Поза подтверждена"
            }
            onSuccess?.invoke()
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
        timerJob?.cancel()
        activeGemmaCheckJob?.cancel()
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = "Проверка не пройдена"
    }

    fun stopSession() {
        timerJob?.cancel(); activeGemmaCheckJob?.cancel()
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
