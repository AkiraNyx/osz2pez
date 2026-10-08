package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionDefaults
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionSettings
import java.math.BigDecimal

/** Framework-free phase-6 check for settings validation and export gating. */
fun main() {
    defaultsRoundTripToDomain()
    speedRangeAndStepAreEnforced()
    audioOffsetModeAndRangeAreEnforced()
    judgeLineRangeAndStepAreEnforced()
    resetRestoresOneDefaultObject()
    println("phase-6 self-test passed: settings validation, stepping, reset and export gate")
}

private fun defaultsRoundTripToDomain() {
    val form = ConversionSettingsFormState.defaults()
    check(form.isValid)
    check(form.toSettingsOrNull() == ConversionSettings.defaults())
}

private fun speedRangeAndStepAreEnforced() {
    val minimum = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "0.1")
    check(minimum.toSettingsOrNull()?.rpeBaseSpeed == BigDecimal("0.1"))

    val maximum = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "100.0")
    check(maximum.toSettingsOrNull()?.rpeBaseSpeed == BigDecimal("100.0"))

    val invalidStep = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "16.55")
    check(!invalidStep.isValid)
    check(invalidStep.failureFor(ConversionFailureCode.INVALID_RPE_SPEED) != null)

    val invalidRange = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "100.1")
    check(invalidRange.toSettingsOrNull() == null)

    val incomplete = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "16.")
    check(incomplete.isValid)
    check(incomplete.toSettingsOrNull()?.rpeBaseSpeed == BigDecimal("16"))
    val malformed = ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "fast")
    check(malformed.toSettingsOrNull() == null)
}

private fun audioOffsetModeAndRangeAreEnforced() {
    val automatic = ConversionSettingsFormState.defaults().copy(audioOffsetMode = AudioOffsetMode.AUTO)
    check(automatic.toSettingsOrNull()?.audioOffsetMode == AudioOffsetMode.AUTO)

    val manual = automatic.copy(
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetText = "-2000",
    )
    check(manual.toSettingsOrNull()?.manualAudioOffsetMs == -2000)
    check(
        manual.copy(manualAudioOffsetText = "2000").toSettingsOrNull()
            ?.manualAudioOffsetMs == 2000
    )
    check(
        manual.copy(manualAudioOffsetText = "1.5")
            .failureFor(ConversionFailureCode.INVALID_AUDIO_OFFSET) != null
    )
    check(manual.copy(manualAudioOffsetText = "2001").toSettingsOrNull() == null)
}

private fun judgeLineRangeAndStepAreEnforced() {
    val minimum = ConversionSettingsFormState.defaults().copy(judgeLineYText = "-450")
    check(minimum.toSettingsOrNull()?.judgeLineY == -450)
    val maximum = minimum.copy(judgeLineYText = "450")
    check(maximum.toSettingsOrNull()?.judgeLineY == 450)
    check(
        minimum.copy(judgeLineYText = "451")
            .failureFor(ConversionFailureCode.INVALID_JUDGELINE_POSITION) != null
    )
    check(
        minimum.copy(judgeLineYText = "center")
            .toSettingsOrNull() == null
    )
}

private fun resetRestoresOneDefaultObject() {
    val changed = ConversionSettingsFormState(
        rpeBaseSpeedText = "20.0",
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetText = "100",
        judgeLineYText = "120",
    )
    val reset = ConversionSettingsFormState.defaults()
    check(reset.toSettingsOrNull() == ConversionSettings.defaults())
    check(reset != changed)
    check(reset.withRpeBaseSpeed(BigDecimal("20.0")).rpeBaseSpeedText == "20")
    check(reset.withManualAudioOffset(-1).manualAudioOffsetText == "-1")
    check(reset.withJudgeLineY(1).judgeLineYText == "1")
}
