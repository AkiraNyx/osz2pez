package com.akiranyx.osu2phira

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(
            this,
            MainViewModelFactory,
        )[MainViewModel::class.java]
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onInputUriSelected)
    }
    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        viewModel.onOutputUriSelected(uri)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            Osu2PhiraApp(
                widthSizeClass = windowSizeClass.widthSizeClass,
                uiState = uiState,
                onChooseOsz = { filePicker.launch(OSZ_MIME_TYPES) },
                onChooseBeatmap = viewModel::openBeatmapSelection,
                onConfirmBeatmap = { path ->
                    viewModel.confirmBeatmapSelection(path)?.let(exportPicker::launch)
                },
                onDismissBeatmap = viewModel::dismissBeatmapSelection,
                onSettingsChange = viewModel::updateSettings,
                onExport = {
                    viewModel.beginOutputSelection()?.let(exportPicker::launch)
                },
                onCancelExport = viewModel::cancelExport,
            )
        }

        viewModel.restoreSavedUriIfNeeded()
    }

    private companion object {
        val OSZ_MIME_TYPES = arrayOf("*/*")
    }

    private object MainViewModelFactory : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                val application = checkNotNull(
                    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                )
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(
                    application = application,
                    savedStateHandle = extras.createSavedStateHandle(),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
