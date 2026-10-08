package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.data.ExportState
import com.akiranyx.osu2phira.data.InputFileState

/**
 * The complete state rendered by the main screen.
 *
 * The operation is derived from the two existing domain state machines so
 * there is no second progress source that can drift from what the UI shows.
 */
data class MainUiState(
    val inputState: InputFileState = InputFileState.Empty,
    val exportState: ExportState = ExportState.Idle,
    val settingsState: ConversionSettingsFormState = ConversionSettingsFormState.defaults(),
    val selectedBeatmapPath: String? = null,
    val isBeatmapSelectionOpen: Boolean = false,
) {
    val operation: MainOperation
        get() = when {
            inputState is InputFileState.Loading -> MainOperation.READING_INPUT
            exportState is ExportState.SelectingOutput -> MainOperation.SELECTING_OUTPUT
            exportState is ExportState.Exporting -> MainOperation.EXPORTING
            exportState is ExportState.Cancelling -> MainOperation.CANCELLING
            else -> MainOperation.IDLE
        }
}

enum class MainOperation {
    IDLE,
    READING_INPUT,
    SELECTING_OUTPUT,
    EXPORTING,
    CANCELLING,
}
