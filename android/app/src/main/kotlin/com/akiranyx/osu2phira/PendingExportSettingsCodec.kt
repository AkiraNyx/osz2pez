package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionSettings
import java.math.BigDecimal

/** Primitive SavedStateHandle representation for an in-flight output picker. */
internal object PendingExportSettingsCodec {
    const val PENDING_EXPORT = "pending_export"
    const val SPEED = "pending_export_speed"
    const val OFFSET_MODE = "pending_export_offset_mode"
    const val MANUAL_OFFSET = "pending_export_manual_offset"
    const val JUDGE_LINE = "pending_export_judge_line"

    val keys: List<String> = listOf(PENDING_EXPORT, SPEED, OFFSET_MODE, MANUAL_OFFSET, JUDGE_LINE)

    fun encode(settings: ConversionSettings): Map<String, Any> = mapOf(
        PENDING_EXPORT to true,
        SPEED to settings.rpeBaseSpeed.toPlainString(),
        OFFSET_MODE to settings.audioOffsetMode.name,
        MANUAL_OFFSET to settings.manualAudioOffsetMs,
        JUDGE_LINE to settings.judgeLineY,
    )

    fun decode(value: (String) -> Any?): ConversionSettings? {
        if (value(PENDING_EXPORT) != true) return null
        val speed = (value(SPEED) as? String)?.let {
            runCatching { BigDecimal(it) }.getOrNull()
        } ?: return null
        val mode = (value(OFFSET_MODE) as? String)?.let {
            runCatching { AudioOffsetMode.valueOf(it) }.getOrNull()
        } ?: return null
        val manualOffset = value(MANUAL_OFFSET) as? Int ?: return null
        val judgeLineY = value(JUDGE_LINE) as? Int ?: return null
        return ConversionSettings(speed, mode, manualOffset, judgeLineY)
            .takeIf { it.validate().isEmpty() }
    }
}
