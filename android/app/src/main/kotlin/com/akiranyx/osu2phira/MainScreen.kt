package com.akiranyx.osu2phira

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import com.akiranyx.osu2phira.data.ExportState
import com.akiranyx.osu2phira.data.InputFileState
import com.akiranyx.osu2phira.domain.FailureRecoveryAction
import com.akiranyx.osu2phira.domain.recoveryAction

internal object AppSpacing {
    val unit = 8.dp
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainScreen(
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    inputState: InputFileState = InputFileState.Empty,
    exportState: ExportState = ExportState.Idle,
    settingsState: ConversionSettingsFormState = ConversionSettingsFormState.defaults(),
    selectedBeatmapPath: String? = null,
    isBeatmapSelectionOpen: Boolean = false,
    onChooseOsz: () -> Unit = {},
    onChooseBeatmap: () -> Unit = {},
    onConfirmBeatmap: (String) -> Unit = {},
    onDismissBeatmap: () -> Unit = {},
    onSettingsChange: (ConversionSettingsFormState) -> Unit = {},
    onExport: () -> Unit = {},
    onCancelExport: () -> Unit = {},
) {
    val compact = widthSizeClass == WindowWidthSizeClass.Compact
    val settingsEnabled = exportState.canEditSettings

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.screen_title))
                        Text(
                            text = stringResource(R.string.screen_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (compact) {
                ExportCard(
                    inputState = inputState,
                    exportState = exportState,
                    settingsValid = settingsState.isValid,
                    selectedBeatmapPath = selectedBeatmapPath,
                    onChooseBeatmap = onChooseBeatmap,
                    onExport = onExport,
                    onCancelExport = onCancelExport,
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(AppSpacing.unit * 2),
                )
            }
        },
    ) { innerPadding ->
        if (compact) {
            CompactContent(
                inputState = inputState,
                exportState = exportState,
                settingsState = settingsState,
                settingsEnabled = settingsEnabled,
                onChooseOsz = onChooseOsz,
                onSettingsChange = onSettingsChange,
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            )
        } else {
            WideContent(
                widthSizeClass = widthSizeClass,
                inputState = inputState,
                onChooseOsz = onChooseOsz,
                selectedBeatmapPath = selectedBeatmapPath,
                onChooseBeatmap = onChooseBeatmap,
                exportState = exportState,
                settingsState = settingsState,
                settingsEnabled = settingsEnabled,
                settingsValid = settingsState.isValid,
                onSettingsChange = onSettingsChange,
                onExport = onExport,
                onCancelExport = onCancelExport,
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            )
        }
    }
    val inspection = (inputState as? InputFileState.Ready)?.inspection
    if (isBeatmapSelectionOpen && inspection != null && inspection.beatmapCandidates.size > 1) {
        BeatmapSelectionDialog(
            candidates = inspection.beatmapCandidates,
            selectedPath = selectedBeatmapPath,
            onConfirm = onConfirmBeatmap,
            onDismiss = onDismissBeatmap,
        )
    }
}

@Composable
private fun CompactContent(
    inputState: InputFileState,
    exportState: ExportState,
    settingsState: ConversionSettingsFormState,
    settingsEnabled: Boolean,
    onChooseOsz: () -> Unit,
    onSettingsChange: (ConversionSettingsFormState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(AppSpacing.unit * 2),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.unit * 2),
    ) {
        InputFileCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
        SettingsSection(
            state = settingsState,
            enabled = settingsEnabled,
            onStateChange = onSettingsChange,
        )
        ResultCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
    }
}

