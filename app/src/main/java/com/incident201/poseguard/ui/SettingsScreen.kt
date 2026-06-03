package com.incident201.poseguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.incident201.poseguard.R
import com.incident201.poseguard.viewmodel.AppLanguage
import com.incident201.poseguard.viewmodel.FaceCheckMode
import com.incident201.poseguard.viewmodel.GameSettings
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
    onShowInstructions: () -> Unit
 ) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedDriftTolerance = driftTolerancePresetFor(settings.driftThresholdFactor)
    val selectedMotionSensitivity = motionSensitivityPresetFor(settings.motionThresholdFactor)

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
