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

@Composable
internal fun SettingsScreen(
    settings: GameSettings,
    onClose: () -> Unit,
    onFaceModeChanged: (FaceCheckMode) -> Unit,
    onFaceConfidenceChanged: (Float) -> Unit,
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
    onTimelapseRecordingEnabledChanged: (Boolean) -> Unit
 ) {
    var faceConfidenceSlider by remember(settings.faceDetectionConfidence) {
        mutableFloatStateOf(settings.faceDetectionConfidence)
    }

    val colorScheme = MaterialTheme.colorScheme

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
                Text("${localizedString(settings.language, R.string.face_detection_threshold)}: ${"%.2f".format(faceConfidenceSlider)}", color = colorScheme.onSurfaceVariant)
                Slider(
                    value = faceConfidenceSlider,
                    onValueChange = { faceConfidenceSlider = it.coerceIn(0.5f, 0.95f) },
                    onValueChangeFinished = { onFaceConfidenceChanged(faceConfidenceSlider) },
                    valueRange = 0.5f..0.95f
                )
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
                Text(localizedString(settings.language, R.string.motion_reaction), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Text("${localizedString(settings.language, R.string.drift_threshold)}: ${"%.2f".format(settings.driftThresholdFactor)}", color = colorScheme.onSurfaceVariant)
                Slider(value = settings.driftThresholdFactor, onValueChange = onDriftChanged, valueRange = 0.05f..0.40f)
                Text("${localizedString(settings.language, R.string.abrupt_motion_threshold)}: ${"%.2f".format(settings.motionThresholdFactor)}", color = colorScheme.onSurfaceVariant)
                Slider(value = settings.motionThresholdFactor, onValueChange = onMotionChanged, valueRange = 0.03f..0.25f)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text(localizedString(settings.language, R.string.language), color = colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onLanguageChanged(AppLanguage.English) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = settings.language == AppLanguage.English, onClick = { onLanguageChanged(AppLanguage.English) })
                    Text(
                        localizedString(settings.language, R.string.language_english),
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
                    RadioButton(selected = settings.language == AppLanguage.Russian, onClick = { onLanguageChanged(AppLanguage.Russian) })
                    Text(
                        localizedString(settings.language, R.string.language_russian),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
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

