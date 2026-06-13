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
import androidx.compose.material.icons.filled.Settings
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
import com.incident201.poseguard.audio.TtsPhraseTemplate
import com.incident201.poseguard.audio.TtsVoiceMode
import com.incident201.poseguard.intiface.IntifaceDeviceInfo
import com.incident201.poseguard.intiface.IntifaceBackgroundMode
import com.incident201.poseguard.intiface.IntifaceMessage
import com.incident201.poseguard.intiface.IntifaceUiMessage
import com.incident201.poseguard.intiface.IntifaceUiState
import com.incident201.poseguard.intiface.IntifaceVibrationPattern
import com.incident201.poseguard.intiface.IntifaceVibrationSettings
import com.incident201.poseguard.intiface.IntifaceViolationMode
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
    onTtsVoiceModeChanged: (TtsVoiceMode) -> Unit,
    onTtsPhraseTemplateChanged: (TtsPhraseTemplate, String?) -> Unit,
    onAudioCueSettingsChanged: (AudioCue, AudioCueSettings) -> Unit,
    intifaceState: IntifaceUiState,
    onIntifaceConnectionEnabledChanged: (Boolean) -> Unit,
    onIntifaceBackgroundModeChanged: (IntifaceBackgroundMode) -> Unit,
    onIntifaceBackgroundVibrationChanged: (IntifaceVibrationSettings) -> Unit,
    onIntifaceViolationModeChanged: (IntifaceViolationMode) -> Unit,
    onIntifaceViolationVibrationChanged: (IntifaceVibrationSettings) -> Unit,
    onIntifaceViolationPauseSecondsChanged: (Double) -> Unit,
    onIntifaceWebSocketUrlChanged: (String) -> Unit,
    onIntifaceSearchDevices: (String) -> Unit,
    onIntifaceDeviceSelected: (IntifaceDeviceInfo) -> Unit,
    onIntifaceTestVibration: () -> Unit,
    onIntifaceDisconnect: () -> Unit,
    onShowInstructions: () -> Unit
 ) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedDriftTolerance = driftTolerancePresetFor(settings.driftThresholdFactor)
    val selectedMotionSensitivity = motionSensitivityPresetFor(settings.motionThresholdFactor)
    var showAudioSettings by remember { mutableStateOf(false) }
    var showIntifaceSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showAudioSettings || showIntifaceSettings) {
        if (showIntifaceSettings) showIntifaceSettings = false else showAudioSettings = false
    }

    if (showIntifaceSettings) {
        IntifaceCentralSettingsScreen(
            settings = settings,
            state = intifaceState,
            onBack = { showIntifaceSettings = false },
            onConnectionEnabledChanged = onIntifaceConnectionEnabledChanged,
            onBackgroundModeChanged = onIntifaceBackgroundModeChanged,
            onBackgroundVibrationChanged = onIntifaceBackgroundVibrationChanged,
            onViolationModeChanged = onIntifaceViolationModeChanged,
            onViolationVibrationChanged = onIntifaceViolationVibrationChanged,
            onViolationPauseSecondsChanged = onIntifaceViolationPauseSecondsChanged,
            onUrlChanged = onIntifaceWebSocketUrlChanged,
            onSearchDevices = onIntifaceSearchDevices,
            onDeviceSelected = onIntifaceDeviceSelected,
            onTestVibration = onIntifaceTestVibration,
            onDisconnect = onIntifaceDisconnect,
            colorScheme = colorScheme
        )
        return
    }

    if (showAudioSettings) {
        CustomizeAudioSettingsScreen(
            settings = settings,
            onBack = { showAudioSettings = false },
            onEnabledChanged = onCustomizeAudioEnabledChanged,
            onSettingsChanged = onAudioCueSettingsChanged,
            onTtsPhraseTemplateChanged = onTtsPhraseTemplateChanged,
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
        TtsVoiceModeCard(
            language = settings.language,
            selectedMode = settings.ttsVoiceMode,
            onModeChanged = onTtsVoiceModeChanged,
            colorScheme = colorScheme
        )
        Spacer(Modifier.height(12.dp))
        CompactCustomizeAudioCard(
            settings = settings,
            onEnabledChanged = onCustomizeAudioEnabledChanged,
            onConfigureClick = { showAudioSettings = true },
            colorScheme = colorScheme
        )
        Spacer(Modifier.height(12.dp))
        CompactIntifaceCard(
            settings = settings,
            state = intifaceState,
            onEnabledChanged = onIntifaceConnectionEnabledChanged,
            onConfigureClick = { showIntifaceSettings = true },
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
private fun TtsVoiceModeCard(
    language: AppLanguage,
    selectedMode: TtsVoiceMode,
    onModeChanged: (TtsVoiceMode) -> Unit,
    colorScheme: ColorScheme
) {
    var expanded by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                localizedString(language, R.string.tts_voice),
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            EnumSettingField(
                label = localizedString(language, R.string.tts_voice),
                value = localizedString(language, selectedMode.labelRes()),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                options = TtsVoiceMode.entries.map { mode ->
                    localizedString(language, mode.labelRes()) to { onModeChanged(mode) }
                }
            )
        }
    }
}

@Composable
private fun localizedIntifaceMessage(
    language: AppLanguage,
    message: IntifaceUiMessage
): String = when (message.message) {
    IntifaceMessage.OnlineOnly -> localizedString(language, R.string.intiface_online_only)
    IntifaceMessage.Connecting -> localizedString(language, R.string.intiface_connecting)
    IntifaceMessage.Connected -> localizedString(language, R.string.intiface_connected)
    IntifaceMessage.Scanning -> localizedString(language, R.string.intiface_scanning)
    IntifaceMessage.NoVibrateDevices -> localizedString(language, R.string.intiface_no_vibrate_devices)
    IntifaceMessage.FoundDevices -> localizedFormatString(
        language,
        R.string.intiface_found_devices,
        message.args.firstOrNull()?.toIntOrNull() ?: 0
    )
    IntifaceMessage.SelectedDevice -> localizedFormatString(
        language,
        R.string.intiface_selected_device,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.Disconnected -> localizedString(language, R.string.intiface_disconnected)
    IntifaceMessage.InvalidUrl -> localizedString(language, R.string.intiface_invalid_url)
    IntifaceMessage.ServerError -> localizedString(language, R.string.intiface_server_error)
    IntifaceMessage.UnableToConnect -> localizedString(language, R.string.intiface_unable_to_connect)
    IntifaceMessage.UnableToConnectDetail -> localizedFormatString(
        language,
        R.string.intiface_unable_to_connect_detail,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.TestVibration -> localizedString(language, R.string.intiface_test_vibration_status)
    IntifaceMessage.TestVibrationDone -> localizedString(language, R.string.intiface_test_vibration_done)
    IntifaceMessage.SelectDeviceFirst -> localizedString(language, R.string.intiface_select_device_first)
    IntifaceMessage.SelectedDeviceMissing -> localizedString(language, R.string.intiface_selected_device_missing)
    IntifaceMessage.NoVibrateCapability -> localizedString(language, R.string.intiface_no_vibrate_capability)
    IntifaceMessage.TestVibrationFailed -> localizedString(language, R.string.intiface_test_vibration_failed)
    IntifaceMessage.TestVibrationFailedDetail -> localizedFormatString(
        language,
        R.string.intiface_test_vibration_failed_detail,
        message.args.firstOrNull().orEmpty()
    )
    IntifaceMessage.ScanRejected -> localizedString(language, R.string.intiface_scan_rejected)
    IntifaceMessage.CommandRejected -> localizedString(language, R.string.intiface_command_rejected)
    IntifaceMessage.CommandRejectedDetail -> localizedFormatString(
        language,
        R.string.intiface_command_rejected_detail,
        message.args.firstOrNull().orEmpty()
    )
}

@Composable
private fun CompactIntifaceCard(
    settings: GameSettings,
    state: IntifaceUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onConfigureClick: () -> Unit,
    colorScheme: ColorScheme
) {
    val supported = state.isSupported
    Card(
        modifier = Modifier.alpha(if (supported) 1f else 0.5f),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    localizedString(settings.language, R.string.intiface_connection_title),
                    modifier = Modifier.weight(1f),
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = supported && settings.intifaceConnectionEnabled,
                    onCheckedChange = onEnabledChanged,
                    enabled = supported
                )
            }
            Text(
                localizedString(
                    settings.language,
                    if (supported) R.string.intiface_connection_description_online
                    else R.string.intiface_connection_description_offline
                ),
                color = colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = onConfigureClick, enabled = supported) {
                Text(localizedString(settings.language, R.string.intiface_configure))
            }
        }
    }
}

