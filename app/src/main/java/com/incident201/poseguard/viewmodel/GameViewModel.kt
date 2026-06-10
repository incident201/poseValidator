package com.incident201.poseguard.viewmodel

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
import com.incident201.poseguard.R
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueEvent
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmChannel
import com.incident201.poseguard.audio.PcmPattern
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.audio.TtsVoiceMode
import com.incident201.poseguard.tracker.FaceDetectionStatus
import com.incident201.poseguard.tracker.FaceCandidateCropper
import com.incident201.poseguard.tracker.FaceDetectorService
import com.incident201.poseguard.tracker.MovementTracker
import com.incident201.poseguard.tracker.Point3D
import com.incident201.poseguard.tracker.PoseFrameCropper
import com.incident201.poseguard.tracker.PoseIdentityStabilizationResult
import com.incident201.poseguard.tracker.PoseIdentityStabilizer
import com.incident201.poseguard.tracker.PoseLandmarks
import com.incident201.poseguard.tracker.PoseOcclusionGuard
import com.incident201.poseguard.tracker.PoseOcclusionGuardConfig
import com.incident201.poseguard.tracker.PoseSmoother
import com.incident201.poseguard.tracker.landmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

private const val PREF_OCCLUSION_FREEZE_VIS_ALWAYS = "occlusion_freeze_visibility_always"
private const val PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS = "occlusion_freeze_visibility_p10_always"
private const val PREF_OCCLUSION_FREEZE_VIS_HARD = "occlusion_freeze_visibility_hard"
private const val PREF_OCCLUSION_FREEZE_VIS_SOFT = "occlusion_freeze_visibility_soft"
private const val PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD = "occlusion_jitter_freeze_threshold"
private const val PREF_WRIST_DRIFT_WEIGHT = "wrist_drift_weight"
private const val DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS = 0.05f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS = 0.02f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_HARD = 0.08f
private const val DEFAULT_OCCLUSION_FREEZE_VIS_SOFT = 0.1f
private const val DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD = 0.01f
private const val DEFAULT_WRIST_DRIFT_WEIGHT = 0.7f
private const val PREF_DEBUG_MODE_ENABLED = "debug_mode_enabled"
private const val PREF_ONBOARDING_COMPLETED = "onboarding_completed"
private const val PREF_POSE_SMOOTHER_MIN_CUTOFF = "pose_smoother_min_cutoff"
private const val PREF_POSE_SMOOTHER_BETA = "pose_smoother_beta"
private const val PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF = "pose_smoother_derivative_cutoff"
private const val PREF_SENSITIVITY_PRESETS_VERSION = "sensitivity_presets_version"
private const val CURRENT_SENSITIVITY_PRESETS_VERSION = 2
private const val MAX_POSE_DROPOUT_HOLD_FRAMES = 5
private const val MAX_POSE_DROPOUT_HOLD_MS = 180L
private const val PREF_CUSTOMIZE_AUDIO_ENABLED = "customize_audio_enabled"
private const val PREF_TTS_VOICE_MODE = "tts_voice_mode"
private const val PREF_AUDIO_CUE_MODE_PREFIX = "audio_cue_mode_"
private const val PREF_AUDIO_CUE_URI_PREFIX = "audio_cue_uri_"
private const val PREF_TTS_TEMPLATE_PREFIX = "tts_template_"
private const val LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX = "audio_cue_tts_text_"
private const val PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX = "audio_cue_pcm_frequency_"
private const val PREF_AUDIO_CUE_PCM_DURATION_PREFIX = "audio_cue_pcm_duration_"
private const val PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX = "audio_cue_pcm_channel_"
private const val PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX = "audio_cue_pcm_amplitude_"
private const val PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX = "audio_cue_pcm_fade_in_"
private const val PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX = "audio_cue_pcm_fade_out_"
private const val PREF_AUDIO_CUE_PCM_PATTERN_PREFIX = "audio_cue_pcm_pattern_"

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
    val faceCheckMode: FaceCheckMode = FaceCheckMode.Disabled,
    val faceDetectionConfidence: Float = 0.8f,
    val driftThresholdFactor: Float = 0.160f,
    val motionThresholdFactor: Float = 0.04f,
    val minimumPenaltyIntervalSeconds: Int = 5,
    val maxViolations: Int = 4,
    val penaltiesEnabled: Boolean = true,
    val firstViolationPenaltyMinutes: Int = 1,
    val secondViolationPenaltyMinutes: Int = 3,
    val thirdViolationPenaltyMinutes: Int = 3,
    val subsequentViolationPenaltyMinutes: Int = 3,
    val timelapseRecordingEnabled: Boolean = true,
    val occlusionFreezeVisibilityAlways: Float = DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS,
    val occlusionFreezeVisibilityP10Always: Float = DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
    val occlusionFreezeVisibilityHard: Float = DEFAULT_OCCLUSION_FREEZE_VIS_HARD,
    val occlusionFreezeVisibilitySoft: Float = DEFAULT_OCCLUSION_FREEZE_VIS_SOFT,
    val occlusionJitterFreezeThreshold: Float = DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD,
    val wristDriftWeight: Float = DEFAULT_WRIST_DRIFT_WEIGHT,
    val poseSmootherMinCutoff: Float = PoseSmoother.DEFAULT_MIN_CUTOFF,
    val poseSmootherBeta: Float = PoseSmoother.DEFAULT_BETA,
    val poseSmootherDerivativeCutoff: Float = PoseSmoother.DEFAULT_DERIVATIVE_CUTOFF,
    val debugModeEnabled: Boolean = false,
    val customizeAudioEnabled: Boolean = false,
    val ttsVoiceMode: TtsVoiceMode = TtsVoiceMode.DefaultVoice,
    val customTtsTemplates: Map<TtsPhraseTemplate, String> = emptyMap(),
    val audioCueSettings: Map<AudioCue, AudioCueSettings> =
        AudioCue.entries.associateWith { AudioCueSettings() }
)

