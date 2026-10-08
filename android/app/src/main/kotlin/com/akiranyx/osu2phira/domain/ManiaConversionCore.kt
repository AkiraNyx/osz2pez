package com.akiranyx.osu2phira.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.Locale
import java.util.concurrent.CancellationException

private val DECIMAL_CONTEXT: MathContext = MathContext.DECIMAL128
private val SIXTY_THOUSAND = BigDecimal("60000")
private val ONE_HUNDRED = BigDecimal("100")
private val SCROLL_SPEED_EPSILON = BigDecimal("0.000001")
private val RPE_MAX_INT = BigInteger.valueOf(Int.MAX_VALUE.toLong())

private data class RedSegment(
    val startTimeMs: BigDecimal,
    val beatLengthMs: BigDecimal,
    val cumulativeBeat: Rational,
)

private data class EffectiveSpeedAt(
    val timeMs: BigDecimal,
    val speed: BigDecimal,
)

private data class SpeedSection(
    val startTimeMs: BigDecimal,
    val endTimeMs: BigDecimal,
    val speed: BigDecimal,
)

data class RpeBeatValue(
    val time: RpeTime,
    val exactBeat: Rational,
    val errorMs: BigDecimal,
)

/** Exact multi-BPM and inherited-SV timing conversion for osu!mania. */
class ManiaTimingConverter(timingPoints: List<TimingPoint>) {
    val points: List<TimingPoint> = timingPoints
        .sortedWith(compareBy<TimingPoint> { it.timeMs }.thenBy { it.lineNumber })

    val redLines: List<TimingPoint>
    private val segments: List<RedSegment>
    val firstRedLine: TimingPoint

    init {
        val allRed = points.filter { it.uninherited }
        if (allRed.isEmpty()) {
            timingFailure(
                ConversionFailureCode.MISSING_TIMING_POINT,
                "谱面没有可用的红线 TimingPoint",
            )
        }
        redLines = uniqueByTime(allRed)
        firstRedLine = redLines.first()
        redLines.forEach { point ->
            if (point.beatLengthMs <= BigDecimal.ZERO) {
                timingFailure(
                    ConversionFailureCode.INVALID_TIMING,
                    "第 ${point.lineNumber} 行红线 beatLength 必须大于 0",
                )
            }
        }
        points.filterNot { it.uninherited }.forEach { point ->
            if (point.timeMs < firstRedLine.timeMs) {
                timingFailure(
                    ConversionFailureCode.GREEN_LINE_BEFORE_RED,
                    "第 ${point.lineNumber} 行绿线位于第一条红线之前，无法继承 TimingPoint",
                )
            }
            if (point.beatLengthMs >= BigDecimal.ZERO) {
                timingFailure(
                    ConversionFailureCode.INVALID_GREEN_LINE,
                    "第 ${point.lineNumber} 行绿线 beatLength 必须为负数",
                )
            }
        }

        var cumulative = Rational.of(0L)
        segments = buildList {
            redLines.forEachIndexed { index, point ->
                if (index > 0) {
                    val previous = redLines[index - 1]
                    cumulative += Rational.fromDecimal(point.timeMs - previous.timeMs) /
                        Rational.fromDecimal(previous.beatLengthMs)
                }
                add(
                    RedSegment(
                        startTimeMs = point.timeMs,
                        beatLengthMs = point.beatLengthMs,
                        cumulativeBeat = cumulative,
                    )
                )
            }
        }
    }

    val firstRedTimeMs: BigDecimal
        get() = firstRedLine.timeMs

    fun offsetMs(): Long {
        val integer = try {
            firstRedLine.timeMs.toBigIntegerExact()
        } catch (error: ArithmeticException) {
            timingFailure(
                ConversionFailureCode.RPE_TIME_OUT_OF_RANGE,
                "第一条红线时间必须是整数毫秒，无法写入 RPE META.offset：${firstRedLine.timeMs}",
                error,
            )
        }
        if (integer < RPE_MAX_INT.negate() || integer > RPE_MAX_INT) {
            timingFailure(
                ConversionFailureCode.RPE_TIME_OUT_OF_RANGE,
                "RPE META.offset 超出 i32 范围：$integer",
            )
        }
        return integer.toLong()
    }

    fun beatAt(timeMs: BigDecimal): Rational {
        var selected = segments.first()
        for (segment in segments) {
            if (timeMs < segment.startTimeMs) break
            selected = segment
        }
        return selected.cumulativeBeat +
            Rational.fromDecimal(timeMs - selected.startTimeMs) /
            Rational.fromDecimal(selected.beatLengthMs)
    }