@Composable
private fun IntifaceCentralSettingsScreen(
    settings: GameSettings,
    state: IntifaceUiState,
    onBack: () -> Unit,
    onConnectionEnabledChanged: (Boolean) -> Unit,
    onBackgroundModeChanged: (IntifaceBackgroundMode) -> Unit,
    onBackgroundVibrationChanged: (IntifaceVibrationSettings) -> Unit,
    onViolationModeChanged: (IntifaceViolationMode) -> Unit,
    onViolationVibrationChanged: (IntifaceVibrationSettings) -> Unit,
    onViolationPauseSecondsChanged: (Double) -> Unit,
    onUrlChanged: (String) -> Unit,
    onSearchDevices: (String) -> Unit,
    onDeviceSelected: (IntifaceDeviceInfo) -> Unit,
    onTestVibration: () -> Unit,
    onDisconnect: () -> Unit,
    colorScheme: ColorScheme
) {
    val enabled = state.isSupported && settings.intifaceConnectionEnabled
    Column(
        Modifier.fillMaxSize().background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, localizedString(settings.language, R.string.back))
            }
            Text(
                localizedString(settings.language, R.string.intiface_connection_title),
                fontSize = 24.sp, fontWeight = FontWeight.Bold
            )
        }
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(localizedString(settings.language, R.string.intiface_connection_title), fontWeight = FontWeight.SemiBold)
                    Text(
                        localizedString(
                            settings.language,
                            if (state.isSupported) R.string.intiface_connection_description_online
                            else R.string.intiface_connection_description_offline
                        )
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onConnectionEnabledChanged,
                    enabled = state.isSupported
                )
            }
        }
        if (!enabled) {
            Text(
                localizedString(settings.language, R.string.intiface_rules_disabled_until_enabled),
                color = colorScheme.onBackground
            )
        }
        IntifaceConnectionCard(
            settings, state, enabled, onUrlChanged, onSearchDevices, onDeviceSelected,
            onTestVibration, onDisconnect, colorScheme
        )
        IntifaceModeCard(
            title = localizedString(settings.language, R.string.intiface_background_title),
            enabled = enabled,
            options = IntifaceBackgroundMode.entries,
            selected = settings.intifaceBackgroundMode,
            label = { if (it == IntifaceBackgroundMode.Off) R.string.intiface_mode_off else R.string.intiface_mode_vibration },
            onSelected = onBackgroundModeChanged,
            language = settings.language,
            colorScheme = colorScheme
        ) {
            if (settings.intifaceBackgroundMode == IntifaceBackgroundMode.Vibration) {
                IntifaceVibrationSettingsEditor(
                    settings.language, settings.intifaceBackgroundVibration,
                    onBackgroundVibrationChanged, enabled
                )
            }
        }
        IntifaceModeCard(
            title = localizedString(settings.language, R.string.intiface_violations_title),
            enabled = enabled,
            options = IntifaceViolationMode.entries,
            selected = settings.intifaceViolationMode,
            label = {
                when (it) {
                    IntifaceViolationMode.Off -> R.string.intiface_mode_off
                    IntifaceViolationMode.Vibration -> R.string.intiface_mode_vibration
                    IntifaceViolationMode.Pause -> R.string.intiface_mode_pause
                }
            },
            onSelected = onViolationModeChanged,
            language = settings.language,
            colorScheme = colorScheme
        ) {
            when (settings.intifaceViolationMode) {
                IntifaceViolationMode.Off -> Unit
                IntifaceViolationMode.Vibration -> IntifaceVibrationSettingsEditor(
                    settings.language, settings.intifaceViolationVibration,
                    onViolationVibrationChanged, enabled
                )
                IntifaceViolationMode.Pause -> DoubleSettingField(
                    localizedString(settings.language, R.string.intiface_pause_duration_seconds),
                    settings.intifaceViolationPauseSeconds, onViolationPauseSecondsChanged,
                    0.05, 60.0, 2, enabled
                )
            }
        }
    }
}

