package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionDefaults
import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionSettings
import java.math.BigDecimal

/**
 * UI draft for conversion settings.
 *
 * Text is kept separately from the domain object so an incomplete or malformed
 * edit remains visible and cannot accidentally start a conversion.
 */
data class ConversionSettingsFormState(
    val rpeBaseSpeedText: String = ConversionDefaults.defaultRpeBaseSpeed.toPlainString(),
    val audioOffsetMode: AudioOffsetMode = AudioOffsetMode.AUTO,
    val manualAudioOffsetText: String = "0",
    val judgeLineYText: String = ConversionDefaults.defaultJudgeLineY.toString(),
) {
    val validationFailures: List<ConversionFailure>
        get() {
            val failures = mutableListOf<ConversionFailure>()
            val speed = rpeBaseSpeedText.toBigDecimalOrNullCompat()
            if (speed == null) {
                failures += ConversionFailure(
                    code = ConversionFailureCode.INVALID_RPE_SPEED,
                    message = "RPE 流速必须是数字，范围为 0.1–100.0，并按 0.1 调整",
                    section = "设置",
                )
            }

            val manualOffset = manualAudioOffsetText.trim().toIntOrNull()
            if (manualOffset == null) {
                failures += ConversionFailure(
                    code = ConversionFailureCode.INVALID_AUDIO_OFFSET,
                    message = "手动音频偏移必须是整数，范围为 -2000–2000 ms，并按 1 ms 调整",
                    section = "设置",
                )
            }

            val judgeLineY = judgeLineYText.trim().toIntOrNull()
            if (judgeLineY == null) {
                failures += ConversionFailure(
                    code = ConversionFailureCode.INVALID_JUDGELINE_POSITION,
                    message = "判定线位置必须是整数，范围为 -450–450，并按 1 调整",
                    section = "设置",
                )
            }

            if (speed != null && manualOffset != null && judgeLineY != null) {
                val settings = ConversionSettings(
                    rpeBaseSpeed = speed,
                    audioOffsetMode = audioOffsetMode,
                    manualAudioOffsetMs = manualOffset,
                    judgeLineY = judgeLineY,
                )
                failures += settings.validate()
            }
            return failures.distinctBy(ConversionFailure::code)
        }

    val isValid: Boolean
        get() = validationFailures.isEmpty()

    /** Converts the complete draft only after every domain constraint passes. */
    fun toSettingsOrNull(): ConversionSettings? {
        if (!isValid) return null
        return ConversionSettings(
            rpeBaseSpeed = rpeBaseSpeedText.toBigDecimalOrNullCompat()!!,
            audioOffsetMode = audioOffsetMode,
            manualAudioOffsetMs = manualAudioOffsetText.trim().toInt(),
            judgeLineY = judgeLineYText.trim().toInt(),
        )
    }

    fun failureFor(code: ConversionFailureCode): ConversionFailure? =
        validationFailures.firstOrNull { it.code == code }

    fun withRpeBaseSpeed(value: BigDecimal): ConversionSettingsFormState =
        copy(rpeBaseSpeedText = value.stripTrailingZeros().toPlainString())

    fun withManualAudioOffset(value: Int): ConversionSettingsFormState =
        copy(manualAudioOffsetText = value.toString())

    fun withJudgeLineY(value: Int): ConversionSettingsFormState =
        copy(judgeLineYText = value.toString())

    companion object {
        fun defaults(): ConversionSettingsFormState = ConversionSettingsFormState()
    }
}

private fun String.toBigDecimalOrNullCompat(): BigDecimal? = try {
    trim().takeIf(String::isNotEmpty)?.let(::BigDecimal)
} catch (_: NumberFormatException) {
    null
}