    fun beatLengthAt(timeMs: BigDecimal): BigDecimal {
        var selected = segments.first()
        for (segment in segments) {
            if (timeMs < segment.startTimeMs) break
            selected = segment
        }
        return selected.beatLengthMs
    }

    fun prerollBeats(): Rational = Rational.of(ConversionDefaults.chartPrerollMs).div(
        Rational.fromDecimal(firstRedLine.beatLengthMs)
    )

    fun exactRpeBeat(timeMs: BigDecimal): Rational = beatAt(timeMs) + prerollBeats()

    fun rpeBeatValue(timeMs: BigDecimal): RpeBeatValue {
        val exactBeat = exactRpeBeat(timeMs)
        val encoded = try {
            exactBeat.toRpeTime()
        } catch (error: IllegalArgumentException) {
            timingFailure(
                ConversionFailureCode.RPE_TIME_OUT_OF_RANGE,
                "时间无法编码为 RPE 三元组：$timeMs ms",
                error,
            )
        }
        val encodedBeat = Rational.of(
            BigInteger.valueOf(encoded.integer.toLong()) * BigInteger.valueOf(encoded.denominator) +
                BigInteger.valueOf(encoded.numerator),
            BigInteger.valueOf(encoded.denominator),
        )
        val errorMs = (encodedBeat - exactBeat).abs()
            .toBigDecimal(DECIMAL_CONTEXT)
            .multiply(beatLengthAt(timeMs), DECIMAL_CONTEXT)
            .abs()
        return RpeBeatValue(time = encoded, exactBeat = exactBeat, errorMs = errorMs)
    }

    fun rpeBeat(timeMs: BigDecimal): RpeTime = rpeBeatValue(timeMs).time

    fun bpmList(): List<RpeBpmEvent> = buildList {
        add(
            RpeBpmEvent(
                bpm = bpm(firstRedLine.beatLengthMs),
                startTime = RpeTime(0, 0, 1),
            )
        )
        redLines.forEach { point ->
            add(
                RpeBpmEvent(
                    bpm = bpm(point.beatLengthMs),
                    startTime = rpeBeat(point.timeMs),
                )
            )
        }
    }

    fun speedEvents(maxTimeMs: BigDecimal, baseSpeed: BigDecimal): List<RpeSpeedEvent> {
        if (baseSpeed <= BigDecimal.ZERO) {
            timingFailure(
                ConversionFailureCode.INVALID_RPE_SPEED,
                "RPE 流速必须大于 0",
            )
        }
        val firstTime = firstRedLine.timeMs
        val endLimit = if (maxTimeMs <= firstTime) {
            firstTime + firstRedLine.beatLengthMs
        } else {
            maxTimeMs
        }

        var currentBpm = bpm(firstRedLine.beatLengthMs)
        var currentSv = BigDecimal.ONE
        val effective = mutableListOf<EffectiveSpeedAt>()
        points.forEach { point ->
            if (point.timeMs < firstTime) return@forEach
            if (point.uninherited) {
                currentBpm = bpm(point.beatLengthMs)
                currentSv = BigDecimal.ONE
            } else {
                currentSv = ONE_HUNDRED.negate().divide(point.beatLengthMs, DECIMAL_CONTEXT)
            }
            val next = EffectiveSpeedAt(
                timeMs = point.timeMs,
                speed = currentBpm.multiply(currentSv, DECIMAL_CONTEXT),
            )
            val previous = effective.lastOrNull()
            if (previous != null && previous.timeMs.compareTo(next.timeMs) == 0) {
                effective[effective.lastIndex] = next
            } else {
                effective += next
            }
        }
        if (effective.none { it.timeMs.compareTo(firstTime) == 0 }) {
            effective.add(
                0,
                EffectiveSpeedAt(
                    timeMs = firstTime,
                    speed = currentBpm.multiply(currentSv, DECIMAL_CONTEXT),
                )
            )
        }

        val sections = mutableListOf<SpeedSection>()
        effective.forEachIndexed { index, change ->
            if (change.timeMs >= endLimit) return@forEachIndexed
            val nextTime = effective.getOrNull(index + 1)?.timeMs ?: endLimit
            val end = minOf(nextTime, endLimit)
            if (end > change.timeMs) {
                sections += SpeedSection(change.timeMs, end, change.speed)
            }
        }
        if (sections.isEmpty()) return emptyList()

        val merged = mutableListOf<SpeedSection>()
        sections.forEach { section ->
            val previous = merged.lastOrNull()
            if (previous != null) {
                val scale = maxOf(BigDecimal.ONE, previous.speed.abs(), section.speed.abs())
                val threshold = SCROLL_SPEED_EPSILON.multiply(scale, DECIMAL_CONTEXT)
                if (section.speed.subtract(previous.speed).abs() <= threshold) {
                    merged[merged.lastIndex] = previous.copy(endTimeMs = section.endTimeMs)
                    return@forEach
                }
            }
            merged += section
        }

        var reference = merged.first()
        var referenceDuration = reference.endTimeMs - reference.startTimeMs
        merged.drop(1).forEach { section ->
            val duration = section.endTimeMs - section.startTimeMs
            if (duration > referenceDuration) {
                reference = section
                referenceDuration = duration
            }
        }

        return merged.mapIndexed { index, section ->
            val speed = section.speed
                .divide(reference.speed, DECIMAL_CONTEXT)
                .multiply(baseSpeed, DECIMAL_CONTEXT)
            RpeSpeedEvent(
                start = speed,
                end = speed,
                startTime = if (index == 0) RpeTime(0, 0, 1) else rpeBeat(section.startTimeMs),
                endTime = rpeBeat(section.endTimeMs),
            )
        }
    }