@Composable
private fun <T> IntifaceModeCard(
    title: String,
    enabled: Boolean,
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelected: (T) -> Unit,
    language: AppLanguage,
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp).alpha(if (enabled) 1f else 0.5f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            options.forEach { option ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == option, { onSelected(option) }, enabled = enabled)
                    Text(localizedString(language, label(option)))
                }
            }
            content()
        }
    }
}

@Composable
private fun IntifaceConnectionCard(
    settings: GameSettings,
    state: IntifaceUiState,
    enabled: Boolean,
    onUrlChanged: (String) -> Unit,
    onSearchDevices: (String) -> Unit,
    onDeviceSelected: (IntifaceDeviceInfo) -> Unit,
    onTestVibration: () -> Unit,
    onDisconnect: () -> Unit,
    colorScheme: ColorScheme
) {
    var showDeviceDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.devices) {
        if (state.devices.isNotEmpty()) {
            showDeviceDialog = true
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = localizedString(settings.language, R.string.intiface_title),
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = localizedString(
                    settings.language,
                    if (state.isSupported) {
                        R.string.intiface_description_online
                    } else {
                        R.string.intiface_description_offline
                    }
                ),
                color = colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = settings.intifaceWebSocketUrl,
                onValueChange = onUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !state.isScanning && !state.isTestingVibration,
                singleLine = true,
                label = {
                    Text(localizedString(settings.language, R.string.intiface_websocket_url))
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onSearchDevices(settings.intifaceWebSocketUrl) },
                    enabled = enabled &&
                        !state.isScanning &&
                        !state.isTestingVibration &&
                        settings.intifaceWebSocketUrl.isNotBlank()
                ) {
                    Text(
                        localizedString(
                            settings.language,
                            if (state.isScanning) {
                                R.string.intiface_searching
                            } else {
                                R.string.intiface_search_devices
                            }
                        )
                    )
                }
                if (state.isConnected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = enabled && !state.isTestingVibration
                    ) {
                        Text(localizedString(settings.language, R.string.intiface_disconnect))
                    }
                }
            }
            Button(
                onClick = onTestVibration,
                enabled = enabled &&
                    state.isConnected &&
                    state.selectedDevice != null &&
                    !state.isScanning &&
                    !state.isTestingVibration
            ) {
                Text(
                    localizedString(
                        settings.language,
                        if (state.isTestingVibration) {
                            R.string.intiface_testing_vibration
                        } else {
                            R.string.intiface_test_vibration
                        }
                    )
                )
            }
            state.statusMessage?.let { status ->
                Text(
                    text = localizedIntifaceMessage(settings.language, status),
                    color = colorScheme.onSurfaceVariant
                )
            }
            state.errorMessage?.let { error ->
                Text(
                    text = localizedIntifaceMessage(settings.language, error),
                    color = colorScheme.error
                )
            }
            state.selectedDevice?.let { device ->
                Text(
                    text = localizedFormatString(
                        settings.language,
                        R.string.intiface_selected_device,
                        device.displayName
                    ),
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showDeviceDialog && state.devices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = {
                Text(localizedString(settings.language, R.string.intiface_select_device_title))
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.devices.forEach { device ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDeviceSelected(device)
                                    showDeviceDialog = false
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.displayName,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = localizedFormatString(
                                            settings.language,
                                            R.string.intiface_vibrate_motors,
                                            device.vibrateCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        onDeviceSelected(device)
                                        showDeviceDialog = false
                                    }
                                ) {
                                    Text(localizedString(settings.language, R.string.intiface_select))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text(localizedString(settings.language, R.string.intiface_close))
                }
            }
        )
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
    onTtsPhraseTemplateChanged: (TtsPhraseTemplate, String?) -> Unit,
    colorScheme: ColorScheme
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pcmPreviewPlayer = remember { PcmSignalPlayer() }
    var cueAwaitingFile by remember { mutableStateOf<AudioCue?>(null) }
    var audioPreviewingCue by remember { mutableStateOf<AudioCue?>(null) }
    var pcmPreviewingCue by remember { mutableStateOf<AudioCue?>(null) }
    var pcmConfiguringCue by remember { mutableStateOf<AudioCue?>(null) }
    var ttsConfiguringTemplate by remember { mutableStateOf<TtsPhraseTemplate?>(null) }
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
                            ttsText = settings.customTtsTemplates[cue.ttsPhraseTemplate()]
                                ?: localizedString(
                                    settings.language,
                                    cue.ttsPhraseTemplate().defaultTextRes()
                                ),
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
                            onTtsConfigureClick = {
                                stopAllPreviews()
                                ttsConfiguringTemplate = cue.ttsPhraseTemplate()
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
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = colorScheme.outlineVariant.copy(alpha = 0.65f)
                    )
                    TtsTemplateSettingRow(
                        language = settings.language,
                        label = localizedString(
                            settings.language,
                            R.string.audio_cue_penalty_added_to_timer
                        ),
                        text = settings.customTtsTemplates[TtsPhraseTemplate.PenaltyAddedToTimer]
                            ?: localizedString(
                                settings.language,
                                TtsPhraseTemplate.PenaltyAddedToTimer.defaultTextRes()
                            ),
                        onConfigureClick = {
                            stopAllPreviews()
                            ttsConfiguringTemplate = TtsPhraseTemplate.PenaltyAddedToTimer
                        },
                        colorScheme = colorScheme
                    )
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

    ttsConfiguringTemplate?.let { template ->
        TtsPhraseSettingsDialog(
            language = settings.language,
            template = template,
            initialCustomText = settings.customTtsTemplates[template],
            defaultText = localizedString(settings.language, template.defaultTextRes()),
            onDismiss = { ttsConfiguringTemplate = null },
            onApply = { customText ->
                onTtsPhraseTemplateChanged(template, customText)
                ttsConfiguringTemplate = null
            }
        )
    }
}

