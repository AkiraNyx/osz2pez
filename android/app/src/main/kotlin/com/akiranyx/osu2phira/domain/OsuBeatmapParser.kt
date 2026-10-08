package com.akiranyx.osu2phira.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException

private data class OsuSectionLine(
    val lineNumber: Int,
    val raw: String,
)

private typealias OsuSections = Map<String, List<OsuSectionLine>>

/** Parses the small, line-oriented subset of .osu needed by the Mania core. */
object OsuBeatmapParser {
    fun parseUtf8(bytes: ByteArray, sourceName: String = "beatmap.osu"): OsuBeatmap =
        parse(String(bytes, StandardCharsets.UTF_8), sourceName)

    fun parse(text: String, sourceName: String = "beatmap.osu"): OsuBeatmap {
        val sections = parseSections(text)
        val general = sectionValues(sections, "General")
        val mode = general["Mode"]?.let { field ->
            integerValue(field.value, sourceName, "General", field.lineNumber, "Mode")
        } ?: 0

        // Keep non-Mania files representable so the conversion layer can return
        // the stable UNSUPPORTED_MODE error without guessing at their fields.
        if (mode != ConversionDefaults.supportedMode) {
            return OsuBeatmap(
                sourceName = sourceName,
                mode = mode,
                keyCount = 0,
            )
        }

        val audioFilename = requiredValue(
            general,
            "AudioFilename",
            sourceName,
            "General",
        ).value.unquote()
        if (audioFilename.isBlank()) {
            fail(
                ConversionFailureCode.MISSING_REQUIRED_FIELD,
                sourceName,
                "General",
                general["AudioFilename"]?.lineNumber,
                "缺少 AudioFilename",
            )
        }

        val difficulty = sectionValues(sections, "Difficulty")
        val circleSizeField = requiredValue(
            difficulty,
            "CircleSize",
            sourceName,
            "Difficulty",
        )
        val keyCount = integerValue(
            circleSizeField.value,
            sourceName,
            "Difficulty",
            circleSizeField.lineNumber,
            "CircleSize",
        )
        if (keyCount !in 1..ConversionDefaults.maxSupportedKeyCount) {
            fail(
                ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
                sourceName,
                "Difficulty",
                circleSizeField.lineNumber,
                "CircleSize 不在 1..18 范围内：$keyCount",
            )
        }

        val metadata = sectionValues(sections, "Metadata")
        val previewTimeMs = optionalDecimal(
            general["PreviewTime"],
            default = BigDecimal("-1"),
            sourceName = sourceName,
            section = "General",
            fieldName = "PreviewTime",
        )
        val overallDifficulty = optionalDecimal(
            difficulty["OverallDifficulty"],
            default = BigDecimal.ZERO,
            sourceName = sourceName,
            section = "Difficulty",
            fieldName = "OverallDifficulty",
        )
        val timingPoints = parseTimingPoints(sections, sourceName)
        val events = parseEvents(sections)
        val notes = parseHitObjects(sections, keyCount, sourceName)

        return OsuBeatmap(
            sourceName = sourceName,
            mode = mode,
            keyCount = keyCount,
            title = metadata["Title"].valueOrEmpty(),
            titleUnicode = metadata["TitleUnicode"].valueOrEmpty(),
            artist = metadata["Artist"].valueOrEmpty(),
            artistUnicode = metadata["ArtistUnicode"].valueOrEmpty(),
            creator = metadata["Creator"].valueOrEmpty(),
            version = metadata["Version"].valueOrEmpty(),
            audioFilename = audioFilename,
            backgroundFilename = events.background,
            previewTimeMs = previewTimeMs,
            overallDifficulty = overallDifficulty,
            timingPoints = timingPoints,
            notes = notes,
            warnings = events.warnings,
        )
    }

    fun parseOutcome(text: String, sourceName: String = "beatmap.osu"): ConversionOutcome<OsuBeatmap> =
        try {
            ConversionOutcome.Success(parse(text, sourceName))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ConversionException) {
            ConversionOutcome.Failure(error.failure)
        } catch (error: Throwable) {
            ConversionOutcome.Failure(
                ConversionFailure(
                    code = ConversionFailureCode.INTERNAL_ERROR,
                    message = "$sourceName：解析失败",
                    cause = error,
                    sourceName = sourceName,
                )
            )
        }

    private fun parseSections(text: String): OsuSections {
        val sections = linkedMapOf<String, MutableList<OsuSectionLine>>()
        var current: String? = null
        text.removePrefix("\uFEFF").split('\n').forEachIndexed { index, lineWithCr ->
            val rawLine = lineWithCr.trimEnd('\r')
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed
            if (line.startsWith('[') && line.endsWith(']')) {
                val sectionName = line.substring(1, line.length - 1).trim()
                current = sectionName
                sections.getOrPut(sectionName) { mutableListOf() }
                return@forEachIndexed
            }
            current?.let { section ->
                sections.getOrPut(section) { mutableListOf() }
                    .add(OsuSectionLine(index + 1, rawLine.trimEnd()))
            }
        }
        return sections
    }