    private fun bpm(beatLengthMs: BigDecimal): BigDecimal =
        SIXTY_THOUSAND.divide(beatLengthMs, DECIMAL_CONTEXT)

    private fun uniqueByTime(points: List<TimingPoint>): List<TimingPoint> {
        val result = mutableListOf<TimingPoint>()
        points.forEach { point ->
            val previous = result.lastOrNull()
            if (previous != null && previous.timeMs.compareTo(point.timeMs) == 0) {
                result[result.lastIndex] = point
            } else {
                result += point
            }
        }
        return result
    }
}

/** Pure Kotlin conversion from parsed osu!mania data to an RPE domain model. */
object ManiaConversionCore {
    fun convertText(
        text: String,
        sourceName: String = "beatmap.osu",
        settings: ConversionSettings = ConversionSettings.defaults(),
        audioTiming: AudioTimingInputs? = null,
    ): ConversionOutcome<ConvertedManiaChart> = try {
        ConversionOutcome.Success(
            convertOrThrow(
                OsuBeatmapParser.parse(text, sourceName),
                settings,
                audioTiming,
            )
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConversionException) {
        ConversionOutcome.Failure(error.failure)
    } catch (error: Throwable) {
        ConversionOutcome.Failure(
            ConversionFailure(
                code = ConversionFailureCode.INTERNAL_ERROR,
                message = "$sourceName：转换失败",
                cause = error,
                sourceName = sourceName,
            )
        )
    }

    fun convertTextOrThrow(
        text: String,
        sourceName: String = "beatmap.osu",
        settings: ConversionSettings = ConversionSettings.defaults(),
        audioTiming: AudioTimingInputs? = null,
    ): ConvertedManiaChart = convertOrThrow(
        OsuBeatmapParser.parse(text, sourceName),
        settings,
        audioTiming,
    )

    fun convert(
        beatmap: OsuBeatmap,
        settings: ConversionSettings = ConversionSettings.defaults(),
        audioTiming: AudioTimingInputs? = null,
    ): ConversionOutcome<ConvertedManiaChart> = try {
        ConversionOutcome.Success(convertOrThrow(beatmap, settings, audioTiming))
    } catch (error: CancellationException) {
        throw error
    } catch (error: ConversionException) {
        ConversionOutcome.Failure(error.failure)
    } catch (error: Throwable) {
        ConversionOutcome.Failure(
            ConversionFailure(
                code = ConversionFailureCode.INTERNAL_ERROR,
                message = "${beatmap.sourceName}：转换失败",
                cause = error,
                sourceName = beatmap.sourceName,
            )
        )
    }

    fun convertOrThrow(
        beatmap: OsuBeatmap,
        settings: ConversionSettings = ConversionSettings.defaults(),
        audioTiming: AudioTimingInputs? = null,
    ): ConvertedManiaChart {
        settings.validate().firstOrNull()?.let { failure ->
            throw ConversionException(failure.withSource(beatmap.sourceName))
        }
        val header = validateBeatmapHeader(beatmap.mode, beatmap.keyCount)
        if (header is BeatmapHeaderValidation.Rejected) {
            throw ConversionException(header.failure.withSource(beatmap.sourceName))
        }

        val timing = ManiaTimingConverter(beatmap.timingPoints)
        val noteValues = mutableListOf<RpeNote>()
        var maxTimingError = BigDecimal.ZERO
        beatmap.notes.forEach { note ->
            if (note.column !in 0 until ConversionDefaults.supportedKeyCount) {
                conversionFailure(
                    ConversionFailureCode.INVALID_HIT_OBJECT,
                    beatmap.sourceName,
                    "第 ${note.lineNumber} 行列号超出 0..3：${note.column}",
                    lineNumber = note.lineNumber.takeIf { it > 0 },
                )
            }
            if (note.hold && note.endMs < note.startMs) {
                conversionFailure(
                    ConversionFailureCode.INVALID_HOLD,
                    beatmap.sourceName,
                    "第 ${note.lineNumber} 行 Hold 结束时间早于开始时间",
                    lineNumber = note.lineNumber.takeIf { it > 0 },
                )
            }
            val start = timing.rpeBeatValue(note.startMs)
            val end = timing.rpeBeatValue(note.endMs)
            maxTimingError = maxOf(maxTimingError, start.errorMs, end.errorMs)
            noteValues += RpeNote(
                type = if (note.hold) 2 else 1,
                startTime = start.time,
                endTime = end.time,
                positionX = positionX(note.column, beatmap.keyCount),
                hitsound = note.sampleFilename.takeIf(String::isNotBlank),
            )
        }

        val maxTimeMs = beatmap.notes.maxOfOrNull { it.endMs } ?: timing.firstRedTimeMs
        val speedEvents = timing.speedEvents(maxTimeMs, settings.rpeBaseSpeed)
        val endTime = timing.rpeBeatValue(maxTimeMs)
        maxTimingError = maxOf(maxTimingError, endTime.errorMs)
        val timingInputs = audioTiming ?: AudioTimingInputs(
            firstRedLineMs = timing.offsetMs(),
        )
        val metaOffset = conversionMetaOffsetMs(timingInputs, settings)
        if (metaOffset !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            conversionFailure(
                ConversionFailureCode.RPE_TIME_OUT_OF_RANGE,
                beatmap.sourceName,
                "RPE META.offset 超出 i32 范围：$metaOffset",
            )
        }

        val name = beatmap.titleUnicode.ifBlank {
            beatmap.title.ifBlank { beatmap.sourceName.substringAfterLast('/').substringBeforeLast('.') }
        }
        val level = beatmap.version.ifBlank { "${beatmap.keyCount}K" }
        return ConvertedManiaChart(
            source = beatmap,
            settings = settings,
            bpmList = timing.bpmList(),
            meta = RpeMeta(
                name = name,
                level = level,
                composer = beatmap.artistUnicode.ifBlank { beatmap.artist },
                charter = beatmap.creator,
                offsetMs = metaOffset,
            ),
            notes = noteValues,
            speedEvents = speedEvents,
            moveYEvent = TimingContract.judgeLineEvent(settings, endTime.time),
            alphaEvent = RpeScalarEvent(
                start = BigDecimal("255"),
                end = BigDecimal("255"),
                startTime = RpeTime(0, 0, 1),
                endTime = endTime.time,
            ),
            warnings = beatmap.warnings,
            maxTimingErrorMs = maxTimingError,
        )
    }

    private fun positionX(column: Int, keyCount: Int): BigDecimal {
        val center = BigDecimal.valueOf(column.toLong()) + BigDecimal("0.5")
        return center
            .divide(BigDecimal.valueOf(keyCount.toLong()), DECIMAL_CONTEXT)
            .subtract(BigDecimal("0.5"))
            .multiply(BigDecimal.valueOf(ConversionDefaults.laneSpan.toLong()), DECIMAL_CONTEXT)
    }

    private fun ConversionFailure.withSource(sourceName: String): ConversionFailure =
        if (this.sourceName == null) {
            copy(sourceName = sourceName, message = "$sourceName：$message")
        } else {
            this
        }
}

private fun conversionMetaOffsetMs(
    input: AudioTimingInputs,
    settings: ConversionSettings,
): Long {
    val automatic = TimingContract.automaticMetaOffsetMs(input) + input.additionalOffsetMs
    return if (settings.audioOffsetMode == AudioOffsetMode.MANUAL) {
        automatic + settings.manualAudioOffsetMs
    } else {
        automatic
    }
}

private fun timingFailure(
    code: ConversionFailureCode,
    reason: String,
    cause: Throwable? = null,
): Nothing = throw ConversionException(
    ConversionFailure(code = code, message = reason, cause = cause)
)

private fun conversionFailure(
    code: ConversionFailureCode,
    sourceName: String,
    reason: String,
    lineNumber: Int? = null,
): Nothing = throw ConversionException(
    ConversionFailure(
        code = code,
        message = "$sourceName${lineNumber?.let { " 第 $it 行" }.orEmpty()}：$reason",
        sourceName = sourceName,
        section = "转换",
        lineNumber = lineNumber,
    )
)