@Composable
private fun WideContent(
    widthSizeClass: WindowWidthSizeClass,
    inputState: InputFileState,
    onChooseOsz: () -> Unit,
    selectedBeatmapPath: String?,
    onChooseBeatmap: () -> Unit,
    exportState: ExportState,
    settingsState: ConversionSettingsFormState,
    settingsEnabled: Boolean,
    settingsValid: Boolean,
    onSettingsChange: (ConversionSettingsFormState) -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        NavigationRail(
            windowInsets = WindowInsets(0, 0, 0, 0),
            header = { Text(stringResource(R.string.adaptive_rail_label)) },
        ) {
            NavigationRailItem(
                selected = true,
                onClick = {},
                icon = { Text("↔") },
                label = { Text(stringResource(R.string.compact_navigation_home)) },
            )
        }
        if (widthSizeClass == WindowWidthSizeClass.Medium) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = 1040.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.unit * 2),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.unit * 2),
            ) {
                InputFileCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
                SettingsSection(
                    state = settingsState,
                    enabled = settingsEnabled,
                    onStateChange = onSettingsChange,
                )
                ResultCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
                ExportCard(
                    inputState = inputState,
                    exportState = exportState,
                    settingsValid = settingsValid,
                    selectedBeatmapPath = selectedBeatmapPath,
                    onChooseBeatmap = onChooseBeatmap,
                    onExport = onExport,
                    onCancelExport = onCancelExport,
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 1040.dp)
                    .fillMaxWidth()
                    .padding(AppSpacing.unit * 2),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.unit * 2),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.unit * 2),
                ) {
                    InputFileCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
                    SettingsSection(
                        state = settingsState,
                        enabled = settingsEnabled,
                        onStateChange = onSettingsChange,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.unit * 2),
                ) {
                    ResultCard(inputState = inputState, exportState = exportState, onChooseOsz = onChooseOsz)
                    ExportCard(
                        inputState = inputState,
                        exportState = exportState,
                        settingsValid = settingsValid,
                        selectedBeatmapPath = selectedBeatmapPath,
                        onChooseBeatmap = onChooseBeatmap,
                        onExport = onExport,
                        onCancelExport = onCancelExport,
                    )
                }
            }
        }
    }
}