    private fun sectionValues(
        sections: OsuSections,
        section: String,
    ): Map<String, OsuSectionLine> = buildMap {
        sections[section].orEmpty().forEach { line ->
            val separator = line.raw.indexOf(':')
            if (separator > 0) {
                put(
                    line.raw.substring(0, separator).trim(),
                    OsuSectionLine(line.lineNumber, line.raw.substring(separator + 1).trim()),
                )
            }
        }
    }

    private fun requiredValue(
        values: Map<String, OsuSectionLine>,
        key: String,
        sourceName: String,
        section: String,
    ): OsuSectionLine = values[key] ?: fail(
        ConversionFailureCode.MISSING_REQUIRED_FIELD,
        sourceName,
        section,
        null,
        "缺少 $key",
    )

    private fun optionalDecimal(
        field: OsuSectionLine?,
        default: BigDecimal,
        sourceName: String,
        section: String,
        fieldName: String,
    ): BigDecimal = field?.let {
        decimalValue(it.value, sourceName, section, it.lineNumber, fieldName)
    } ?: default

    private fun decimalValue(
        value: String,
        sourceName: String,
        section: String,
        lineNumber: Int,
        fieldName: String,
    ): BigDecimal = try {
        BigDecimal(value.trim())
    } catch (error: NumberFormatException) {
        fail(
            ConversionFailureCode.INVALID_OSU_FIELD,
            sourceName,
            section,
            lineNumber,
            "$fieldName 不是有效数字：${value.trim()}",
            error,
        )
    }

    private fun integerValue(
        value: String,
        sourceName: String,
        section: String,
        lineNumber: Int,
        fieldName: String,
    ): Int {
        val decimal = decimalValue(value, sourceName, section, lineNumber, fieldName)
        return try {
            val integer = decimal.toBigIntegerExact()
            val min = BigInteger.valueOf(Int.MIN_VALUE.toLong())
            val max = BigInteger.valueOf(Int.MAX_VALUE.toLong())
            if (integer < min || integer > max) {
                throw ArithmeticException("integer out of Int range")
            }
            integer.toInt()
        } catch (error: ArithmeticException) {
            fail(
                ConversionFailureCode.INVALID_OSU_FIELD,
                sourceName,
                section,
                lineNumber,
                "$fieldName 必须是整数：${value.trim()}",
                error,
            )
        }
    }

    private data class ParsedEvents(
        val background: String,
        val warnings: List<String>,
    )

    private fun parseEvents(sections: OsuSections): ParsedEvents {
        var background = ""
        val warnings = mutableListOf<String>()
        sections["Events"].orEmpty().forEach { lineEntry ->
            val line = lineEntry.raw.trim()
            val lower = line.lowercase(Locale.ROOT)
            if (lower.startsWith("//storyboard") || lower.startsWith("storyboard")) {
                warnings += "Events 第 ${lineEntry.lineNumber} 行包含未转换的故事板内容"
            }
            if (lower.startsWith("1,") || lower.startsWith("video,")) {
                warnings += "Events 第 ${lineEntry.lineNumber} 行包含未转换的视频"
                return@forEach
            }
            if (background.isBlank() &&
                (lower.startsWith("0,") || lower.startsWith("background,"))
            ) {
                background = firstQuotedValue(line)
                if (background.isBlank()) {
                    background = line.split(',', limit = 4)
                        .getOrNull(2)
                        ?.trim()
                        ?.trim('"')
                        .orEmpty()
                }
            }
        }
        return ParsedEvents(background = background, warnings = warnings)
    }

    private fun firstQuotedValue(line: String): String {
        val start = line.indexOf('"')
        if (start < 0) return ""
        val end = line.indexOf('"', start + 1)
        return if (end > start) line.substring(start + 1, end) else ""
    }

    private fun parseTimingPoints(
        sections: OsuSections,
        sourceName: String,
    ): List<TimingPoint> {
        val points = sections["TimingPoints"].orEmpty().map { lineEntry ->
            val fields = lineEntry.raw.split(',').map(String::trim)
            if (fields.size < 2) {
                fail(
                    ConversionFailureCode.INVALID_TIMING,
                    sourceName,
                    "TimingPoints",
                    lineEntry.lineNumber,
                    "字段不足：${lineEntry.raw}",
                )
            }
            val timeMs = decimalValue(
                fields[0],
                sourceName,
                "TimingPoints",
                lineEntry.lineNumber,
                "时间",
            )
            val beatLengthMs = decimalValue(
                fields[1],
                sourceName,
                "TimingPoints",
                lineEntry.lineNumber,
                "beatLength",
            )
            val uninherited = integerValue(
                fields.getOrNull(6) ?: "1",
                sourceName,
                "TimingPoints",
                lineEntry.lineNumber,
                "uninherited",
            )
            if (uninherited !in 0..1) {
                fail(
                    ConversionFailureCode.INVALID_TIMING,
                    sourceName,
                    "TimingPoints",
                    lineEntry.lineNumber,
                    "uninherited 必须为 0 或 1",
                )
            }
            if (beatLengthMs.compareTo(BigDecimal.ZERO) == 0) {
                fail(
                    ConversionFailureCode.INVALID_TIMING,
                    sourceName,
                    "TimingPoints",
                    lineEntry.lineNumber,
                    "beatLength 不能为 0",
                )
            }
            if (uninherited == 1 && beatLengthMs < BigDecimal.ZERO) {
                fail(
                    ConversionFailureCode.INVALID_TIMING,
                    sourceName,
                    "TimingPoints",
                    lineEntry.lineNumber,
                    "红线 beatLength 不能为负数",
                )
            }
            TimingPoint(
                timeMs = timeMs,
                beatLengthMs = beatLengthMs,
                uninherited = uninherited == 1,
                lineNumber = lineEntry.lineNumber,
            )
        }.sortedWith(compareBy<TimingPoint> { it.timeMs }.thenBy { it.lineNumber })

        if (points.none { it.uninherited }) {
            fail(
                ConversionFailureCode.MISSING_TIMING_POINT,
                sourceName,
                "TimingPoints",
                null,
                "谱面没有可用的红线 TimingPoint",
            )
        }
        return points
    }

