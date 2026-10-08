package com.akiranyx.osu2phira

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akiranyx.osu2phira.data.ExportState
import com.akiranyx.osu2phira.data.FileRepository
import com.akiranyx.osu2phira.data.InputFileInspection
import com.akiranyx.osu2phira.data.InputFileState
import com.akiranyx.osu2phira.data.PezExportService
import com.akiranyx.osu2phira.data.SettingsRepository
import com.akiranyx.osu2phira.domain.ConversionException
import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionOutcome
import com.akiranyx.osu2phira.domain.ConversionSettings
import com.akiranyx.osu2phira.domain.InputFileException
import com.akiranyx.osu2phira.domain.isCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Owns all screen state and asynchronous work for the main conversion flow. */
class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val fileRepository = FileRepository(application)
    private val exportService = PezExportService(application.contentResolver, application.cacheDir)
    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var settingsTouched = false
    private var inspectJob: Job? = null
    private var exportJob: Job? = null
    private var inspectGeneration = 0L
    private var exportGeneration = 0L
    private var userCancelledExportGeneration: Long? = null
    private var pendingExportSettings: ConversionSettings? = readPendingExportSettings()

    init {
        viewModelScope.launch {
            val persisted = try {
                settingsRepository.settings.first()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                ConversionSettingsFormState.defaults()
            }
            if (!settingsTouched) {
                _uiState.update { current -> current.copy(settingsState = persisted) }
            }
        }
    }

    /** Re-reads the durable URI only for a new ViewModel instance. */
    fun restoreSavedUriIfNeeded() {
        if (_uiState.value.inputState != InputFileState.Empty || inspectJob?.isActive == true) return
        val candidate = savedStateHandle.get<String>(KEY_SELECTED_URI)
            ?.let(::parseUri)
            ?: fileRepository.savedUri()
        if (candidate != null) inspectUri(candidate, restoreSelection = true)
    }

    fun onInputUriSelected(uri: Uri) {
        if (_uiState.value.exportState.isBusy()) return
        inspectUri(uri)
    }

    fun updateSettings(next: ConversionSettingsFormState) {
        if (_uiState.value.exportState.isBusy()) return
        settingsTouched = true
        _uiState.update { current -> current.copy(settingsState = next) }
        viewModelScope.launch {
            try {
                settingsRepository.save(next)
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // Keep the in-memory state usable; the next edit retries persistence.
            }
        }
    }

    fun openBeatmapSelection() {
        val current = _uiState.value
        val candidates = (current.inputState as? InputFileState.Ready)?.inspection?.beatmapCandidates
        if (candidates != null && candidates.size > 1 && !current.exportState.isBusy()) {
            _uiState.update { it.copy(isBeatmapSelectionOpen = true) }
        }
    }

    fun dismissBeatmapSelection() {
        clearBeatmapSelection()
    }

    /** Confirms a difficulty before the system output picker is launched. */
    fun confirmBeatmapSelection(sourceName: String): String? {
        val current = _uiState.value
        val candidates = (current.inputState as? InputFileState.Ready)?.inspection?.beatmapCandidates
        if (!current.isBeatmapSelectionOpen || current.exportState.isBusy() ||
            candidates?.any { it.sourceName == sourceName } != true
        ) return null
        savedStateHandle[KEY_SELECTED_BEATMAP] = sourceName
        _uiState.update {
            it.copy(selectedBeatmapPath = sourceName, isBeatmapSelectionOpen = false)
        }
        return beginOutputSelection()
    }

    /** Returns the suggested name when an output picker should be launched. */
    fun beginOutputSelection(): String? {
        val current = _uiState.value
        if (current.exportState.isBusy()) return null
        val inspection = (current.inputState as? InputFileState.Ready)?.inspection
        if (inspection == null) {
            setExportError(
                ConversionFailureCode.INPUT_NOT_SELECTED,
                "请先选择并读取 .osz 谱面包",
            )
            return null
        }
        val selectedPath = current.selectedBeatmapPath
            ?: inspection.beatmapCandidates.singleOrNull()?.sourceName
        if (selectedPath == null && inspection.beatmapCandidates.size > 1) {
            _uiState.update { it.copy(isBeatmapSelectionOpen = true) }
            return null
        }
        if (selectedPath == null || inspection.beatmapCandidates.none { it.sourceName == selectedPath }) {
            setExportError(ConversionFailureCode.BEATMAP_SELECTION_REQUIRED, "请先选择要导出的 4K 谱面")
            return null
        }
        savedStateHandle[KEY_SELECTED_BEATMAP] = selectedPath
        _uiState.update { it.copy(selectedBeatmapPath = selectedPath) }
        val settings = current.settingsState.toSettingsOrNull()
        if (settings == null) {
            setExportError(
                current.settingsState.validationFailures.firstOrNull()
                    ?: ConversionFailure(
                        code = ConversionFailureCode.INVALID_RPE_SPEED,
                        message = "请检查转换设置后重试",
                        section = "设置",
                    )
            )
            return null
        }

        pendingExportSettings = settings
        persistPendingExportSettings(settings)
        _uiState.update { state -> state.copy(exportState = ExportState.SelectingOutput) }
        return defaultOutputName(inspection.displayName)
    }

    fun onOutputUriSelected(uri: Uri?) {
        val settings = pendingExportSettings ?: readPendingExportSettings()
        if (uri == null) {
            clearPendingExport()
            clearBeatmapSelection()
            if (_uiState.value.exportState is ExportState.SelectingOutput) {
                _uiState.update { state -> state.copy(exportState = ExportState.Idle) }
            }
            return
        }
        if (settings == null) {
            clearPendingExport()
            clearBeatmapSelection()
            if (_uiState.value.exportState is ExportState.SelectingOutput) {
                _uiState.update { state -> state.copy(exportState = ExportState.Idle) }
            }
            return
        }

        clearPendingExport()
        val inspection = (_uiState.value.inputState as? InputFileState.Ready)?.inspection
        if (inspection == null) {
            setExportError(
                ConversionFailureCode.INPUT_NOT_SELECTED,
                "请先选择并读取 .osz 谱面包",
            )
            return
        }
        val selectedPath = _uiState.value.selectedBeatmapPath
        if (selectedPath == null || inspection.beatmapCandidates.none { it.sourceName == selectedPath }) {
            setExportError(ConversionFailureCode.BEATMAP_SELECTION_REQUIRED, "谱面选择已失效，请重新选择后导出")
            clearBeatmapSelection()
            return
        }
        exportUri(inspection, uri, selectedPath, settings)
    }

    fun cancelExport() {
        if (_uiState.value.exportState !is ExportState.Exporting) return
        val generation = exportGeneration
        userCancelledExportGeneration = generation
        exportGeneration += 1L
        clearBeatmapSelection()
        _uiState.update { state -> state.copy(exportState = ExportState.Cancelling) }
        exportJob?.cancel()
    }

    private fun inspectUri(uri: Uri, restoreSelection: Boolean = false) {
        val restoredPath = if (restoreSelection) savedStateHandle.get<String>(KEY_SELECTED_BEATMAP) else null
        val generation = ++inspectGeneration
        inspectJob?.cancel()
        clearPendingExport()
        savedStateHandle.remove<String>(KEY_SELECTED_BEATMAP)
        _uiState.update { state ->
            state.copy(
                inputState = InputFileState.Loading,
                exportState = ExportState.Idle,
                selectedBeatmapPath = null,
                isBeatmapSelectionOpen = false,
            )
        }
        savedStateHandle[KEY_SELECTED_URI] = uri.toString()

        val job = viewModelScope.launch {
            val nextState = try {
                val inspection = withContext(Dispatchers.IO) {
                    fileRepository.inspect(uri)
                }
                InputFileState.Ready(inspection)
            } catch (error: InputFileException) {
                if (generation == inspectGeneration && isActive) clearInvalidUri()
                InputFileState.Error(error.failure)
            } catch (error: ConversionException) {
                if (generation == inspectGeneration && isActive) clearInvalidUri()
                InputFileState.Error(error.failure)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation == inspectGeneration && isActive) clearInvalidUri()
                InputFileState.Error(
                    ConversionFailure(
                        code = ConversionFailureCode.URI_READ_FAILED,
                        message = "读取所选文件失败，请重新选择 .osz",
                        cause = error,
                        section = "输入",
                    )
                )
            }
            if (generation == inspectGeneration && isActive) {
                val candidates = (nextState as? InputFileState.Ready)?.inspection?.beatmapCandidates.orEmpty()
                val selectedPath = if (candidates.size == 1) candidates.single().sourceName
                    else restoredPath?.takeIf { path -> candidates.any { it.sourceName == path } }
                if (selectedPath != null) savedStateHandle[KEY_SELECTED_BEATMAP] = selectedPath
                _uiState.update { state ->
                    state.copy(
                        inputState = nextState,
                        exportState = ExportState.Idle,
                        selectedBeatmapPath = selectedPath,
                    )
                }
            }
            if (inspectJob === currentCoroutineContext()[Job]) inspectJob = null
        }
        inspectJob = job
    }

    private fun exportUri(
        inspection: InputFileInspection,
        uri: Uri,
        selectedPath: String,
        settings: ConversionSettings,
    ) {
        if (exportJob?.isActive == true) return
        val generation = ++exportGeneration
        userCancelledExportGeneration = null
        _uiState.update { state -> state.copy(exportState = ExportState.Exporting) }
        val job = viewModelScope.launch {
            val nextState: ExportState? = try {
                val outcome = withContext(Dispatchers.IO) {
                    exportService.exportToUriOutcome(
                        inspection = inspection,
                        outputUri = uri,
                        selectedBeatmapPath = selectedPath,
                        settings = settings,
                        shouldCancel = { !isActive },
                    )
                }
                when (outcome) {
                    is ConversionOutcome.Success -> ExportState.Success(
                        fileName = outcome.value.savedFileName,
                        entryCount = outcome.value.validation.entries.size,
                        warningCount = outcome.value.artifact.warnings.size,
                    )
                    is ConversionOutcome.Failure -> if (outcome.error.isCancellation) {
                        ExportState.Cancelled
                    } else {
                        ExportState.Error(outcome.error)
                    }
                }
            } catch (error: ConversionException) {
                ExportState.Error(error.failure)
            } catch (error: CancellationException) {
                if (userCancelledExportGeneration == generation) ExportState.Cancelled else null
            } catch (error: Throwable) {
                ExportState.Error(
                    ConversionFailure(
                        code = ConversionFailureCode.INTERNAL_ERROR,
                        message = "导出 .pez 失败，请重试",
                        cause = error,
                        section = "输出",
                    )
                )
            }

            if (generation == exportGeneration &&
                nextState != null &&
                (isActive || nextState is ExportState.Cancelled)
            ) {
                _uiState.update { state -> state.copy(exportState = nextState) }
            }
            if (exportJob === currentCoroutineContext()[Job]) {
                exportJob = null
                if (userCancelledExportGeneration == generation) {
                    userCancelledExportGeneration = null
                    if (_uiState.value.exportState is ExportState.Cancelling) {
                        _uiState.update { state -> state.copy(exportState = ExportState.Cancelled) }
                    }
                }
            }
        }
        exportJob = job
    }

    private fun setExportError(code: ConversionFailureCode, message: String) {
        setExportError(ConversionFailure(code = code, message = message, section = "输出"))
    }

    private fun setExportError(failure: ConversionFailure) {
        _uiState.update { state -> state.copy(exportState = ExportState.Error(failure)) }
    }

    private fun clearInvalidUri() {
        savedStateHandle.remove<String>(KEY_SELECTED_URI)
        savedStateHandle.remove<String>(KEY_SELECTED_BEATMAP)
        // A failed new selection must not resurrect the previous input after process death.
        fileRepository.clearSavedUri()
    }

    private fun persistPendingExportSettings(settings: ConversionSettings) {
        PendingExportSettingsCodec.encode(settings).forEach { (key, value) ->
            savedStateHandle[key] = value
        }
    }

    private fun readPendingExportSettings(): ConversionSettings? =
        PendingExportSettingsCodec.decode { key -> savedStateHandle[key] }

    private fun clearPendingExport() {
        pendingExportSettings = null
        PendingExportSettingsCodec.keys.forEach { key -> savedStateHandle.remove<Any>(key) }
    }

    private fun clearBeatmapSelection() {
        savedStateHandle.remove<String>(KEY_SELECTED_BEATMAP)
        _uiState.update {
            it.copy(selectedBeatmapPath = null, isBeatmapSelectionOpen = false)
        }
    }

    private fun defaultOutputName(displayName: String): String {
        val stem = displayName.substringBeforeLast('.', displayName)
            .replace(Regex("[<>:\"/\\\\|?*]+"), "_")
            .trim(' ', '.')
            .ifBlank { "converted" }
        return "$stem.pez"
    }

    override fun onCleared() {
        inspectGeneration += 1L
        exportGeneration += 1L
        inspectJob = null
        exportJob = null
        super.onCleared()
    }

    private companion object {
        const val KEY_SELECTED_URI = "selected_uri"
        const val KEY_SELECTED_BEATMAP = "selected_beatmap"
    }
}

private fun parseUri(value: String): Uri? = try {
    value.trim().takeIf(String::isNotEmpty)?.let(Uri::parse)
} catch (_: RuntimeException) {
    null
}

private fun ExportState.isBusy(): Boolean = this is ExportState.SelectingOutput ||
    this is ExportState.Exporting ||
    this is ExportState.Cancelling