private fun GameSettings.toPoseOcclusionGuardConfig(): PoseOcclusionGuardConfig {
    return PoseOcclusionGuardConfig(
        freezeVisibilityAlways = occlusionFreezeVisibilityAlways,
        freezeVisibilityP10Always = occlusionFreezeVisibilityP10Always,
        freezeVisibilityHard = occlusionFreezeVisibilityHard,
        freezeVisibilitySoft = occlusionFreezeVisibilitySoft,
        jitterFreezeThreshold = occlusionJitterFreezeThreshold
    )
}

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
    val face: FaceOverlayState = FaceOverlayState(),
    val frozenLandmarkIndices: Set<Int> = emptySet(),
    val identityDebugText: String = ""
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
    private val startDelaySeconds = 10
    private val poseOcclusionCalibrationSeconds = 2
    private val stabilizationDurationMs = 4_000L
    private val gyroscopeStillThresholdRadPerSec = 0.08f

    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscopeSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var stabilizationStableSinceMs: Long? = null
    private var stabilizationCompleted = false
    private var stabilizationFallbackJob: Job? = null

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
    private val _audioCueEvents = MutableSharedFlow<AudioCueEvent>(extraBufferCapacity = 8)
    val audioCueEvents: SharedFlow<AudioCueEvent> = _audioCueEvents.asSharedFlow()
    private val _gameSettings = MutableStateFlow(loadSettings())
    val gameSettings: StateFlow<GameSettings> = _gameSettings.asStateFlow()
    private val _onboardingCompleted = MutableStateFlow(
        prefs.getBoolean(PREF_ONBOARDING_COMPLETED, false)
    )
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()
    private val _sessionSummary = MutableStateFlow<SessionSummary?>(null)
    val sessionSummary: StateFlow<SessionSummary?> = _sessionSummary.asStateFlow()

    private var sessionInitialTimerSeconds = defaultDurationSeconds
    private var sessionHoldingStartedAtElapsedMs: Long? = null
    private var sessionSettingsSnapshot = _gameSettings.value

    private data class AnalyzedPoseFrame(
        val pose: PoseLandmarks,
        val timestampMs: Long,
        val imageWidth: Int,
        val imageHeight: Int,
        val face: FaceOverlayState
    )

    private data class PendingPoseFrame(
        val bitmap: Bitmap,
        val processingGeneration: Long
    )

    private val faceDetectorService = FaceDetectorService(application.applicationContext, _gameSettings.value.faceDetectionConfidence)
    private val movementTracker = MovementTracker()
    private val poseIdentityStabilizer = PoseIdentityStabilizer()
    private val poseSmoother = PoseSmoother()
    private val poseOcclusionGuard = PoseOcclusionGuard()

    private val frameLock = Any()
    private val processingLock = Any()
    private var processingGeneration = 0L
    private val mediaPipeResultExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var isCleared = false
    private val pendingFrames = LinkedHashMap<Long, PendingPoseFrame>()
    private var latestAnalyzedFrame: AnalyzedPoseFrame? = null
    private var lastUsablePoseForDropoutHold: PoseLandmarks? = null
    private var lastUsablePoseTimestampMs: Long? = null
    private var consecutivePoseDropoutFrames: Int = 0
    private var rawPoseOkFrames = 0
    private var rawPoseMissingFrames = 0
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
        val mode = runCatching { FaceCheckMode.valueOf(prefs.getString("face_mode", FaceCheckMode.Disabled.name) ?: FaceCheckMode.Disabled.name) }
            .getOrDefault(FaceCheckMode.Disabled)
        val language = runCatching { AppLanguage.valueOf(prefs.getString("app_language", AppLanguage.English.name) ?: AppLanguage.English.name) }.getOrDefault(AppLanguage.English)
        val ttsVoiceMode = runCatching {
            TtsVoiceMode.valueOf(
                prefs.getString(PREF_TTS_VOICE_MODE, TtsVoiceMode.DefaultVoice.name)
                    ?: TtsVoiceMode.DefaultVoice.name
            )
        }.getOrDefault(TtsVoiceMode.DefaultVoice)
        val (driftThresholdFactor, motionThresholdFactor) = loadSensitivityThresholds()
        val occlusionFreezeVisibilityAlways =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_ALWAYS,
                DEFAULT_OCCLUSION_FREEZE_VIS_ALWAYS
            ).coerceIn(0f, 0.05f)
        val occlusionFreezeVisibilityP10Always =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
                DEFAULT_OCCLUSION_FREEZE_VIS_P10_ALWAYS
            ).coerceIn(0f, 0.05f)
        val occlusionFreezeVisibilityHard =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_HARD,
                DEFAULT_OCCLUSION_FREEZE_VIS_HARD
            ).coerceIn(0f, 0.10f)
        val occlusionFreezeVisibilitySoft =
            prefs.getFloat(
                PREF_OCCLUSION_FREEZE_VIS_SOFT,
                DEFAULT_OCCLUSION_FREEZE_VIS_SOFT
            ).coerceIn(0f, 0.20f)
        val occlusionJitterFreezeThreshold =
            prefs.getFloat(
                PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD,
                DEFAULT_OCCLUSION_JITTER_FREEZE_THRESHOLD
            ).coerceIn(0f, 0.30f)
        val wristDriftWeight = prefs.getFloat(
            PREF_WRIST_DRIFT_WEIGHT,
            DEFAULT_WRIST_DRIFT_WEIGHT
        ).coerceIn(0f, 1f)
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
            timelapseRecordingEnabled = prefs.getBoolean("timelapse_recording_enabled", true),
            occlusionFreezeVisibilityAlways = occlusionFreezeVisibilityAlways,
            occlusionFreezeVisibilityP10Always = occlusionFreezeVisibilityP10Always,
            occlusionFreezeVisibilityHard = occlusionFreezeVisibilityHard,
            occlusionFreezeVisibilitySoft = occlusionFreezeVisibilitySoft,
            occlusionJitterFreezeThreshold = occlusionJitterFreezeThreshold,
            wristDriftWeight = wristDriftWeight,
            poseSmootherMinCutoff = prefs.getFloat(
                PREF_POSE_SMOOTHER_MIN_CUTOFF,
                PoseSmoother.DEFAULT_MIN_CUTOFF
            ).coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE),
            poseSmootherBeta = prefs.getFloat(
                PREF_POSE_SMOOTHER_BETA,
                PoseSmoother.DEFAULT_BETA
            ).coerceIn(PoseSmoother.MIN_BETA_RANGE, PoseSmoother.MAX_BETA_RANGE),
            poseSmootherDerivativeCutoff = prefs.getFloat(
                PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF,
                PoseSmoother.DEFAULT_DERIVATIVE_CUTOFF
            ).coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE),
            debugModeEnabled = prefs.getBoolean(PREF_DEBUG_MODE_ENABLED, false),
            customizeAudioEnabled = prefs.getBoolean(PREF_CUSTOMIZE_AUDIO_ENABLED, false),
            ttsVoiceMode = ttsVoiceMode,
            customTtsTemplates = loadCustomTtsTemplates(),
            audioCueSettings = loadAudioCueSettings()
        )
    }

    private fun loadCustomTtsTemplates(): Map<TtsPhraseTemplate, String> =
        TtsPhraseTemplate.entries.mapNotNull { template ->
            val currentValue = prefs
                .getString(PREF_TTS_TEMPLATE_PREFIX + template.name, null)
                ?.takeIf { it.isNotBlank() }
            val legacyValue = template.audioCueForLegacyMigration()?.let { cue ->
                prefs.getString(LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX + cue.name, null)
                    ?.takeIf { it.isNotBlank() }
            }

            (currentValue ?: legacyValue)?.let { template to it }
        }.toMap()

    private fun TtsPhraseTemplate.audioCueForLegacyMigration(): AudioCue? = when (this) {
        TtsPhraseTemplate.PlaceDeviceStill -> AudioCue.PlaceDeviceStill
        TtsPhraseTemplate.TakePosition -> AudioCue.TakePosition
        TtsPhraseTemplate.TimeStartedHoldPosition -> AudioCue.TimeStartedHoldPosition
        TtsPhraseTemplate.TimeIsUp -> AudioCue.TimeIsUp
        TtsPhraseTemplate.DefeatTryAgain -> AudioCue.DefeatTryAgain
        TtsPhraseTemplate.MotionViolation -> AudioCue.MotionViolation
        TtsPhraseTemplate.DriftViolation -> AudioCue.DriftViolation
        TtsPhraseTemplate.ViolationRecorded -> AudioCue.ViolationRecorded
        TtsPhraseTemplate.FaceTurnedAway -> AudioCue.FaceTurnedAway
        TtsPhraseTemplate.FaceLookedAtCamera -> AudioCue.FaceLookedAtCamera
        TtsPhraseTemplate.PenaltyAddedToTimer -> null
    }

    private fun loadAudioCueSettings(): Map<AudioCue, AudioCueSettings> =
        AudioCue.entries.associateWith { cue ->
            val mode = runCatching {
                AudioCueMode.valueOf(
                    prefs.getString(PREF_AUDIO_CUE_MODE_PREFIX + cue.name, AudioCueMode.UseTts.name)
                        ?: AudioCueMode.UseTts.name
                )
            }.getOrDefault(AudioCueMode.UseTts)
            val defaultPcmSettings = PcmSignalSettings()
            val pcmChannel = runCatching {
                PcmChannel.valueOf(
                    prefs.getString(
                        PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX + cue.name,
                        defaultPcmSettings.channel.name
                    ) ?: defaultPcmSettings.channel.name
                )
            }.getOrDefault(defaultPcmSettings.channel)
            val pcmPattern = runCatching {
                PcmPattern.valueOf(
                    prefs.getString(
                        PREF_AUDIO_CUE_PCM_PATTERN_PREFIX + cue.name,
                        defaultPcmSettings.pattern.name
                    ) ?: defaultPcmSettings.pattern.name
                )
            }.getOrDefault(defaultPcmSettings.pattern)
            AudioCueSettings(
                mode = mode,
                audioFileUri = prefs.getString(PREF_AUDIO_CUE_URI_PREFIX + cue.name, null),
                pcmSettings = PcmSignalSettings(
                    frequencyHz = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX + cue.name,
                        defaultPcmSettings.frequencyHz
                    ).coerceIn(20, 20_000),
                    durationSeconds = prefs.getFloat(
                        PREF_AUDIO_CUE_PCM_DURATION_PREFIX + cue.name,
                        defaultPcmSettings.durationSeconds
                    ).takeIf(Float::isFinite)
                        ?.coerceIn(0.05f, 10.0f)
                        ?: defaultPcmSettings.durationSeconds,
                    channel = pcmChannel,
                    amplitudePercent = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX + cue.name,
                        defaultPcmSettings.amplitudePercent
                    ).coerceIn(0, 100),
                    fadeInMs = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX + cue.name,
                        defaultPcmSettings.fadeInMs
                    ).coerceIn(0, 5_000),
                    fadeOutMs = prefs.getInt(
                        PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX + cue.name,
                        defaultPcmSettings.fadeOutMs
                    ).coerceIn(0, 5_000),
                    pattern = pcmPattern
                )
            )
        }

    private fun loadSensitivityThresholds(): Pair<Float, Float> {
        val rawDrift = prefs.getFloat("pose_drift_factor_v2", 0.160f).coerceIn(0.05f, 0.40f)
        val rawMotion = prefs.getFloat("pose_motion_factor_v2", 0.04f).coerceIn(0.02f, 0.25f)
        val presetsVersion = prefs.getInt(PREF_SENSITIVITY_PRESETS_VERSION, 1)

        if (presetsVersion < CURRENT_SENSITIVITY_PRESETS_VERSION) {
            val (migratedDrift, migratedMotion) = migrateLegacySensitivityThresholds(rawDrift, rawMotion)
            prefs.edit()
                .putFloat("pose_drift_factor_v2", migratedDrift)
                .putFloat("pose_motion_factor_v2", migratedMotion)
                .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
                .apply()
            return migratedDrift to migratedMotion
        }

        return normalizeDriftThreshold(rawDrift) to normalizeMotionThreshold(rawMotion)
    }

    private fun migrateLegacySensitivityThresholds(drift: Float, motion: Float): Pair<Float, Float> {
        return when {
            abs(drift - 0.12f) < 0.001f && abs(motion - 0.06f) < 0.001f -> 0.160f to 0.04f
            else -> normalizeDriftThreshold(drift) to normalizeMotionThreshold(motion)
        }
    }


    private fun normalizeDriftThreshold(drift: Float): Float {
        val presets = listOf(0.200f, 0.180f, 0.160f, 0.140f, 0.120f)
        return presets.firstOrNull { preset -> abs(preset - drift) < 0.001f } ?: 0.160f
    }

    private fun normalizeMotionThreshold(motion: Float): Float {
        val presets = listOf(0.08f, 0.06f, 0.04f, 0.03f, 0.02f)
        return presets.firstOrNull { preset -> abs(preset - motion) < 0.001f } ?: 0.04f
    }

    private fun applySettingsToEngines(settings: GameSettings) {
        synchronized(processingLock) {
            movementTracker.driftThresholdFactor = settings.driftThresholdFactor
            movementTracker.motionThresholdFactor = settings.motionThresholdFactor
            movementTracker.wristDriftWeight = settings.wristDriftWeight
            poseSmoother.updateConfig(
                minCutoff = settings.poseSmootherMinCutoff,
                beta = settings.poseSmootherBeta,
                derivativeCutoff = settings.poseSmootherDerivativeCutoff
            )
            poseOcclusionGuard.updateConfig(settings.toPoseOcclusionGuardConfig())
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
        prefs.edit()
            .putFloat("pose_drift_factor_v2", normalized)
            .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
            .apply()
        synchronized(processingLock) { movementTracker.driftThresholdFactor = normalized }
    }

    fun updateMotionThresholdFactor(value: Float) {
        val normalized = value.coerceIn(0.02f, 0.25f)
        _gameSettings.value = _gameSettings.value.copy(motionThresholdFactor = normalized)
        prefs.edit()
            .putFloat("pose_motion_factor_v2", normalized)
            .putInt(PREF_SENSITIVITY_PRESETS_VERSION, CURRENT_SENSITIVITY_PRESETS_VERSION)
            .apply()
        synchronized(processingLock) { movementTracker.motionThresholdFactor = normalized }
    }

    fun updateWristDriftWeight(value: Float) {
        val normalized = value.coerceIn(0f, 1f)
        _gameSettings.value = _gameSettings.value.copy(wristDriftWeight = normalized)
        prefs.edit().putFloat(PREF_WRIST_DRIFT_WEIGHT, normalized).apply()
        synchronized(processingLock) {
            movementTracker.wristDriftWeight = normalized
        }
    }

    fun updatePoseSmootherMinCutoff(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_MIN_CUTOFF,
            normalized = value.coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE)
        ) { copy(poseSmootherMinCutoff = it) }
    }

    fun updatePoseSmootherBeta(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_BETA,
            normalized = value.coerceIn(PoseSmoother.MIN_BETA_RANGE, PoseSmoother.MAX_BETA_RANGE)
        ) { copy(poseSmootherBeta = it) }
    }

    fun updatePoseSmootherDerivativeCutoff(value: Float) {
        updatePoseSmootherConfig(
            prefKey = PREF_POSE_SMOOTHER_DERIVATIVE_CUTOFF,
            normalized = value.coerceIn(PoseSmoother.MIN_CUTOFF_RANGE, PoseSmoother.MAX_CUTOFF_RANGE)
        ) { copy(poseSmootherDerivativeCutoff = it) }
    }

    private fun updatePoseSmootherConfig(
        prefKey: String,
        normalized: Float,
        apply: GameSettings.(Float) -> GameSettings
    ) {
        val updated = _gameSettings.value.apply(normalized)
        _gameSettings.value = updated
        prefs.edit().putFloat(prefKey, normalized).apply()
        synchronized(processingLock) {
            poseSmoother.updateConfig(
                minCutoff = updated.poseSmootherMinCutoff,
                beta = updated.poseSmootherBeta,
                derivativeCutoff = updated.poseSmootherDerivativeCutoff
            )
        }
    }

    fun updateOcclusionFreezeVisibilityAlways(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_ALWAYS,
            normalized = value.coerceIn(0f, 0.05f)
        ) { copy(occlusionFreezeVisibilityAlways = it) }
    }

    fun updateOcclusionFreezeVisibilityP10Always(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_P10_ALWAYS,
            normalized = value.coerceIn(0f, 0.05f)
        ) { copy(occlusionFreezeVisibilityP10Always = it) }
    }

    fun updateOcclusionFreezeVisibilityHard(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_HARD,
            normalized = value.coerceIn(0f, 0.10f)
        ) { copy(occlusionFreezeVisibilityHard = it) }
    }

    fun updateOcclusionFreezeVisibilitySoft(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_FREEZE_VIS_SOFT,
            normalized = value.coerceIn(0f, 0.20f)
        ) { copy(occlusionFreezeVisibilitySoft = it) }
    }

    fun updateOcclusionJitterFreezeThreshold(value: Float) {
        updatePoseOcclusionConfig(
            prefKey = PREF_OCCLUSION_JITTER_FREEZE_THRESHOLD,
            normalized = value.coerceIn(0f, 0.30f)
        ) { copy(occlusionJitterFreezeThreshold = it) }
    }

    private fun updatePoseOcclusionConfig(
        prefKey: String,
        normalized: Float,
        apply: GameSettings.(Float) -> GameSettings
    ) {
        val updated = _gameSettings.value.apply(normalized)
        _gameSettings.value = updated
        prefs.edit().putFloat(prefKey, normalized).apply()
        synchronized(processingLock) {
            poseOcclusionGuard.updateConfig(updated.toPoseOcclusionGuardConfig())
        }
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

    fun markOnboardingCompleted() {
        _onboardingCompleted.value = true
        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
    }

    fun updatePenaltiesEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(penaltiesEnabled = enabled)
        prefs.edit().putBoolean("penalties_enabled", enabled).apply()
    }

    fun updateTtsVoiceMode(mode: TtsVoiceMode) {
        _gameSettings.value = _gameSettings.value.copy(ttsVoiceMode = mode)
        prefs.edit().putString(PREF_TTS_VOICE_MODE, mode.name).apply()
    }

    fun updateTtsPhraseTemplate(template: TtsPhraseTemplate, customText: String?) {
        val normalized = customText?.takeIf { it.isNotBlank() }
        _gameSettings.value = _gameSettings.value.copy(
            customTtsTemplates = if (normalized == null) {
                _gameSettings.value.customTtsTemplates - template
            } else {
                _gameSettings.value.customTtsTemplates + (template to normalized)
            }
        )

        prefs.edit().apply {
            if (normalized == null) {
                remove(PREF_TTS_TEMPLATE_PREFIX + template.name)
            } else {
                putString(PREF_TTS_TEMPLATE_PREFIX + template.name, normalized)
            }
            template.audioCueForLegacyMigration()?.let { cue ->
                remove(LEGACY_PREF_AUDIO_CUE_TTS_TEXT_PREFIX + cue.name)
            }
        }.apply()
    }

    fun updateCustomizeAudioEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(customizeAudioEnabled = enabled)
        prefs.edit().putBoolean(PREF_CUSTOMIZE_AUDIO_ENABLED, enabled).apply()
    }

    fun updateAudioCueSettings(cue: AudioCue, settings: AudioCueSettings) {
        _gameSettings.value = _gameSettings.value.copy(
            audioCueSettings = _gameSettings.value.audioCueSettings + (cue to settings)
        )
        prefs.edit()
            .putString(PREF_AUDIO_CUE_MODE_PREFIX + cue.name, settings.mode.name)
            .apply {
                if (settings.audioFileUri == null) {
                    remove(PREF_AUDIO_CUE_URI_PREFIX + cue.name)
                } else {
                    putString(PREF_AUDIO_CUE_URI_PREFIX + cue.name, settings.audioFileUri)
                }
            }
            .putInt(PREF_AUDIO_CUE_PCM_FREQUENCY_PREFIX + cue.name, settings.pcmSettings.frequencyHz)
            .putFloat(PREF_AUDIO_CUE_PCM_DURATION_PREFIX + cue.name, settings.pcmSettings.durationSeconds)
            .putString(PREF_AUDIO_CUE_PCM_CHANNEL_PREFIX + cue.name, settings.pcmSettings.channel.name)
            .putInt(PREF_AUDIO_CUE_PCM_AMPLITUDE_PREFIX + cue.name, settings.pcmSettings.amplitudePercent)
            .putInt(PREF_AUDIO_CUE_PCM_FADE_IN_PREFIX + cue.name, settings.pcmSettings.fadeInMs)
            .putInt(PREF_AUDIO_CUE_PCM_FADE_OUT_PREFIX + cue.name, settings.pcmSettings.fadeOutMs)
            .putString(PREF_AUDIO_CUE_PCM_PATTERN_PREFIX + cue.name, settings.pcmSettings.pattern.name)
            .apply()
    }

    fun updateTimelapseRecordingEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(timelapseRecordingEnabled = enabled)
        prefs.edit().putBoolean("timelapse_recording_enabled", enabled).apply()
    }

    fun updateDebugModeEnabled(enabled: Boolean) {
        _gameSettings.value = _gameSettings.value.copy(debugModeEnabled = enabled)
        prefs.edit().putBoolean(PREF_DEBUG_MODE_ENABLED, enabled).apply()
    }

    fun resetPoseInputContinuity() {
        if (_gameState.value !in setOf(GameState.Idle, GameState.Failed, GameState.Success)) return
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
        }
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

    fun updateSelectedDurationSeconds(seconds: Int) {
        val normalizedSeconds = seconds.coerceAtLeast(1)
        _selectedDurationSeconds.value = normalizedSeconds

        if (
            _gameState.value == GameState.Idle ||
            _gameState.value == GameState.Failed ||
            _gameState.value == GameState.Success
        ) {
            _timerSeconds.value = normalizedSeconds
        }
    }

    fun updateSelectedDurationMinutes(minutes: Int) {
        updateSelectedDurationSeconds(minutes * 60)
    }

    fun registerCameraFrame(bitmap: Bitmap, timestampMs: Long) {
        val generation = synchronized(processingLock) { processingGeneration }
        val pendingFrame = PendingPoseFrame(bitmap, generation)
        val removedBitmaps = mutableListOf<Bitmap>()
        synchronized(frameLock) {
            val previous = pendingFrames.put(timestampMs, pendingFrame)
            if (previous != null && previous.bitmap !== bitmap) {
                removedBitmaps.add(previous.bitmap)
            }

            while (pendingFrames.size > 20) {
                val oldestTimestamp = pendingFrames.keys.first()
                val removed = pendingFrames.remove(oldestTimestamp)
                if (removed != null && removed.bitmap !== bitmap) {
                    removedBitmaps.add(removed.bitmap)
                }
            }

            val minTs = timestampMs - 3000
            val iterator = pendingFrames.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key < minTs) {
                    val removed = entry.value
                    iterator.remove()
                    if (removed.bitmap !== bitmap) {
                        removedBitmaps.add(removed.bitmap)
                    }
                }
            }
        }
        removedBitmaps.forEach { it.recycleIfNeeded() }
    }

    fun dropCameraFrame(timestampMs: Long, recycle: Boolean) {
        val frame = synchronized(frameLock) { pendingFrames.remove(timestampMs) }
        if (recycle) frame?.bitmap?.recycleIfNeeded()
    }

    fun clearCameraFrameCache(recycle: Boolean) {
        val bitmaps = synchronized(frameLock) {
            val values = pendingFrames.values.map { it.bitmap }
            pendingFrames.clear()
            values
        }
        if (recycle) bitmaps.forEach { it.recycleIfNeeded() }
    }


    fun buildPoseDebugSnapshotJson(): String? {
        val frame = synchronized(frameLock) { latestAnalyzedFrame } ?: return null
        val landmarks = JSONArray()
        MovementTracker.TRAINING_POSE_LANDMARK_INDICES.forEach { index ->
            val point = frame.pose.landmark(index)
            landmarks.put(
                JSONObject()
                    .put("index", index)
                    .put("position", index)
                    .put("name", MovementTracker.poseLandmarkName(index))
                    .put("x", point?.x ?: JSONObject.NULL)
                    .put("y", point?.y ?: JSONObject.NULL)
                    .put("z", point?.z ?: JSONObject.NULL)
                    .put("visibility", point?.visibility ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("timestampMs", frame.timestampMs)
            .put("imageWidth", frame.imageWidth)
            .put("imageHeight", frame.imageHeight)
            .put("landmarks", landmarks)
            .toString(2)
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
        val matchedFrame = synchronized(frameLock) { pendingFrames.remove(timestamp) }
        val matchedBitmap = matchedFrame?.bitmap
        val stabilizedFrame = synchronized(processingLock) {
            if (matchedFrame != null && matchedFrame.processingGeneration != processingGeneration) {
                null
            } else {
                val rawPoseMissing = rawPose.allLandmarks.size < 33
                if (rawPoseMissing) {
                    rawPoseMissingFrames += 1
                } else {
                    rawPoseOkFrames += 1
                }
                val dropoutHoldPose = if (rawPoseMissing) poseForDropoutHold(timestamp) else null
                if (dropoutHoldPose != null) {
                    Triple(
                        processingGeneration,
                        dropoutHoldPose,
                        buildPoseDropoutDebugText(
                            frames = consecutivePoseDropoutFrames,
                            ageMs = lastUsablePoseTimestampMs?.let { timestamp - it },
                            holding = true
                        )
                    )
                } else if (rawPoseMissing) {
                    consecutivePoseDropoutFrames += 1
                    poseIdentityStabilizer.reset()
                    poseSmoother.reset()
                    Triple(
                        processingGeneration,
                        rawPose,
                        buildPoseDropoutDebugText(
                            frames = consecutivePoseDropoutFrames,
                            ageMs = null,
                            holding = false
                        )
                    )
                } else {
                    consecutivePoseDropoutFrames = 0
                    val identityResult = poseIdentityStabilizer.stabilize(rawPose, timestamp)
                    val smoothedPose = poseSmoother.smooth(identityResult.pose, timestamp)
                    if (identityResult.accepted && smoothedPose.allLandmarks.size >= 33) {
                        lastUsablePoseForDropoutHold = smoothedPose
                        lastUsablePoseTimestampMs = timestamp
                    }
                    val identityDebugText = buildIdentityDebugText(identityResult)
                    val shouldCollectOcclusionCalibration =
                        _gameState.value == GameState.StartingDelay &&
                            _startDelayRemainingSeconds.value in 1..poseOcclusionCalibrationSeconds
                    if (shouldCollectOcclusionCalibration) {
                        poseOcclusionGuard.addCalibrationFrame(smoothedPose, timestamp)
                    }
                    Triple(processingGeneration, smoothedPose, identityDebugText)
                }
            }
        }
        if (stabilizedFrame == null) {
            matchedBitmap?.recycleIfNeeded()
            return
        }
        val (frameGeneration, pose, identityDebugText) = stabilizedFrame
        if (isCleared) {
            matchedBitmap?.recycleIfNeeded()
            return
        }
        Log.v(tag, "MediaPipe frame ts=$timestamp size=${imageWidth}x$imageHeight landmarks=${pose.allLandmarks.size}")
        val nextOverlayState = try {
            if (matchedBitmap == null) {
                buildOverlayStateWithoutFace(pose, imageWidth, imageHeight, identityDebugText)
            } else {
                buildOverlayState(matchedBitmap, pose, timestamp, identityDebugText)
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
            val isHoldingPose = _gameState.value == GameState.HoldingPose
            if (!isHoldingPose) {
                _poseOverlayState.value = nextOverlayState.copy(frozenLandmarkIndices = emptySet())
                return
            }

            val trackingPose = poseOcclusionGuard.applyForTracking(pose, timestamp)
            _poseOverlayState.value = nextOverlayState.copy(
                frozenLandmarkIndices = poseOcclusionGuard.activeFrozenIndices()
            )

            if (!pose.hasEnoughKeypoints()) {
                resetMovementGaugeState()
                val trackingResult = movementTracker.trackFrame(trackingPose, timestamp)
                if (handleMovementViolation(trackingResult.violation, pose)) return
                processFaceRule(nextOverlayState.face.status, pose)
                return
            }

            val trackingResult = movementTracker.trackFrame(trackingPose, timestamp)
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
            triggerDefeat(defeatReason)
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
        }

        if (type == RuleViolationType.FaceNotMatchingMode) {
            val statusText = if (_gameSettings.value.penaltiesEnabled) {
                tr(R.string.face_rule_violated_with_penalty, minutes)
            } else {
                tr(R.string.face_rule_violated)
            }
            val prefixTemplate = if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera) {
                TtsPhraseTemplate.FaceTurnedAway
            } else {
                TtsPhraseTemplate.FaceLookedAtCamera
            }
            _statusMessage.value = statusText
            val cue = if (_gameSettings.value.faceCheckMode == FaceCheckMode.FaceToCamera) {
                AudioCue.FaceTurnedAway
            } else {
                AudioCue.FaceLookedAtCamera
            }
            val cueText = if (_gameSettings.value.penaltiesEnabled) {
                "${ttsText(prefixTemplate)}. ${ttsText(TtsPhraseTemplate.PenaltyAddedToTimer, minutes)}"
            } else {
                ttsText(prefixTemplate)
            }
            playAudioCue(cue, cueText)
            return
        }

        val (cue, voiceTemplate) = when (type) {
            RuleViolationType.Motion -> AudioCue.MotionViolation to TtsPhraseTemplate.MotionViolation
            RuleViolationType.Drift -> AudioCue.DriftViolation to TtsPhraseTemplate.DriftViolation
            else -> AudioCue.ViolationRecorded to TtsPhraseTemplate.ViolationRecorded
        }

        if (_gameSettings.value.penaltiesEnabled) {
            _statusMessage.value = tr(R.string.violation_recorded_with_penalty, minutes)
            playAudioCue(
                cue,
                "${ttsText(voiceTemplate)}. ${ttsText(TtsPhraseTemplate.PenaltyAddedToTimer, minutes)}"
            )
        } else {
            _statusMessage.value = tr(R.string.violation_recorded)
            playAudioCue(cue, ttsText(voiceTemplate))
        }
    }

    private fun buildOverlayState(
        bitmap: Bitmap,
        pose: PoseLandmarks,
        timestamp: Long,
        identityDebugText: String
    ): PoseOverlayState { /* trimmed from old implementation */
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
        return PoseOverlayState(bitmap.width, bitmap.height, pose.allLandmarks, normalizedRect, faceOverlayState, identityDebugText = identityDebugText)
    }

    private fun buildOverlayStateWithoutFace(
        pose: PoseLandmarks,
        imageWidth: Int,
        imageHeight: Int,
        identityDebugText: String
    ): PoseOverlayState {
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
        return PoseOverlayState(safeWidth, safeHeight, pose.allLandmarks, normalizedRect, faceOverlayState, identityDebugText = identityDebugText)
    }

    private fun resetPoseDropoutHoldState() {
        lastUsablePoseForDropoutHold = null
        lastUsablePoseTimestampMs = null
        consecutivePoseDropoutFrames = 0
        rawPoseOkFrames = 0
        rawPoseMissingFrames = 0
    }

    private fun poseForDropoutHold(timestampMs: Long): PoseLandmarks? {
        val heldPose = lastUsablePoseForDropoutHold ?: return null
        val heldTimestamp = lastUsablePoseTimestampMs ?: return null
        val nextDropoutFrames = consecutivePoseDropoutFrames + 1
        val ageMs = timestampMs - heldTimestamp
        if (nextDropoutFrames > MAX_POSE_DROPOUT_HOLD_FRAMES || ageMs > MAX_POSE_DROPOUT_HOLD_MS) {
            return null
        }
        consecutivePoseDropoutFrames = nextDropoutFrames
        return heldPose
    }

    private fun buildPoseDropoutDebugText(frames: Int, ageMs: Long?, holding: Boolean): String {
        val statusText = if (holding) "hold(no_pose)" else "rejected(no_pose)"
        val ageText = if (holding && ageMs != null) " age=${ageMs.coerceAtLeast(0L)}ms" else ""
        return "identity: $statusText frames=$frames$ageText rawOk=$rawPoseOkFrames rawMiss=$rawPoseMissingFrames"
    }

    private fun buildIdentityDebugText(identityResult: PoseIdentityStabilizationResult): String {
        val ambiguousText = if (identityResult.ambiguous) " ambiguous" else ""
        val acceptanceText = when {
            identityResult.outlier -> " outlier(${identityResult.outlierReason})"
            identityResult.accepted -> " accepted"
            else -> " rejected(${identityResult.rejectReason})"
        }
        val detailsText = if (identityResult.debugDetails.isBlank()) "" else " ${identityResult.debugDetails}"
        return String.format(
            Locale.US,
            "identity: %s%s%s d=%.3f s=%.3f%s",
            identityResult.transform.name,
            ambiguousText,
            acceptanceText,
            identityResult.directScore,
            identityResult.swappedScore,
            detailsText
        )
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

    private fun sessionElapsedSecondsForSummary(): Int {
        val startedAt = sessionHoldingStartedAtElapsedMs ?: return 0
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000L)
            .coerceAtLeast(0L)
        return elapsedSeconds.toInt()
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
                    actualTimerSeconds = sessionElapsedSecondsForSummary(),
                    violationCounts = _ruleViolationCounts.value,
                    settings = sessionSettingsSnapshot
                )
                _gameState.value = GameState.Success
                resetMovementGaugeState()
                _statusMessage.value = tr(R.string.victory)
                playAudioCue(AudioCue.TimeIsUp, ttsText(TtsPhraseTemplate.TimeIsUp))
            }
        }
    }

    fun startSession() {
        if (_gameState.value != GameState.Idle && _gameState.value != GameState.Failed && _gameState.value != GameState.Success) return

        _sessionSummary.value = null
        sessionInitialTimerSeconds = _selectedDurationSeconds.value
        sessionHoldingStartedAtElapsedMs = null
        sessionSettingsSnapshot = _gameSettings.value
        _timerSeconds.value = _selectedDurationSeconds.value
        _defeatReason.value = ""
        resetMovementGaugeState()
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            movementTracker.reset()
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            currentViolationCount = 0
            _violationCount.value = 0
            resetRuleViolationCounts()
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
        }
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()

        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _gameState.value = GameState.WaitingForStabilization
        _statusMessage.value = tr(R.string.place_device_still)
        playAudioCue(AudioCue.PlaceDeviceStill, ttsText(TtsPhraseTemplate.PlaceDeviceStill))
        sensorManager.unregisterListener(this)

        val gyroscopeRegistered = gyroscopeSensor?.let { sensor ->
            runCatching {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }.onFailure { error ->
                Log.w(tag, "Unable to register gyroscope listener; using timed stabilization", error)
            }.getOrDefault(false)
        } ?: false
        if (!gyroscopeRegistered) {
            stabilizationFallbackJob = viewModelScope.launch {
                delay(stabilizationDurationMs)
                completeDeviceStabilization()
            }
        }
    }

    private fun startPoseCountdownAfterDeviceStabilized() {
        playAudioCue(AudioCue.TakePosition, ttsText(TtsPhraseTemplate.TakePosition))
        synchronized(processingLock) {
            poseOcclusionGuard.reset()
        }
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
            sessionHoldingStartedAtElapsedMs = SystemClock.elapsedRealtime()
            synchronized(processingLock) {
                processingGeneration += 1
                movementTracker.reset()
                currentViolationCount = 0
                _violationCount.value = 0
                resetRuleViolationCounts()
                lastPenaltyAtMs = 0L
                consecutiveFaceFailFrames = 0
                poseOcclusionGuard.finishCalibration(
                    referencePose = initialPose,
                    referenceTimestampMs = analyzedFrame.timestampMs
                )
                val guardedInitialPose = poseOcclusionGuard.buildReferencePose(initialPose)
                movementTracker.startTracking(guardedInitialPose)
            }
            _gameState.value = GameState.HoldingPose
            _statusMessage.value = tr(R.string.time_started_hold_position)
            startTimerLoop()
            playAudioCue(
                AudioCue.TimeStartedHoldPosition,
                ttsText(TtsPhraseTemplate.TimeStartedHoldPosition)
            )
        }
    }

    private fun completeDeviceStabilization() {
        if (_gameState.value != GameState.WaitingForStabilization || stabilizationCompleted) return
        stabilizationCompleted = true
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
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

    fun triggerDefeat(reason: String) {
        val alreadyFailed = _gameState.value == GameState.Failed
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
        }
        resetMovementGaugeState()
        _sessionSummary.value = SessionSummary(
            result = GameState.Failed,
            initialTimerSeconds = sessionInitialTimerSeconds,
            actualTimerSeconds = sessionElapsedSecondsForSummary(),
            violationCounts = _ruleViolationCounts.value,
            settings = sessionSettingsSnapshot,
            defeatReason = reason
        )
        _gameState.value = GameState.Failed
        _defeatReason.value = reason
        _statusMessage.value = tr(R.string.check_failed)
        if (!alreadyFailed) {
            playAudioCue(AudioCue.DefeatTryAgain, ttsText(TtsPhraseTemplate.DefeatTryAgain))
        }
    }

    private fun defaultTtsTemplateText(template: TtsPhraseTemplate): String = when (template) {
        TtsPhraseTemplate.PlaceDeviceStill -> tr(R.string.place_device_still)
        TtsPhraseTemplate.TakePosition -> tr(R.string.take_position)
        TtsPhraseTemplate.TimeStartedHoldPosition -> tr(R.string.time_started_hold_position)
        TtsPhraseTemplate.TimeIsUp -> tr(R.string.time_is_up)
        TtsPhraseTemplate.DefeatTryAgain -> tr(R.string.defeat_try_again)
        TtsPhraseTemplate.MotionViolation -> tr(R.string.motion_violation_voice)
        TtsPhraseTemplate.DriftViolation -> tr(R.string.drift_violation_voice)
        TtsPhraseTemplate.ViolationRecorded -> tr(R.string.violation_recorded)
        TtsPhraseTemplate.FaceTurnedAway -> tr(R.string.you_turned_away)
        TtsPhraseTemplate.FaceLookedAtCamera -> tr(R.string.you_looked_at_camera)
        TtsPhraseTemplate.PenaltyAddedToTimer -> tr(R.string.penalty_added_to_timer_template)
    }

    private fun ttsText(template: TtsPhraseTemplate): String =
        _gameSettings.value.customTtsTemplates[template]
            ?.takeIf { it.isNotBlank() }
            ?: defaultTtsTemplateText(template)

    private fun ttsText(template: TtsPhraseTemplate, minutes: Int): String =
        ttsText(template).replace("{minutes}", minutes.toString())

    private fun playAudioCue(cue: AudioCue, ttsText: String) {
        _audioCueEvents.tryEmit(AudioCueEvent(cue, ttsText))
    }

    fun dismissFinalScreen() {
        if (_gameState.value != GameState.Success && _gameState.value != GameState.Failed) return
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            currentViolationCount = 0
            _violationCount.value = 0
            resetRuleViolationCounts()
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
        }
        _sessionSummary.value = null
        resetMovementGaugeState()
        sessionHoldingStartedAtElapsedMs = null
        _gameState.value = GameState.Idle
        _statusMessage.value = tr(R.string.status_initial)
        _defeatReason.value = ""
        _timerSeconds.value = _selectedDurationSeconds.value
    }

    fun stopSession() {
        startDelayJob?.cancel()
        timerJob?.cancel()
        stabilizationFallbackJob?.cancel()
        stabilizationFallbackJob = null
        sensorManager.unregisterListener(this)
        stabilizationStableSinceMs = null
        stabilizationCompleted = false
        _startDelayRemainingSeconds.value = 0
        synchronized(processingLock) {
            processingGeneration += 1
            poseIdentityStabilizer.reset()
            poseSmoother.reset()
            movementTracker.reset()
            poseOcclusionGuard.reset()
            resetPoseDropoutHoldState()
            currentViolationCount = 0
            _violationCount.value = 0
            resetRuleViolationCounts()
            lastPenaltyAtMs = 0L
            consecutiveFaceFailFrames = 0
        }
        _sessionSummary.value = null
        resetMovementGaugeState()
        sessionHoldingStartedAtElapsedMs = null
        _gameState.value = GameState.Idle
        _statusMessage.value = tr(R.string.status_initial)
        _defeatReason.value = ""
        _timerSeconds.value = _selectedDurationSeconds.value
    }
    override fun onCleared() { isCleared = true; stabilizationFallbackJob?.cancel(); stabilizationFallbackJob = null; sensorManager.unregisterListener(this); stabilizationStableSinceMs = null; stabilizationCompleted = false; synchronized(processingLock) { processingGeneration += 1; poseIdentityStabilizer.reset(); poseSmoother.reset(); movementTracker.reset(); poseOcclusionGuard.reset(); resetPoseDropoutHoldState() }; clearCameraFrameCache(recycle = true); mediaPipeResultExecutor.shutdownNow(); runCatching { mediaPipeResultExecutor.awaitTermination(200, TimeUnit.MILLISECONDS) }; faceDetectorService.close(); super.onCleared() }
}

private fun Bitmap.recycleIfNeeded() {
    if (!isRecycled) recycle()
}