    private fun parseHitObjects(
        sections: OsuSections,
        keyCount: Int,
        sourceName: String,
    ): List<ManiaNote> = sections["HitObjects"].orEmpty().map { lineEntry ->
        val fields = lineEntry.raw.split(',', limit = 6)
        if (fields.size < 5) {
            fail(
                ConversionFailureCode.INVALID_HIT_OBJECT,
                sourceName,
                "HitObjects",
                lineEntry.lineNumber,
                "字段不足：${lineEntry.raw}",
            )
        }
        val x = integerValue(fields[0], sourceName, "HitObjects", lineEntry.lineNumber, "x")
        val startMs = decimalValue(
            fields[2],
            sourceName,
            "HitObjects",
            lineEntry.lineNumber,
            "时间",
        )
        val objectType = integerValue(
            fields[3],
            sourceName,
            "HitObjects",
            lineEntry.lineNumber,
            "type",
        )
        if (x !in 0..512) {
            fail(
                ConversionFailureCode.INVALID_HIT_OBJECT,
                sourceName,
                "HitObjects",
                lineEntry.lineNumber,
                "x 超出 0..512：$x",
            )
        }

        val column = ((x.toLong() * keyCount) / 512L)
            .toInt()
            .coerceIn(0, keyCount - 1)
        val isHold = (objectType and 128) != 0
        val endMs: BigDecimal
        val sampleFilename: String
        if (isHold) {
            val params = fields.getOrNull(5).orEmpty()
            val separator = params.indexOf(':')
            if (separator < 0) {
                fail(
                    ConversionFailureCode.INVALID_HOLD,
                    sourceName,
                    "HitObjects",
                    lineEntry.lineNumber,
                    "Hold 缺少结束时间",
                )
            }
            endMs = decimalValue(
                params.substring(0, separator),
                sourceName,
                "HitObjects",
                lineEntry.lineNumber,
                "Hold 结束时间",
            )
            if (endMs < startMs) {
                fail(
                    ConversionFailureCode.INVALID_HOLD,
                    sourceName,
                    "HitObjects",
                    lineEntry.lineNumber,
                    "Hold 结束时间早于开始时间",
                )
            }
            sampleFilename = customSampleFilename(params.substring(separator + 1))
        } else {
            if ((objectType and 1) == 0) {
                fail(
                    ConversionFailureCode.UNSUPPORTED_OBJECT,
                    sourceName,
                    "HitObjects",
                    lineEntry.lineNumber,
                    "不是支持的 Mania Tap/Hold：type=$objectType",
                )
            }
            endMs = startMs
            sampleFilename = fields.getOrNull(5)?.let(::customSampleFilename).orEmpty()
        }

        ManiaNote(
            column = column,
            startMs = startMs,
            endMs = endMs,
            hold = isHold,
            sampleFilename = sampleFilename,
            lineNumber = lineEntry.lineNumber,
        )
    }

    private fun customSampleFilename(sampleText: String): String =
        sampleText.trim().split(':').getOrNull(4)?.trim()?.trim('"').orEmpty()

    private fun OsuSectionLine?.valueOrEmpty(): String = this?.value?.unquote().orEmpty()

    private val OsuSectionLine.value: String
        get() = raw

    private fun String.unquote(): String = trim().trim('"')

    private fun fail(
        code: ConversionFailureCode,
        sourceName: String,
        section: String?,
        lineNumber: Int?,
        reason: String,
        cause: Throwable? = null,
    ): Nothing {
        val message = buildString {
            append(sourceName)
            section?.let { append(" [$it]") }
            lineNumber?.let { append(" 第 ").append(it).append(" 行") }
            append("：").append(reason)
        }
        throw ConversionException(
            ConversionFailure(
                code = code,
                message = message,
                cause = cause,
                sourceName = sourceName,
                section = section,
                lineNumber = lineNumber,
            )
        )
    }
}
