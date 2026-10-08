package com.akiranyx.osu2phira.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.akiranyx.osu2phira.ConversionSettingsFormState
import com.akiranyx.osu2phira.domain.AudioOffsetMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.osu2PhiraSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "osz2phira_settings")

private val RPE_BASE_SPEED = stringPreferencesKey("rpe_base_speed")
private val AUDIO_OFFSET_MODE = stringPreferencesKey("audio_offset_mode")
private val MANUAL_AUDIO_OFFSET = stringPreferencesKey("manual_audio_offset")
private val JUDGE_LINE_Y = stringPreferencesKey("judge_line_y")

/** Durable user settings boundary. URI persistence remains owned by FileRepository. */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.osu2PhiraSettingsDataStore
    private val defaults = ConversionSettingsFormState.defaults()

    val settings: Flow<ConversionSettingsFormState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences.toSettings(defaults) }

    suspend fun save(state: ConversionSettingsFormState) {
        dataStore.edit { preferences ->
            preferences[RPE_BASE_SPEED] = state.rpeBaseSpeedText
            preferences[AUDIO_OFFSET_MODE] = state.audioOffsetMode.name
            preferences[MANUAL_AUDIO_OFFSET] = state.manualAudioOffsetText
            preferences[JUDGE_LINE_Y] = state.judgeLineYText
        }
    }

}

private fun Preferences.toSettings(defaults: ConversionSettingsFormState): ConversionSettingsFormState =
    ConversionSettingsFormState(
        rpeBaseSpeedText = this[RPE_BASE_SPEED] ?: defaults.rpeBaseSpeedText,
        audioOffsetMode = this[AUDIO_OFFSET_MODE]
            ?.let { value -> runCatching { AudioOffsetMode.valueOf(value) }.getOrNull() }
            ?: defaults.audioOffsetMode,
        manualAudioOffsetText = this[MANUAL_AUDIO_OFFSET]
            ?: defaults.manualAudioOffsetText,
        judgeLineYText = this[JUDGE_LINE_Y] ?: defaults.judgeLineYText,
    )
