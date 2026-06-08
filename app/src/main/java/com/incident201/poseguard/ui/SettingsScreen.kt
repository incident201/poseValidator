package com.incident201.poseguard.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.incident201.poseguard.R
import com.incident201.poseguard.audio.AudioCue
import com.incident201.poseguard.audio.AudioCueMode
import com.incident201.poseguard.audio.AudioCueSettings
import com.incident201.poseguard.audio.PcmChannel
import com.incident201.poseguard.audio.PcmPattern
import com.incident201.poseguard.audio.PcmSignalPlayer
import com.incident201.poseguard.audio.PcmSignalSettings
import com.incident201.poseguard.viewmodel.AppLanguage
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun SettingsScreen(
    settings: GameSettings,
    onClose: () -> Unit,
    onFaceModeChanged: (FaceCheckMode) -> Unit,
    onDriftChanged: (Float) -> Unit,
    onMotionChanged: (Float) -> Unit,
    onPenaltyIntervalChanged: (Int) -> Unit,
    onMaxViolationsChanged: (Int) -> Unit,
    onPenaltiesEnabledChanged: (Boolean) -> Unit,
    onFirstViolationPenaltyChanged: (Int) -> Unit,
    onSecondViolationPenaltyChanged: (Int) -> Unit,
    onThirdViolationPenaltyChanged: (Int) -> Unit,
    onSubsequentViolationPenaltyChanged: (Int) -> Unit,
    onLanguageChanged: (AppLanguage) -> Unit,
    onTimelapseRecordingEnabledChanged: (Boolean) -> Unit,
    onDebugModeEnabledChanged: (Boolean) -> Unit,
    onOcclusionFreezeVisibilityAlwaysChanged: (Float) -> Unit,
    onOcclusionFreezeVisibilityP10AlwaysChanged: (Float) -> Unit,
    onOcclusionFreezeVisibilityHardChanged: (Float) -> Unit,
    onOcclusionFreezeVisibilitySoftChanged: (Float) -> Unit,
    onOcclusionJitterFreezeThresholdChanged: (Float) -> Unit,
    onPoseSmootherMinCutoffChanged: (Float) -> Unit,
    onPoseSmootherBetaChanged: (Float) -> Unit,
    onPoseSmootherDerivativeCutoffChanged: (Float) -> Unit,
    onWristDriftWeightChanged: (Float) -> Unit,
    onCustomizeAudioEnabledChanged: (Boolean) -> Unit,
    onAudioCueSettingsChanged: (AudioCue, AudioCueSettings) -> Unit,
    onShowInstructions: () -> Unit
 ) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedDriftTolerance = driftTolerancePresetFor(settings.driftThresholdFactor)
    val selectedMotionSensitivity = motionSensitivityPresetFor(settings.motionThresholdFactor)
    var showAudioSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showAudioSettings) {
        showAudioSettings = false
    }

    if (showAudioSettings) {
        CustomizeAudioSettingsScreen(
            settings = settings,
            onBack = { showAudioSettings = false },
            onEnabledChanged = onCustomizeAudioEnabledChanged,
            onSettingsChanged = onAudioCueSettingsChanged,
            colorScheme = colorScheme
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = localizedString(settings.language, R.string.back),
                    tint = colorScheme.onBackground
                )
            }
            Text(
                localizedString(settings.language, R.string.settings),
                color = colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        LanguageSelectorCard(
            language = settings.language,
            onLanguageChanged = onLanguageChanged,
            colorScheme = colorScheme
        )
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.face_detection), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                listOf(
                    localizedString(settings.language, R.string.face_away) to FaceCheckMode.FaceAwayFromCamera,
                    localizedString(settings.language, R.string.face_to_camera) to FaceCheckMode.FaceToCamera,
                    localizedString(settings.language, R.string.do_not_check) to FaceCheckMode.Disabled
                ).forEach { (label, mode) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onFaceModeChanged(mode) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = settings.faceCheckMode == mode, onClick = { onFaceModeChanged(mode) })
                        Text(
                            label,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.maximum_violations), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                IntegerSettingField(
                    label = localizedString(settings.language, R.string.range_0_9999),
                    value = settings.maxViolations,
                    onValueChanged = onMaxViolationsChanged,
                    min = 0,
                    max = 9999
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.min_interval_errors), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                IntegerSettingField(
                    label = localizedString(settings.language, R.string.seconds_0_30),
                    value = settings.minimumPenaltyIntervalSeconds,
                    onValueChanged = onPenaltyIntervalChanged,
                    min = 0,
                    max = 30
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(localizedString(settings.language, R.string.penalty_for_violation), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (settings.penaltiesEnabled) localizedString(settings.language, R.string.on) else localizedString(settings.language, R.string.off),
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = settings.penaltiesEnabled, onCheckedChange = onPenaltiesEnabledChanged)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.alpha(if (settings.penaltiesEnabled) 1f else 0.5f)) {
                    IntegerSettingField(localizedString(settings.language, R.string.first_violation_min), settings.firstViolationPenaltyMinutes, onFirstViolationPenaltyChanged, 0, 9999, enabled = settings.penaltiesEnabled)
                    IntegerSettingField(localizedString(settings.language, R.string.second_violation_min), settings.secondViolationPenaltyMinutes, onSecondViolationPenaltyChanged, 0, 9999, enabled = settings.penaltiesEnabled)
                    IntegerSettingField(localizedString(settings.language, R.string.third_violation_min), settings.thirdViolationPenaltyMinutes, onThirdViolationPenaltyChanged, 0, 9999, enabled = settings.penaltiesEnabled)
                    IntegerSettingField(localizedString(settings.language, R.string.subsequent_violation_min), settings.subsequentViolationPenaltyMinutes, onSubsequentViolationPenaltyChanged, 0, 9999, enabled = settings.penaltiesEnabled)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CompactCustomizeAudioCard(
            settings = settings,
            onEnabledChanged = onCustomizeAudioEnabledChanged,
            onConfigureClick = { showAudioSettings = true },
            colorScheme = colorScheme
        )
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.pose_deviation_tolerance), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                DriftToleranceDropdown(
                    language = settings.language,
                    selectedPreset = selectedDriftTolerance,
                    onPresetSelected = { preset -> onDriftChanged(preset.thresholdFactor) }
                )
                Spacer(Modifier.height(12.dp))
                Text(localizedString(settings.language, R.string.motion_sensitivity), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                MotionSensitivityDropdown(
                    language = settings.language,
                    selectedPreset = selectedMotionSensitivity,
                    onPresetSelected = { preset -> onMotionChanged(preset.thresholdFactor) }
                )
            }
        }
        if (settings.debugModeEnabled) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pose Smoother One Euro Filter", color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    FloatSettingField(
                        label = "minCutoff 0.010–5.000",
                        value = settings.poseSmootherMinCutoff,
                        onValueChanged = onPoseSmootherMinCutoffChanged,
                        min = 0.01f,
                        max = 5.0f,
                        decimals = 3
                    )
                    FloatSettingField(
                        label = "beta 0.000–1.000",
                        value = settings.poseSmootherBeta,
                        onValueChanged = onPoseSmootherBetaChanged,
                        min = 0f,
                        max = 1.0f,
                        decimals = 4
                    )
                    FloatSettingField(
                        label = "derivativeCutoff 0.010–5.000",
                        value = settings.poseSmootherDerivativeCutoff,
                        onValueChanged = onPoseSmootherDerivativeCutoffChanged,
                        min = 0.01f,
                        max = 5.0f,
                        decimals = 3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        localizedString(settings.language, R.string.wrist_drift_weight),
                        color = colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    FloatSettingField(
                        label = localizedString(settings.language, R.string.wrist_drift_weight_range),
                        value = settings.wristDriftWeight,
                        onValueChanged = onWristDriftWeightChanged,
                        min = 0f,
                        max = 1f,
                        decimals = 3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pose Occlusion Guard Debug", color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    FloatSettingField(
                        label = "visibility always freeze 0.000–0.050",
                        value = settings.occlusionFreezeVisibilityAlways,
                        onValueChanged = onOcclusionFreezeVisibilityAlwaysChanged,
                        min = 0f,
                        max = 0.05f,
                        decimals = 4
                    )
                    FloatSettingField(
                        label = "p10 visibility always freeze 0.000–0.050",
                        value = settings.occlusionFreezeVisibilityP10Always,
                        onValueChanged = onOcclusionFreezeVisibilityP10AlwaysChanged,
                        min = 0f,
                        max = 0.05f,
                        decimals = 4
                    )
                    FloatSettingField(
                        label = "hard visibility 0.000–0.100",
                        value = settings.occlusionFreezeVisibilityHard,
                        onValueChanged = onOcclusionFreezeVisibilityHardChanged,
                        min = 0f,
                        max = 0.10f,
                        decimals = 4
                    )
                    FloatSettingField(
                        label = "soft visibility 0.000–0.200",
                        value = settings.occlusionFreezeVisibilitySoft,
                        onValueChanged = onOcclusionFreezeVisibilitySoftChanged,
                        min = 0f,
                        max = 0.20f,
                        decimals = 4
                    )
                    FloatSettingField(
                        label = "jitter freeze threshold 0.000–0.300",
                        value = settings.occlusionJitterFreezeThreshold,
                        onValueChanged = onOcclusionJitterFreezeThresholdChanged,
                        min = 0f,
                        max = 0.30f,
                        decimals = 3
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        localizedString(settings.language, R.string.record_timelapse_video),
                        color = colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (settings.timelapseRecordingEnabled) localizedString(settings.language, R.string.on) else localizedString(settings.language, R.string.off),
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = settings.timelapseRecordingEnabled,
                            onCheckedChange = onTimelapseRecordingEnabledChanged
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        localizedString(settings.language, R.string.debug_mode),
                        color = colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (settings.debugModeEnabled) localizedString(settings.language, R.string.on) else localizedString(settings.language, R.string.off),
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = settings.debugModeEnabled,
                            onCheckedChange = onDebugModeEnabledChanged
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = onShowInstructions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(localizedString(settings.language, R.string.view_instructions))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CompactCustomizeAudioCard(
    settings: GameSettings,
    onEnabledChanged: (Boolean) -> Unit,
    onConfigureClick: () -> Unit,
    colorScheme: ColorScheme
) {
    Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    localizedString(settings.language, R.string.settings_customize_audio),
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.customizeAudioEnabled,
                    onCheckedChange = onEnabledChanged
                )
            }
            Text(
                localizedString(settings.language, R.string.settings_customize_audio_description),
                color = colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onConfigureClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(localizedString(settings.language, R.string.settings_configure))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomizeAudioSettingsScreen(
    settings: GameSettings,
    onBack: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onSettingsChanged: (AudioCue, AudioCueSettings) -> Unit,
    colorScheme: ColorScheme
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pcmPreviewPlayer = remember { PcmSignalPlayer() }
    var cueAwaitingFile by remember { mutableStateOf<AudioCue?>(null) }
    var audioPreviewingCue by remember { mutableStateOf<AudioCue?>(null) }
    var pcmPreviewingCue by remember { mutableStateOf<AudioCue?>(null) }
    var pcmConfiguringCue by remember { mutableStateOf<AudioCue?>(null) }
    var audioPreviewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun releaseAudioPreview(player: MediaPlayer) {
        if (audioPreviewPlayer === player) {
            audioPreviewPlayer = null
            audioPreviewingCue = null
        }
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    fun stopAudioPreview() {
        audioPreviewPlayer?.let(::releaseAudioPreview)
    }

    fun stopPcmPreview() {
        pcmPreviewPlayer.stop()
        pcmPreviewingCue = null
    }

    fun stopAllPreviews() {
        stopAudioPreview()
        stopPcmPreview()
    }

    fun toggleAudioPreview(cue: AudioCue, uriValue: String) {
        if (audioPreviewingCue == cue) {
            stopAudioPreview()
            return
        }

        stopAllPreviews()
        val player = MediaPlayer()
        audioPreviewPlayer = player
        audioPreviewingCue = cue
        runCatching {
            player.setDataSource(context.applicationContext, Uri.parse(uriValue))
            player.setOnPreparedListener { prepared ->
                if (audioPreviewPlayer === prepared) {
                    runCatching { prepared.start() }.onFailure { releaseAudioPreview(prepared) }
                } else {
                    releaseAudioPreview(prepared)
                }
            }
            player.setOnCompletionListener { completed -> releaseAudioPreview(completed) }
            player.setOnErrorListener { failed, _, _ ->
                releaseAudioPreview(failed)
                true
            }
            player.prepareAsync()
        }.onFailure {
            stopAudioPreview()
        }
    }

    fun togglePcmPreview(cue: AudioCue, pcmSettings: PcmSignalSettings) {
        if (pcmPreviewingCue == cue) {
            stopPcmPreview()
            return
        }

        stopAllPreviews()
        runCatching {
            pcmPreviewPlayer.play(pcmSettings) {
                coroutineScope.launch {
                    if (pcmPreviewingCue == cue) {
                        pcmPreviewingCue = null
                    }
                }
            }
        }
            .onSuccess { pcmPreviewingCue = cue }
            .onFailure { stopPcmPreview() }
    }

    val currentAudioPreviewPlayer by rememberUpdatedState(audioPreviewPlayer)
    DisposableEffect(Unit) {
        onDispose {
            currentAudioPreviewPlayer?.let { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            pcmPreviewPlayer.close()
        }
    }

    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val cue = cueAwaitingFile
        cueAwaitingFile = null
        if (cue == null || uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        stopAllPreviews()
        val existing = settings.audioCueSettings[cue] ?: AudioCueSettings()
        onSettingsChanged(
            cue,
            existing.copy(mode = AudioCueMode.AudioFile, audioFileUri = uri.toString())
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = localizedString(settings.language, R.string.back),
                    tint = colorScheme.onBackground
                )
            }
            Text(
                localizedString(settings.language, R.string.settings_customize_audio),
                color = colorScheme.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        localizedString(settings.language, R.string.settings_customize_audio_description),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.customizeAudioEnabled,
                        onCheckedChange = {
                            if (!it) stopAllPreviews()
                            onEnabledChanged(it)
                        }
                    )
                }
                if (settings.customizeAudioEnabled) {
                    Spacer(Modifier.height(12.dp))
                    AudioCue.entries.forEachIndexed { index, cue ->
                        val cueSettings = settings.audioCueSettings[cue] ?: AudioCueSettings()
                        AudioCueSettingRow(
                            language = settings.language,
                            cue = cue,
                            cueSettings = cueSettings,
                            isAudioFilePreviewing = audioPreviewingCue == cue,
                            isPcmPreviewing = pcmPreviewingCue == cue,
                            onModeSelected = { mode ->
                                stopAllPreviews()
                                if (mode == AudioCueMode.AudioFile) {
                                    cueAwaitingFile = cue
                                    audioFilePicker.launch(arrayOf("audio/*"))
                                } else {
                                    onSettingsChanged(cue, cueSettings.copy(mode = mode))
                                }
                            },
                            onAudioFilePreviewClick = {
                                cueSettings.audioFileUri?.let { toggleAudioPreview(cue, it) }
                            },
                            onPcmPreviewClick = {
                                togglePcmPreview(cue, cueSettings.pcmSettings)
                            },
                            onPcmConfigureClick = {
                                stopAllPreviews()
                                pcmConfiguringCue = cue
                            },
                            colorScheme = colorScheme
                        )
                        if (index != AudioCue.entries.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = colorScheme.outlineVariant.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    pcmConfiguringCue?.let { cue ->
        val cueSettings = settings.audioCueSettings[cue] ?: AudioCueSettings()
        PcmSettingsDialog(
            language = settings.language,
            initialSettings = cueSettings.pcmSettings,
            onDismiss = { pcmConfiguringCue = null },
            onApply = { updatedPcmSettings ->
                onSettingsChanged(
                    cue,
                    cueSettings.copy(
                        mode = AudioCueMode.Pcm,
                        pcmSettings = updatedPcmSettings
                    )
                )
                pcmConfiguringCue = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCueSettingRow(
    language: AppLanguage,
    cue: AudioCue,
    cueSettings: AudioCueSettings,
    isAudioFilePreviewing: Boolean,
    isPcmPreviewing: Boolean,
    onModeSelected: (AudioCueMode) -> Unit,
    onAudioFilePreviewClick: () -> Unit,
    onPcmPreviewClick: () -> Unit,
    onPcmConfigureClick: () -> Unit,
    colorScheme: ColorScheme
) {
    var expanded by remember { mutableStateOf(false) }
    val hasAudioFile = cueSettings.mode == AudioCueMode.AudioFile &&
        !cueSettings.audioFileUri.isNullOrBlank()
    val isPcm = cueSettings.mode == AudioCueMode.Pcm

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = localizedString(language, cue.labelRes()),
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = localizedString(language, cueSettings.mode.labelRes()),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    AudioCueMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(localizedString(language, mode.labelRes())) },
                            onClick = {
                                expanded = false
                                onModeSelected(mode)
                            }
                        )
                    }
                }
            }
            if (hasAudioFile || isPcm) {
                Spacer(Modifier.width(8.dp))
                val isPreviewing = if (isPcm) isPcmPreviewing else isAudioFilePreviewing
                FilledTonalIconButton(
                    onClick = if (isPcm) onPcmPreviewClick else onAudioFilePreviewClick
                ) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = localizedString(
                            language,
                            if (isPreviewing) R.string.audio_cue_stop_preview
                            else R.string.audio_cue_play_preview
                        )
                    )
                }
            }
            if (isPcm) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onPcmConfigureClick) {
                    Text(localizedString(language, R.string.audio_cue_pcm_configure))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcmSettingsDialog(
    language: AppLanguage,
    initialSettings: PcmSignalSettings,
    onDismiss: () -> Unit,
    onApply: (PcmSignalSettings) -> Unit
) {
    var frequencyText by remember(initialSettings) {
        mutableStateOf(initialSettings.frequencyHz.toString())
    }
    var durationText by remember(initialSettings) {
        mutableStateOf(initialSettings.durationSeconds.toString())
    }
    var amplitudeText by remember(initialSettings) {
        mutableStateOf(initialSettings.amplitudePercent.toString())
    }
    var fadeInText by remember(initialSettings) {
        mutableStateOf(initialSettings.fadeInMs.toString())
    }
    var fadeOutText by remember(initialSettings) {
        mutableStateOf(initialSettings.fadeOutMs.toString())
    }
    var channel by remember(initialSettings) { mutableStateOf(initialSettings.channel) }
    var pattern by remember(initialSettings) { mutableStateOf(initialSettings.pattern) }
    var channelExpanded by remember { mutableStateOf(false) }
    var patternExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedString(language, R.string.audio_cue_pcm_settings_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = frequencyText,
                    onValueChange = { frequencyText = it.filter(Char::isDigit) },
                    label = { Text(localizedString(language, R.string.audio_cue_pcm_frequency)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = {
                        Text(localizedString(language, R.string.audio_cue_pcm_duration_seconds))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PcmEnumSettingField(
                    label = localizedString(language, R.string.audio_cue_pcm_channel),
                    value = localizedString(language, channel.labelRes()),
                    expanded = channelExpanded,
                    onExpandedChange = { channelExpanded = it },
                    options = PcmChannel.entries.map { option ->
                        localizedString(language, option.labelRes()) to { channel = option }
                    }
                )
                OutlinedTextField(
                    value = amplitudeText,
                    onValueChange = { amplitudeText = it.filter(Char::isDigit) },
                    label = {
                        Text(localizedString(language, R.string.audio_cue_pcm_amplitude_percent))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fadeInText,
                    onValueChange = { fadeInText = it.filter(Char::isDigit) },
                    label = { Text(localizedString(language, R.string.audio_cue_pcm_fade_in_ms)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fadeOutText,
                    onValueChange = { fadeOutText = it.filter(Char::isDigit) },
                    label = { Text(localizedString(language, R.string.audio_cue_pcm_fade_out_ms)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PcmEnumSettingField(
                    label = localizedString(language, R.string.audio_cue_pcm_pattern),
                    value = localizedString(language, pattern.labelRes()),
                    expanded = patternExpanded,
                    onExpandedChange = { patternExpanded = it },
                    options = PcmPattern.entries.map { option ->
                        localizedString(language, option.labelRes()) to { pattern = option }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        PcmSignalSettings(
                            frequencyHz = (frequencyText.toIntOrNull()
                                ?: initialSettings.frequencyHz).coerceIn(20, 20_000),
                            durationSeconds = (durationText.toFloatOrNull()
                                ?.takeIf(Float::isFinite)
                                ?: initialSettings.durationSeconds).coerceIn(0.05f, 10.0f),
                            channel = channel,
                            amplitudePercent = (amplitudeText.toIntOrNull()
                                ?: initialSettings.amplitudePercent).coerceIn(0, 100),
                            fadeInMs = (fadeInText.toIntOrNull()
                                ?: initialSettings.fadeInMs).coerceIn(0, 5_000),
                            fadeOutMs = (fadeOutText.toIntOrNull()
                                ?: initialSettings.fadeOutMs).coerceIn(0, 5_000),
                            pattern = pattern
                        )
                    )
                }
            ) {
                Text(localizedString(language, R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedString(language, R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PcmEnumSettingField(
    label: String,
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Pair<String, () -> Unit>>
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            options.forEach { (optionLabel, onSelected) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelected()
                        onExpandedChange(false)
                    }
                )
            }
        }
    }
}

private fun AudioCue.labelRes(): Int = when (this) {
    AudioCue.PlaceDeviceStill -> R.string.audio_cue_place_device_still
    AudioCue.TakePosition -> R.string.audio_cue_take_position
    AudioCue.TimeStartedHoldPosition -> R.string.audio_cue_time_started_hold_position
    AudioCue.TimeIsUp -> R.string.audio_cue_time_is_up
    AudioCue.DefeatTryAgain -> R.string.audio_cue_defeat_try_again
    AudioCue.MotionViolation -> R.string.audio_cue_motion_violation
    AudioCue.DriftViolation -> R.string.audio_cue_drift_violation
    AudioCue.ViolationRecorded -> R.string.audio_cue_violation_recorded
    AudioCue.FaceTurnedAway -> R.string.audio_cue_face_turned_away
    AudioCue.FaceLookedAtCamera -> R.string.audio_cue_face_looked_at_camera
}

private fun AudioCueMode.labelRes(): Int = when (this) {
    AudioCueMode.UseTts -> R.string.audio_cue_mode_use_tts
    AudioCueMode.AudioFile -> R.string.audio_cue_mode_choose_audio_file
    AudioCueMode.Pcm -> R.string.audio_cue_mode_pcm
    AudioCueMode.Vibration -> R.string.audio_cue_mode_use_vibration
    AudioCueMode.Off -> R.string.audio_cue_mode_off
}

private fun PcmChannel.labelRes(): Int = when (this) {
    PcmChannel.Left -> R.string.audio_cue_pcm_channel_left
    PcmChannel.Right -> R.string.audio_cue_pcm_channel_right
    PcmChannel.Both -> R.string.audio_cue_pcm_channel_both
}

private fun PcmPattern.labelRes(): Int = when (this) {
    PcmPattern.SingleTone -> R.string.audio_cue_pcm_pattern_single_tone
    PcmPattern.DoubleBeep -> R.string.audio_cue_pcm_pattern_double_beep
}

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    min: Int,
    max: Int,
    enabled: Boolean = true
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        enabled = enabled,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }
            text = digits
            val parsed = digits.toIntOrNull() ?: min
            if (enabled) onValueChanged(parsed.coerceIn(min, max))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FloatSettingField(
    label: String,
    value: Float,
    onValueChanged: (Float) -> Unit,
    min: Float,
    max: Float,
    decimals: Int,
    enabled: Boolean = true
) {
    var text by remember(value) {
        mutableStateOf("%.${decimals}f".format(Locale.US, value))
    }

    OutlinedTextField(
        value = text,
        enabled = enabled,
        onValueChange = { input ->
            val dottedInput = input.replace(',', '.')
            val normalizedInput = dottedInput
                .filterIndexed { index, char ->
                    char.isDigit() || (char == '.' && dottedInput.indexOf('.') == index)
                }

            text = normalizedInput

            val parsed = normalizedInput.toFloatOrNull()
            if (enabled && parsed != null) {
                onValueChanged(parsed.coerceIn(min, max))
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}


internal data class DriftTolerancePreset(
    val nameRes: Int,
    val thresholdFactor: Float
)

internal data class MotionSensitivityPreset(
    val nameRes: Int,
    val thresholdFactor: Float
)

internal val DriftTolerancePresets = listOf(
    DriftTolerancePreset(R.string.pose_tolerance_very_easy, 0.200f),
    DriftTolerancePreset(R.string.pose_tolerance_easy, 0.180f),
    DriftTolerancePreset(R.string.pose_tolerance_normal, 0.160f),
    DriftTolerancePreset(R.string.pose_tolerance_strict, 0.140f),
    DriftTolerancePreset(R.string.pose_tolerance_very_strict, 0.120f)
)

internal val MotionSensitivityPresets = listOf(
    MotionSensitivityPreset(R.string.motion_sensitivity_very_low, 0.08f),
    MotionSensitivityPreset(R.string.motion_sensitivity_low, 0.06f),
    MotionSensitivityPreset(R.string.motion_sensitivity_normal, 0.04f),
    MotionSensitivityPreset(R.string.motion_sensitivity_high, 0.03f),
    MotionSensitivityPreset(R.string.motion_sensitivity_very_high, 0.02f)
)

internal fun driftTolerancePresetFor(thresholdFactor: Float): DriftTolerancePreset {
    val normal = DriftTolerancePresets.first { it.nameRes == R.string.pose_tolerance_normal }
    return DriftTolerancePresets.firstOrNull { preset ->
        preset.thresholdFactor.nearlyEquals(thresholdFactor)
    } ?: normal
}

internal fun motionSensitivityPresetFor(thresholdFactor: Float): MotionSensitivityPreset {
    val normal = MotionSensitivityPresets.first { it.nameRes == R.string.motion_sensitivity_normal }
    return MotionSensitivityPresets.firstOrNull { preset ->
        preset.thresholdFactor.nearlyEquals(thresholdFactor)
    } ?: normal
}

internal fun Float.nearlyEquals(other: Float): Boolean = kotlin.math.abs(this - other) < 0.001f

@Composable
internal fun LanguageSelectorCard(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (showTitle) {
                Text(
                    localizedString(language, R.string.language),
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onLanguageChanged(AppLanguage.English) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = language == AppLanguage.English, onClick = { onLanguageChanged(AppLanguage.English) })
                Text(
                    localizedString(language, R.string.language_english),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onLanguageChanged(AppLanguage.Russian) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = language == AppLanguage.Russian, onClick = { onLanguageChanged(AppLanguage.Russian) })
                Text(
                    localizedString(language, R.string.language_russian),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriftToleranceDropdown(
    language: AppLanguage,
    selectedPreset: DriftTolerancePreset,
    onPresetSelected: (DriftTolerancePreset) -> Unit
) {
    PresetDropdown(
        language = language,
        selectedNameRes = selectedPreset.nameRes,
        presets = DriftTolerancePresets,
        presetNameRes = DriftTolerancePreset::nameRes,
        onPresetSelected = onPresetSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotionSensitivityDropdown(
    language: AppLanguage,
    selectedPreset: MotionSensitivityPreset,
    onPresetSelected: (MotionSensitivityPreset) -> Unit
) {
    PresetDropdown(
        language = language,
        selectedNameRes = selectedPreset.nameRes,
        presets = MotionSensitivityPresets,
        presetNameRes = MotionSensitivityPreset::nameRes,
        onPresetSelected = onPresetSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PresetDropdown(
    language: AppLanguage,
    selectedNameRes: Int,
    presets: List<T>,
    presetNameRes: (T) -> Int,
    onPresetSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = localizedString(language, selectedNameRes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(localizedString(language, presetNameRes(preset))) },
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    }
                )
            }
        }
    }
}
