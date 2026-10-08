package com.akiranyx.osu2phira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionDefaults
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

private val SpeedRange = ConversionDefaults.minRpeBaseSpeed.toFloat()..ConversionDefaults.maxRpeBaseSpeed.toFloat()
private const val SpeedSliderSteps = 998
private const val OffsetSliderSteps = 3999
private const val JudgeLineSliderSteps = 899

@Composable
fun SettingsSection(
    state: ConversionSettingsFormState,
    enabled: Boolean,
    onStateChange: (ConversionSettingsFormState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val speed = parseDecimal(state.rpeBaseSpeedText)
        ?.coerceIn(ConversionDefaults.minRpeBaseSpeed, ConversionDefaults.maxRpeBaseSpeed)
        ?: ConversionDefaults.defaultRpeBaseSpeed
    val manualOffset = state.manualAudioOffsetText.trim().toIntOrNull()
        ?.coerceIn(ConversionDefaults.minManualAudioOffsetMs, ConversionDefaults.maxManualAudioOffsetMs)
        ?: 0
    val judgeLineY = state.judgeLineYText.trim().toIntOrNull()
        ?.coerceIn(ConversionDefaults.minJudgeLineY, ConversionDefaults.maxJudgeLineY)
        ?: ConversionDefaults.defaultJudgeLineY
    val speedFailure = state.failureFor(ConversionFailureCode.INVALID_RPE_SPEED)
    val offsetFailure = state.failureFor(ConversionFailureCode.INVALID_AUDIO_OFFSET)
    val judgeLineFailure = state.failureFor(ConversionFailureCode.INVALID_JUDGELINE_POSITION)
    val speedDescription = "${stringResource(R.string.rpe_speed_label)}，${speed.toPlainString()}"
    val offsetDescription = "${stringResource(R.string.audio_offset_manual_label)}，${manualOffset} ms"
    val judgeLineDescription = "${stringResource(R.string.judge_line_label)}，Y=${judgeLineY}"

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.unit * 2),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.unit),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.settings_intro))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.unit),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.rpeBaseSpeedText,
                    onValueChange = { onStateChange(state.copy(rpeBaseSpeedText = it)) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.rpe_speed_label)) },
                    suffix = { Text(stringResource(R.string.rpe_speed_unit)) },
                    supportingText = {
                        Text(speedFailure?.message ?: stringResource(R.string.rpe_speed_hint))
                    },
                    isError = speedFailure != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.weight(1f),
                )
                StepperButtons(
                    decrementLabel = stringResource(R.string.rpe_speed_decrease),
                    incrementLabel = stringResource(R.string.rpe_speed_increase),
                    onDecrement = {
                        onStateChange(state.withRpeBaseSpeed(stepSpeed(speed, -1)))
                    },
                    onIncrement = {
                        onStateChange(state.withRpeBaseSpeed(stepSpeed(speed, 1)))
                    },
                    enabled = enabled,
                )
            }
            Slider(
                value = speed.toFloat(),
                onValueChange = { value ->
                    val stepped = BigDecimal.valueOf(value.toDouble())
                        .setScale(1, RoundingMode.HALF_UP)
                        .coerceIn(
                            ConversionDefaults.minRpeBaseSpeed,
                            ConversionDefaults.maxRpeBaseSpeed,
                        )
                    onStateChange(state.withRpeBaseSpeed(stepped))
                },
                enabled = enabled,
                valueRange = SpeedRange,
                steps = SpeedSliderSteps,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = speedDescription },
            )
            RangeLabel(
                minimum = stringResource(R.string.rpe_speed_minimum),
                maximum = stringResource(R.string.rpe_speed_maximum),
            )

            Text(
                text = stringResource(R.string.audio_offset_label),
                style = MaterialTheme.typography.titleSmall,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.audioOffsetMode == AudioOffsetMode.AUTO,
                    onClick = { onStateChange(state.copy(audioOffsetMode = AudioOffsetMode.AUTO)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.audio_offset_auto))
                }
                SegmentedButton(
                    selected = state.audioOffsetMode == AudioOffsetMode.MANUAL,
                    onClick = { onStateChange(state.copy(audioOffsetMode = AudioOffsetMode.MANUAL)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.audio_offset_manual))
                }
            }
            if (state.audioOffsetMode == AudioOffsetMode.AUTO) {
                Text(
                    text = stringResource(R.string.audio_offset_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.unit),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.manualAudioOffsetText,
                        onValueChange = { onStateChange(state.copy(manualAudioOffsetText = it)) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.audio_offset_manual_label)) },
                        suffix = { Text(stringResource(R.string.audio_offset_unit)) },
                        supportingText = {
                            Text(offsetFailure?.message ?: stringResource(R.string.audio_offset_manual_hint))
                        },
                        isError = offsetFailure != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    StepperButtons(
                        decrementLabel = stringResource(R.string.audio_offset_decrease),
                        incrementLabel = stringResource(R.string.audio_offset_increase),
                        onDecrement = {
                            onStateChange(state.withManualAudioOffset(manualOffset - 1))
                        },
                        onIncrement = {
                            onStateChange(state.withManualAudioOffset(manualOffset + 1))
                        },
                        enabled = enabled,
                    )
                }
                Slider(
                    value = manualOffset.toFloat(),
                    onValueChange = { value ->
                        onStateChange(state.withManualAudioOffset(value.roundToInt()))
                    },
                    enabled = enabled,
                    valueRange = ConversionDefaults.minManualAudioOffsetMs.toFloat()..
                        ConversionDefaults.maxManualAudioOffsetMs.toFloat(),
                    steps = OffsetSliderSteps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = offsetDescription },
                )
                RangeLabel(
                    minimum = stringResource(R.string.audio_offset_minimum),
                    maximum = stringResource(R.string.audio_offset_maximum),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.unit),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.judgeLineYText,
                    onValueChange = { onStateChange(state.copy(judgeLineYText = it)) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.judge_line_label)) },
                    suffix = { Text(stringResource(R.string.judge_line_unit)) },
                    supportingText = {
                        Text(judgeLineFailure?.message ?: stringResource(R.string.judge_line_hint))
                    },
                    isError = judgeLineFailure != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.weight(1f),
                )
                StepperButtons(
                    decrementLabel = stringResource(R.string.judge_line_decrease),
                    incrementLabel = stringResource(R.string.judge_line_increase),
                    onDecrement = {
                        onStateChange(state.withJudgeLineY((judgeLineY - 1).coerceAtLeast(ConversionDefaults.minJudgeLineY)))
                    },
                    onIncrement = {
                        onStateChange(state.withJudgeLineY((judgeLineY + 1).coerceAtMost(ConversionDefaults.maxJudgeLineY)))
                    },
                    enabled = enabled,
                )
            }
            Slider(
                value = judgeLineY.toFloat(),
                onValueChange = { value ->
                    onStateChange(state.withJudgeLineY(value.roundToInt()))
                },
                enabled = enabled,
                valueRange = ConversionDefaults.minJudgeLineY.toFloat()..
                    ConversionDefaults.maxJudgeLineY.toFloat(),
                steps = JudgeLineSliderSteps,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = judgeLineDescription },
            )
            RangeLabel(
                minimum = stringResource(R.string.judge_line_minimum),
                maximum = stringResource(R.string.judge_line_maximum),
            )
            Text(
                text = stringResource(R.string.judge_line_direction_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(
                onClick = { onStateChange(ConversionSettingsFormState.defaults()) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }
}

@Composable
private fun RangeLabel(
    minimum: String,
    maximum: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(minimum, style = MaterialTheme.typography.bodySmall)
        Text(maximum, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StepperButtons(
    decrementLabel: String,
    incrementLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.unit / 2)) {
        IconButton(
            onClick = onDecrement,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = decrementLabel },
        ) {
            Text("−")
        }
        IconButton(
            onClick = onIncrement,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = incrementLabel },
        ) {
            Text("+")
        }
    }
}

private fun stepSpeed(value: BigDecimal, direction: Int): BigDecimal =
    value.add(ConversionDefaults.rpeBaseSpeedStep.multiply(BigDecimal.valueOf(direction.toLong())))
        .coerceIn(ConversionDefaults.minRpeBaseSpeed, ConversionDefaults.maxRpeBaseSpeed)

private fun parseDecimal(value: String): BigDecimal? = try {
    value.trim().takeIf(String::isNotEmpty)?.let(::BigDecimal)
} catch (_: NumberFormatException) {
    null
}
