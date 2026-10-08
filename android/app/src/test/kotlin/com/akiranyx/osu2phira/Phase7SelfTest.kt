package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.data.ExportState
import com.akiranyx.osu2phira.data.InputFileState
import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionDefaults
import com.akiranyx.osu2phira.domain.ConversionSettings
import java.math.BigDecimal

/** Framework-free phase-7 check for the single state source and restoration codec. */
fun main() {
    operationIsDerivedFromOneUiState()
    pendingSettingsRoundTripThroughPrimitiveState()
    corruptPendingStateIsDiscarded()
    defaultsRemainTheSingleResetSource()
    println("phase-7 self-test passed: single UI state, saved picker state and defaults")
}

private fun operationIsDerivedFromOneUiState() {
    check(MainUiState(inputState = InputFileState.Loading).operation == MainOperation.READING_INPUT)
    check(MainUiState(exportState = ExportState.SelectingOutput).operation == MainOperation.SELECTING_OUTPUT)
    check(MainUiState(exportState = ExportState.Exporting).operation == MainOperation.EXPORTING)
    check(MainUiState(exportState = ExportState.Cancelling).operation == MainOperation.CANCELLING)
    check(MainUiState(exportState = ExportState.Success("song.pez", 4, 0)).operation == MainOperation.IDLE)
}

private fun pendingSettingsRoundTripThroughPrimitiveState() {
    val settings = ConversionSettings(
        rpeBaseSpeed = BigDecimal("22.2"),
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetMs = 125,
        judgeLineY = 100,
    )
    val values = PendingExportSettingsCodec.encode(settings)
    val restored = PendingExportSettingsCodec.decode { key -> values[key] }
    check(restored == settings)
    check(values[PendingExportSettingsCodec.PENDING_EXPORT] == true)
}

private fun corruptPendingStateIsDiscarded() {
    val values = PendingExportSettingsCodec.encode(ConversionSettings.defaults()).toMutableMap()
    values[PendingExportSettingsCodec.SPEED] = "not-a-number"
    check(PendingExportSettingsCodec.decode { key -> values[key] } == null)

    values[PendingExportSettingsCodec.SPEED] = "16.5"
    values[PendingExportSettingsCodec.MANUAL_OFFSET] = "125"
    check(PendingExportSettingsCodec.decode { key -> values[key] } == null)
}

private fun defaultsRemainTheSingleResetSource() {
    val defaults = ConversionSettingsFormState.defaults()
    check(defaults.toSettingsOrNull() == ConversionSettings.defaults())
    check(defaults.rpeBaseSpeedText == ConversionDefaults.defaultRpeBaseSpeed.toPlainString())
    check(defaults.judgeLineYText == ConversionDefaults.defaultJudgeLineY.toString())
}
