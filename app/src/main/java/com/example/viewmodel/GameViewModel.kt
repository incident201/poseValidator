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
import kotlinx.coroutines.CancellationException
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
    ModelPreparing,
    ModelStarting,
    ModelError,
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

    private val _gameState = MutableStateFlow(GameState.ModelPreparing)
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
    private var movementViolationCount: Int = 0
    private var lastMovementPenaltyAtMs: Long = 0L
    private val movementPenaltyCooldownMs: Long = 3000L
    private var gemmaCheckGeneration: Int = 0

    private fun setGemmaChecking(value: Boolean, checkName: String) {
        _isGemmaChecking.value = value
        Log.i(TAG, "$checkName: isGemmaChecking=$value")
    }

    init {
        if (GemmaModelManager.isModelDownloaded(application)) {
            if (GemmaPoseValidator.isRuntimeCachePrepared(application)) {
                startGemmaRuntimeFromPreparedCache()
            } else {
                prepareGemmaRuntime(forceRebuild = false)
            }
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
                prepareGemmaRuntime(forceRebuild = true)
            } else {
                _gameState.value = GameState.ModelDownloadRequired
                _statusMessage.value = "Ошибка скачивания. Пожалуйста, попробуйте снова."
            }
        }
    }

    fun setLatestBitmap(bitmap: Bitmap) { latestBitmap = bitmap }

    private fun prepareGemmaRuntime(forceRebuild: Boolean = false) {
        _gameState.value = GameState.ModelPreparing
        _statusMessage.value = "Подготовка локальной Gemma модели... Первый запуск после установки или обновления может занять некоторое время."
        viewModelScope.launch {
            val prepared = if (forceRebuild) {
                GemmaPoseValidator.rebuildRuntimeCache(getApplication())
            } else {
                GemmaPoseValidator.prepare(getApplication())
            }
            if (prepared) {
                _gameState.value = GameState.Idle
                _statusMessage.value = "Gemma готова. Камера активна."
            } else {
                _gameState.value = GameState.ModelError
                _statusMessage.value = "Не удалось подготовить Gemma runtime. Нажмите \"Пересобрать кэш\"."
            }
        }
    }

    private fun startGemmaRuntimeFromPreparedCache() {
        _gameState.value = GameState.ModelStarting
        _statusMessage.value = "Запуск Gemma runtime..."
        viewModelScope.launch {
            val prepared = GemmaPoseValidator.prepare(getApplication())
            if (prepared) {
                _gameState.value = GameState.Idle
                _statusMessage.value = "Gemma готова. Камера активна."
            } else {
                _gameState.value = GameState.ModelError
                _statusMessage.value = "Не удалось подготовить Gemma runtime. Нажмите \"Пересобрать кэш\"."
            }
        }
    }

    fun retryGemmaPreparation() {
        prepareGemmaRuntime(forceRebuild = true)
    }

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
        _isGemmaChecking.value = false
        _startDelayRemainingSeconds.value = 0
        movementTracker.reset()
        movementViolationCount = 0
        lastMovementPenaltyAtMs = 0L

        startDelayJob?.cancel()
        timerJob?.cancel()
        activeGemmaCheckJob?.cancel()
        gemmaCheckGeneration += 1

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
            movementViolationCount = 0
            lastMovementPenaltyAtMs = 0L
            movementTracker.startTracking(initialPose)
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = "Таймер запущен. Проверяю стартовую позу..."
            startTimerLoop()
            speak("Время пошло. Идет проверка")

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
            }
            if (_timerSeconds.value <= 0 && _gameState.value == GameState.HoldingPose) {
                _gameState.value = GameState.Success
                _statusMessage.value = "Победа"
                speak("Время вышло")
            }
        }
    }

    private fun launchGemmaCheck(checkName: String, snapshot: Bitmap?, isFinal: Boolean) {
        if (snapshot == null) {
            triggerDefeat("$checkName: камера не предоставила кадр")
            return
        }
        activeGemmaCheckJob?.cancel()
        val currentGeneration = ++gemmaCheckGeneration

        activeGemmaCheckJob = viewModelScope.launch {
            setGemmaChecking(true, checkName)
            try {
                Log.i(TAG, "$checkName: validatePose started")
                val result = GemmaPoseValidator.validatePose(getApplication(), snapshot)
                if (currentGeneration != gemmaCheckGeneration) {
                    Log.i(TAG, "$checkName: устаревший результат Gemma проигнорирован")
                    return@launch
                }
                Log.i(TAG, "$checkName: validatePose returned, passed=${result.isPassed}, rawJson=${result.rawJson}")
                setGemmaChecking(false, checkName)

                if (result.technicalError != null) {
                    handleGemmaRuntimeError(checkName, result.technicalError)
                    return@launch
                }

                if (!result.isPassed) {
                    val failMessage = buildGemmaFailureVoiceMessage(result)
                    triggerDefeat(buildGemmaFailReason(result, checkName), failMessage)
                    return@launch
                }

                if (!isFinal) {
                    speak("Поза подтверждена. Удерживайте позицию")
                }

                if (isFinal) {
                    _gameState.value = GameState.Success
                    _statusMessage.value = "Победа"
                } else if (_gameState.value == GameState.HoldingPose) {
                    _statusMessage.value = "$checkName пройдена"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (currentGeneration != gemmaCheckGeneration) return@launch
                Log.e(TAG, "$checkName failed", e)
                handleGemmaRuntimeError(checkName, e.message ?: "Unknown Gemma runtime error")
            } finally {
                if (currentGeneration == gemmaCheckGeneration) {
                    setGemmaChecking(false, checkName)
                    activeGemmaCheckJob = null
                }
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
            viewModelScope.launch {
                delay(movementPenaltyCooldownMs)
                if (_gameState.value != GameState.HoldingPose) return@launch
                val snapshot = latestBitmap ?: run {
                    triggerDefeat("Повторная проверка позы: камера не предоставила кадр")
                    return@launch
                }
                speak("Выполняется проверка позы")
                launchGemmaCheck(
                    checkName = "Повторная проверка позы после движения",
                    snapshot = snapshot,
                    isFinal = false
                )
            }
        }
    }

    private fun buildGemmaFailureVoiceMessage(result: PoseValidationResult): String {
        return when {
            !result.personPresent -> "Проверка позы не пройдена. Вас не видно"
            !result.facingAway -> "Проверка позы не пройдена. Встаньте лицом к стене"
            !result.nude -> "Проверка позы не пройдена. Снимите одежду"
            else -> "Проверка позы не пройдена"
        }
    }

    private fun buildGemmaFailReason(result: PoseValidationResult, checkName: String): String {
        val failed = mutableListOf<String>()
        if (!result.personPresent) failed += "person_present"
        if (!result.facingAway) failed += "facing_away"
        if (!result.nude) failed += "nude"
        return if (failed.isEmpty()) {
            "$checkName: не удалось распарсить ответ Gemma"
        } else {
            "$checkName: Gemma не подтвердила: ${failed.joinToString(", ")}"
        }
    }

    fun triggerDefeat(reason: String, voiceMessage: String = "Вы не справились. Попробуйте снова") {
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
            speak(voiceMessage)
        }
    }

    private fun handleGemmaRuntimeError(checkName: String, error: String) {
        startDelayJob?.cancel()
        timerJob?.cancel()
        activeGemmaCheckJob?.cancel()
        _isGemmaChecking.value = false
        _startDelayRemainingSeconds.value = 0
        _gameState.value = GameState.ModelError
        _statusMessage.value = "Не удалось подготовить Gemma runtime. Нажмите \"Пересобрать кэш\"."
        _defeatReason.value = "$checkName: техническая ошибка Gemma runtime: $error"
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
        movementViolationCount = 0
        lastMovementPenaltyAtMs = 0L
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
