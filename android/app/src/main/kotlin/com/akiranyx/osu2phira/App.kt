package com.akiranyx.osu2phira

import androidx.compose.runtime.Composable
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.akiranyx.osu2phira.ui.theme.Osu2PhiraTheme

@Composable
fun Osu2PhiraApp(
    widthSizeClass: WindowWidthSizeClass,
    uiState: MainUiState = MainUiState(),
    onChooseOsz: () -> Unit = {},
    onChooseBeatmap: () -> Unit = {},
    onConfirmBeatmap: (String) -> Unit = {},
    onDismissBeatmap: () -> Unit = {},
    onSettingsChange: (ConversionSettingsFormState) -> Unit = {},
    onExport: () -> Unit = {},
    onCancelExport: () -> Unit = {},
) {
    Osu2PhiraTheme {
        MainScreen(
            widthSizeClass = widthSizeClass,
            inputState = uiState.inputState,
            exportState = uiState.exportState,
            settingsState = uiState.settingsState,
            selectedBeatmapPath = uiState.selectedBeatmapPath,
            isBeatmapSelectionOpen = uiState.isBeatmapSelectionOpen,
            onChooseOsz = onChooseOsz,
            onChooseBeatmap = onChooseBeatmap,
            onConfirmBeatmap = onConfirmBeatmap,
            onDismissBeatmap = onDismissBeatmap,
            onSettingsChange = onSettingsChange,
            onExport = onExport,
            onCancelExport = onCancelExport,
        )
    }
}