@Composable
private fun InputFileCard(
    inputState: InputFileState,
    exportState: ExportState,
    onChooseOsz: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.unit * 2),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.unit),
        ) {
            Text(
                text = stringResource(R.string.input_file_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when (inputState) {
                InputFileState.Empty -> {
                    Text(stringResource(R.string.input_file_empty))
                    Text(
                        text = stringResource(R.string.input_file_stage),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                InputFileState.Loading -> Text(stringResource(R.string.input_file_reading))
                is InputFileState.Ready -> {
                    Text(stringResource(R.string.input_file_selected, inputState.inspection.displayName))
                    Text(
                        stringResource(
                            R.string.input_file_archive_summary,
                            inputState.inspection.archive.fileCount,
                            inputState.inspection.archive.osuFileCount,
                        )
                    )
                }

                is InputFileState.Error -> Text(
                    text = inputState.failure.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onChooseOsz,
                enabled = inputState !is InputFileState.Loading && exportState.canChooseInput,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (inputState is InputFileState.Ready) {
                            R.string.choose_osz_again
                        } else {
                            R.string.choose_osz
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    inputState: InputFileState,
    exportState: ExportState,
    onChooseOsz: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.unit * 2),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.unit),
        ) {
            Text(
                text = stringResource(R.string.result_title),
                style = MaterialTheme.typography.titleMedium,
            )
            when (inputState) {
                InputFileState.Empty -> {
                    Text(stringResource(R.string.result_placeholder))
                    Text(
                        text = stringResource(R.string.result_scope),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                InputFileState.Loading -> Text(stringResource(R.string.result_reading))
                is InputFileState.Ready -> {
                    Text(stringResource(R.string.result_read_success))
                    Text(
                        stringResource(
                            R.string.result_archive_summary,
                            inputState.inspection.archive.entries.size,
                            inputState.inspection.archive.totalBytes,
                        )
                    )
                    inputState.inspection.sourceSizeBytes?.let { sizeBytes ->
                        Text(stringResource(R.string.result_source_size, sizeBytes))
                    }
                    if (!inputState.inspection.persistedReadPermission) {
                        Text(
                            text = stringResource(R.string.result_permission_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                is InputFileState.Error -> {
                    Text(
                        text = stringResource(R.string.result_read_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(R.string.error_code, inputState.failure.code.name))
                    Text(inputState.failure.message)
                    Text(
                        stringResource(
                            inputState.failure.recoveryAction().labelRes(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onChooseOsz,
                        enabled = exportState.canChooseInput,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.choose_osz_again))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportCard(
    inputState: InputFileState,
    exportState: ExportState,
    settingsValid: Boolean,
    selectedBeatmapPath: String?,
    onChooseBeatmap: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.unit * 2),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.unit),
        ) {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val candidates = (inputState as? InputFileState.Ready)?.inspection?.beatmapCandidates.orEmpty()
            if (candidates.size > 1) {
                val selected = candidates.firstOrNull { it.sourceName == selectedBeatmapPath }
                if (selected != null) {
                    Text(stringResource(
                        R.string.beatmap_selection_current,
                        selected.version.ifBlank { selected.sourceName },
                    ))
                }
                OutlinedButton(
                    onClick = onChooseBeatmap,
                    enabled = exportState.canChooseInput,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.beatmap_selection_action))
                }
            }
            when (exportState) {
                ExportState.Idle -> Text(
                    stringResource(
                        if (inputState is InputFileState.Ready) {
                            when {
                                !settingsValid -> R.string.export_invalid_settings
                                candidates.size > 1 && selectedBeatmapPath == null ->
                                    R.string.beatmap_selection_required
                                else -> R.string.export_ready
                            }
                        } else {
                            R.string.export_placeholder
                        }
                    )
                )
                ExportState.SelectingOutput -> Text(stringResource(R.string.export_selecting_output))
                ExportState.Exporting -> {
                    Text(stringResource(R.string.export_running))
                    OutlinedButton(
                        onClick = onCancelExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.export_cancel))
                    }
                }
                ExportState.Cancelling -> Text(stringResource(R.string.export_cancelling))
                ExportState.Cancelled -> Text(stringResource(R.string.export_cancelled))
                is ExportState.Success -> Text(
                    stringResource(
                        R.string.export_success,
                        exportState.fileName,
                        exportState.entryCount,
                        exportState.warningCount,
                    )
                )
                is ExportState.Error -> {
                    Text(
                        text = stringResource(R.string.export_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(R.string.error_code, exportState.failure.code.name))
                    Text(exportState.failure.message)
                    Text(
                        stringResource(
                            exportState.failure.recoveryAction().labelRes(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = onExport,
                enabled = inputState is InputFileState.Ready &&
                    settingsValid &&
                    exportState !is ExportState.Exporting &&
                    exportState !is ExportState.SelectingOutput &&
                    exportState !is ExportState.Cancelling,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(
                        if (exportState is ExportState.Exporting ||
                            exportState is ExportState.SelectingOutput ||
                            exportState is ExportState.Cancelling
                        ) {
                            R.string.export_running
                        } else {
                            R.string.export_action
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun BeatmapSelectionDialog(
    candidates: List<com.akiranyx.osu2phira.domain.BeatmapCandidate>,
    selectedPath: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftPath by remember(candidates, selectedPath) { mutableStateOf(selectedPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.beatmap_selection_title)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp).selectableGroup(),
            ) {
                items(candidates, key = { it.sourceName }) { candidate ->
                    val selected = draftPath == candidate.sourceName
                    ListItem(
                        headlineContent = {
                            Text(candidate.version.ifBlank { candidate.sourceName })
                        },
                        supportingContent = {
                            Text(
                                listOf(candidate.title, candidate.artist, candidate.creator)
                                    .filter(String::isNotBlank)
                                    .joinToString(" · ") + "\n" + candidate.sourceName
                            )
                        },
                        leadingContent = { RadioButton(selected = selected, onClick = null) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { draftPath = candidate.sourceName },
                            ),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { draftPath?.let(onConfirm) },
                enabled = draftPath != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.beatmap_selection_confirm)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.beatmap_selection_cancel)) }
        },
    )
}

private val ExportState.canChooseInput: Boolean
    get() = this !is ExportState.SelectingOutput &&
        this !is ExportState.Exporting &&
        this !is ExportState.Cancelling

private val ExportState.canEditSettings: Boolean
    get() = this !is ExportState.SelectingOutput &&
        this !is ExportState.Exporting &&
        this !is ExportState.Cancelling

private fun FailureRecoveryAction.labelRes(): Int = when (this) {
    FailureRecoveryAction.SELECT_INPUT,
    FailureRecoveryAction.RETRY_INPUT -> R.string.error_action_choose_input
    FailureRecoveryAction.ADJUST_SETTINGS -> R.string.error_action_adjust_settings
    FailureRecoveryAction.CHOOSE_OUTPUT -> R.string.error_action_choose_output
    FailureRecoveryAction.RETRY_EXPORT -> R.string.error_action_retry_export
    FailureRecoveryAction.NONE -> R.string.error_action_none
}