@Composable
private fun TtsTemplateSettingRow(
    language: AppLanguage,
    label: String,
    text: String,
    onConfigureClick: () -> Unit,
    colorScheme: ColorScheme
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onConfigureClick) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = localizedString(language, R.string.audio_cue_tts_configure)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCueSettingRow(
    language: AppLanguage,
    cue: AudioCue,
    cueSettings: AudioCueSettings,
    ttsText: String,
    isAudioFilePreviewing: Boolean,
    isPcmPreviewing: Boolean,
    onModeSelected: (AudioCueMode) -> Unit,
    onAudioFilePreviewClick: () -> Unit,
    onPcmPreviewClick: () -> Unit,
    onPcmConfigureClick: () -> Unit,
    onTtsConfigureClick: () -> Unit,
    colorScheme: ColorScheme
) {
    var expanded by remember { mutableStateOf(false) }
    val hasAudioFile = cueSettings.mode == AudioCueMode.AudioFile &&
        !cueSettings.audioFileUri.isNullOrBlank()
    val isPcm = cueSettings.mode == AudioCueMode.Pcm
    val isTts = cueSettings.mode == AudioCueMode.UseTts

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = localizedString(language, cue.labelRes()),
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        if (isTts) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = ttsText,
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
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
            if (isTts) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onTtsConfigureClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = localizedString(
                            language,
                            R.string.audio_cue_tts_configure
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsPhraseSettingsDialog(
    language: AppLanguage,
    template: TtsPhraseTemplate,
    initialCustomText: String?,
    defaultText: String,
    onDismiss: () -> Unit,
    onApply: (String?) -> Unit
) {
    var text by remember(template, initialCustomText, defaultText) {
        mutableStateOf(initialCustomText ?: defaultText)
    }
    var isDefault by remember(template, initialCustomText, defaultText) {
        mutableStateOf(initialCustomText == null)
    }
    val isValid = isValidTtsTemplate(template, text)
    val isMinutesTemplate = template == TtsPhraseTemplate.PenaltyAddedToTimer

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedString(language, R.string.audio_cue_tts_settings_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        isDefault = false
                    },
                    label = { Text(localizedString(language, R.string.audio_cue_tts_text)) },
                    isError = !isValid,
                    supportingText = if (isMinutesTemplate) {
                        {
                            Text(
                                localizedString(
                                    language,
                                    if (isValid) R.string.audio_cue_tts_template_minutes_hint
                                    else R.string.audio_cue_tts_template_minutes_error
                                )
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        text = defaultText
                        isDefault = true
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(localizedString(language, R.string.audio_cue_tts_reset_default))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(if (isDefault) null else text.takeIf { it.isNotBlank() })
                },
                enabled = isValid
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
                EnumSettingField(
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
                EnumSettingField(
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
private fun EnumSettingField(
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

private fun TtsVoiceMode.labelRes(): Int = when (this) {
    TtsVoiceMode.DefaultVoice -> R.string.tts_voice_default
    TtsVoiceMode.SystemVoice -> R.string.tts_voice_system
}

private fun AudioCue.ttsPhraseTemplate(): TtsPhraseTemplate = when (this) {
    AudioCue.PlaceDeviceStill -> TtsPhraseTemplate.PlaceDeviceStill
    AudioCue.TakePosition -> TtsPhraseTemplate.TakePosition
    AudioCue.TimeStartedHoldPosition -> TtsPhraseTemplate.TimeStartedHoldPosition
    AudioCue.TimeIsUp -> TtsPhraseTemplate.TimeIsUp
    AudioCue.DefeatTryAgain -> TtsPhraseTemplate.DefeatTryAgain
    AudioCue.MotionViolation -> TtsPhraseTemplate.MotionViolation
    AudioCue.DriftViolation -> TtsPhraseTemplate.DriftViolation
    AudioCue.ViolationRecorded -> TtsPhraseTemplate.ViolationRecorded
    AudioCue.FaceTurnedAway -> TtsPhraseTemplate.FaceTurnedAway
    AudioCue.FaceLookedAtCamera -> TtsPhraseTemplate.FaceLookedAtCamera
}

private fun TtsPhraseTemplate.defaultTextRes(): Int = when (this) {
    TtsPhraseTemplate.PlaceDeviceStill -> R.string.place_device_still
    TtsPhraseTemplate.TakePosition -> R.string.take_position
    TtsPhraseTemplate.TimeStartedHoldPosition -> R.string.time_started_hold_position
    TtsPhraseTemplate.TimeIsUp -> R.string.time_is_up
    TtsPhraseTemplate.DefeatTryAgain -> R.string.defeat_try_again
    TtsPhraseTemplate.MotionViolation -> R.string.motion_violation_voice
    TtsPhraseTemplate.DriftViolation -> R.string.drift_violation_voice
    TtsPhraseTemplate.ViolationRecorded -> R.string.violation_recorded
    TtsPhraseTemplate.FaceTurnedAway -> R.string.you_turned_away
    TtsPhraseTemplate.FaceLookedAtCamera -> R.string.you_looked_at_camera
    TtsPhraseTemplate.PenaltyAddedToTimer -> R.string.penalty_added_to_timer_template
}

private fun isValidTtsTemplate(template: TtsPhraseTemplate, text: String): Boolean =
    template != TtsPhraseTemplate.PenaltyAddedToTimer || text.contains("{minutes}")

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

@Composable
private fun DoubleSettingField(
    label: String,
    value: Double,
    onValueChanged: (Double) -> Unit,
    min: Double,
    max: Double,
    decimals: Int,
    enabled: Boolean = true
) {
    var text by remember(value) { mutableStateOf(String.format(Locale.US, "%.${decimals}f", value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.replace(',', '.').toDoubleOrNull()?.let { onValueChanged(it.coerceIn(min, max)) }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun IntifaceVibrationSettingsEditor(
    language: AppLanguage,
    settings: IntifaceVibrationSettings,
    onChanged: (IntifaceVibrationSettings) -> Unit,
    enabled: Boolean
) {
    DoubleSettingField(
        localizedString(language, R.string.intiface_strength),
        settings.strength,
        { onChanged(settings.copy(strength = it)) },
        0.0, 1.0, 2, enabled
    )
    Text(localizedString(language, R.string.intiface_strength_range))
    Text(localizedString(language, R.string.intiface_pattern), fontWeight = FontWeight.Medium)
    IntifaceVibrationPattern.entries.forEach { pattern ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = settings.pattern == pattern,
                onClick = { onChanged(settings.copy(pattern = pattern)) },
                enabled = enabled
            )
            Text(
                localizedString(
                    language,
                    if (pattern == IntifaceVibrationPattern.Constant) {
                        R.string.intiface_pattern_constant
                    } else {
                        R.string.intiface_pattern_pulse
                    }
                )
            )
        }
    }
    if (settings.pattern == IntifaceVibrationPattern.Pulse) {
        DoubleSettingField(
            localizedString(language, R.string.intiface_pulse_length_seconds),
            settings.pulseLengthSeconds,
            { onChanged(settings.copy(pulseLengthSeconds = it)) },
            0.05, 10.0, 2, enabled
        )
        DoubleSettingField(
            localizedString(language, R.string.intiface_pulse_pause_seconds),
            settings.pulsePauseSeconds,
            { onChanged(settings.copy(pulsePauseSeconds = it)) },
            0.05, 10.0, 2, enabled
        )
    }
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
