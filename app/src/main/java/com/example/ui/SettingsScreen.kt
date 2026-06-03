package com.example.ui

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
import com.example.R
import com.example.viewmodel.AppLanguage
import com.example.viewmodel.FaceCheckMode
import com.example.viewmodel.GameSettings
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
    onShowInstructions: () -> Unit
 ) {
    val colorScheme = MaterialTheme.colorScheme
    val normalSensitivity = SensitivityPresets.first { it.nameRes == R.string.sensitivity_normal }
    val selectedSensitivity = sensitivityPresetFor(settings)

    LaunchedEffect(selectedSensitivity, settings.driftThresholdFactor, settings.motionThresholdFactor) {
        if (!selectedSensitivity.matches(settings)) {
            onDriftChanged(normalSensitivity.driftThresholdFactor)
            onMotionChanged(normalSensitivity.motionThresholdFactor)
        }
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = settings.faceCheckMode == mode, onClick = { onFaceModeChanged(mode) })
                        Text(label, color = colorScheme.onSurfaceVariant)
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
                Text(localizedString(settings.language, R.string.sensitivity), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SensitivityDropdown(
                    language = settings.language,
                    selectedPreset = selectedSensitivity,
                    onPresetSelected = { preset ->
                        onDriftChanged(preset.driftThresholdFactor)
                        onMotionChanged(preset.motionThresholdFactor)
                    }
                )
            }
        }
        if (settings.debugModeEnabled) {
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


internal data class SensitivityPreset(
    val nameRes: Int,
    val driftThresholdFactor: Float,
    val motionThresholdFactor: Float
) {
    fun matches(settings: GameSettings): Boolean =
        driftThresholdFactor.nearlyEquals(settings.driftThresholdFactor) &&
            motionThresholdFactor.nearlyEquals(settings.motionThresholdFactor)
}

internal val SensitivityPresets = listOf(
    SensitivityPreset(R.string.sensitivity_normal, 0.12f, 0.06f),
    SensitivityPreset(R.string.sensitivity_high, 0.10f, 0.04f),
    SensitivityPreset(R.string.sensitivity_low, 0.15f, 0.08f),
    SensitivityPreset(R.string.sensitivity_very_low, 0.17f, 0.09f)
)

internal fun sensitivityPresetFor(settings: GameSettings): SensitivityPreset {
    val normal = SensitivityPresets.first { it.nameRes == R.string.sensitivity_normal }
    return SensitivityPresets.firstOrNull { preset ->
        preset.driftThresholdFactor.nearlyEquals(settings.driftThresholdFactor) &&
            preset.motionThresholdFactor.nearlyEquals(settings.motionThresholdFactor)
    } ?: normal
}

internal fun Float.nearlyEquals(other: Float): Boolean = kotlin.math.abs(this - other) < 0.001f

@Composable
internal fun LanguageSelectorCard(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(localizedString(language, R.string.language), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
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
private fun SensitivityDropdown(
    language: AppLanguage,
    selectedPreset: SensitivityPreset,
    onPresetSelected: (SensitivityPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = localizedString(language, selectedPreset.nameRes),
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
            SensitivityPresets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(localizedString(language, preset.nameRes)) },
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    }
                )
            }
        }
    }
}
