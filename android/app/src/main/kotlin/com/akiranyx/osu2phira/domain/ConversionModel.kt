package com.akiranyx.osu2phira.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.CancellationException

/** Single source of truth for the phase-0 4K conversion contract. */
object ConversionDefaults {
    const val supportedMode: Int = 3
    const val supportedKeyCount: Int = 4
    const val maxSupportedKeyCount: Int = 18
    const val chartPrerollMs: Long = 400L
    const val defaultAdditionalAudioOffsetMs: Long = 75L
    const val laneSpan: Int = 1200
    const val maxRpeUnsigned: Long = 4_294_967_295L
    const val defaultJudgeLineY: Int = -280
    const val minJudgeLineY: Int = -450
    const val maxJudgeLineY: Int = 450
    const val judgeLineStep: Int = 1
    const val minManualAudioOffsetMs: Int = -2000
    const val maxManualAudioOffsetMs: Int = 2000
    const val manualAudioOffsetStepMs: Int = 1
    const val rpeVersion: Int = 160

    val defaultRpeBaseSpeed: BigDecimal = BigDecimal("16.5")
    val minRpeBaseSpeed: BigDecimal = BigDecimal("0.1")
    val maxRpeBaseSpeed: BigDecimal = BigDecimal("100.0")
    val rpeBaseSpeedStep: BigDecimal = BigDecimal("0.1")
}

enum class AudioOffsetMode {
    AUTO,
    MANUAL,
}

data class ConversionSettings(
    val rpeBaseSpeed: BigDecimal = ConversionDefaults.defaultRpeBaseSpeed,
    val audioOffsetMode: AudioOffsetMode = AudioOffsetMode.AUTO,
    /** Manual correction relative to the automatically calculated offset. */
    val manualAudioOffsetMs: Int = 0,
    val judgeLineY: Int = ConversionDefaults.defaultJudgeLineY,
) {
    fun validate(): List<ConversionFailure> = buildList {
        if (rpeBaseSpeed < ConversionDefaults.minRpeBaseSpeed ||
            rpeBaseSpeed > ConversionDefaults.maxRpeBaseSpeed ||
            rpeBaseSpeed.subtract(ConversionDefaults.minRpeBaseSpeed)
                .remainder(ConversionDefaults.rpeBaseSpeedStep)
                .compareTo(BigDecimal.ZERO) != 0
        ) {
            add(
                ConversionFailure(
                    ConversionFailureCode.INVALID_RPE_SPEED,
                    "RPE 流速必须在 0.1–100.0 之间，并按 0.1 调整",
                )
            )
        }
        if (manualAudioOffsetMs !in ConversionDefaults.minManualAudioOffsetMs..ConversionDefaults.maxManualAudioOffsetMs) {
            add(
                ConversionFailure(
                    ConversionFailureCode.INVALID_AUDIO_OFFSET,
                    "手动音频偏移必须在 -2000–2000 ms 之间",
                )
            )
        }
        if (judgeLineY !in ConversionDefaults.minJudgeLineY..ConversionDefaults.maxJudgeLineY) {
            add(
                ConversionFailure(
                    ConversionFailureCode.INVALID_JUDGELINE_POSITION,
                    "判定线位置必须在 -450–450 之间",
                )
            )
        }
    }

    fun resetToDefaults(): ConversionSettings = defaults()

    companion object {
        fun defaults(): ConversionSettings = ConversionSettings()
    }
}

enum class ConversionFailureCode {
    INPUT_NOT_SELECTED,
    UNSUPPORTED_FILE_TYPE,
    URI_READ_FAILED,
    TEMP_DIRECTORY_FAILED,
    INVALID_ZIP,
    EMPTY_ZIP,
    ZIP_PATH_TRAVERSAL,
    ZIP_ABSOLUTE_PATH,
    ZIP_INVALID_ENTRY,
    ZIP_DUPLICATE_ENTRY,
    ZIP_ENTRY_LIMIT_EXCEEDED,
    ZIP_ENTRY_TOO_LARGE,
    ZIP_TOTAL_TOO_LARGE,
    ZIP_ENTRY_READ_FAILED,
    NO_OSU_BEATMAP,
    UNSUPPORTED_MODE,
    UNSUPPORTED_KEY_COUNT,
    MISSING_REQUIRED_FIELD,
    INVALID_OSU_FIELD,
    MISSING_TIMING_POINT,
    INVALID_HIT_OBJECT,
    UNSUPPORTED_OBJECT,
    INVALID_HOLD,
    INVALID_GREEN_LINE,
    GREEN_LINE_BEFORE_RED,
    INTERNAL_ERROR,
    INVALID_RPE_SPEED,
    INVALID_AUDIO_OFFSET,
    INVALID_JUDGELINE_POSITION,
    INVALID_TIMING,
    RPE_TIME_OUT_OF_RANGE,
    MISSING_RESOURCE,
    MISSING_BACKGROUND,
    EMPTY_RESOURCE,
    UNSUPPORTED_RESOURCE,
    NO_MANIA_4K,
    BEATMAP_SELECTION_REQUIRED,
    OUTPUT_EXISTS,
    OUTPUT_NOT_WRITABLE,
    OUTPUT_WRITE_FAILED,
    OUTPUT_VALIDATION_FAILED,
    CANCELLED,
}

/** Input failures use the same stable code set as conversion and output failures. */
typealias InputFileErrorCode = ConversionFailureCode

data class ConversionFailure(
    val code: ConversionFailureCode,
    val message: String,
    val cause: Throwable? = null,
    val sourceName: String? = null,
    val section: String? = null,
    val lineNumber: Int? = null,
)

open class ConversionException(
    val failure: ConversionFailure,
) : IllegalArgumentException(failure.message, failure.cause) {
    val code: ConversionFailureCode
        get() = failure.code
}

enum class FailureRecoveryAction {
    SELECT_INPUT,
    RETRY_INPUT,
    ADJUST_SETTINGS,
    CHOOSE_OUTPUT,
    RETRY_EXPORT,
    NONE,
}

fun ConversionFailure.recoveryAction(): FailureRecoveryAction = when (code) {
    ConversionFailureCode.INPUT_NOT_SELECTED,
    ConversionFailureCode.UNSUPPORTED_FILE_TYPE,
    ConversionFailureCode.URI_READ_FAILED,
    ConversionFailureCode.TEMP_DIRECTORY_FAILED,
    ConversionFailureCode.INVALID_ZIP,
    ConversionFailureCode.EMPTY_ZIP,
    ConversionFailureCode.ZIP_PATH_TRAVERSAL,
    ConversionFailureCode.ZIP_ABSOLUTE_PATH,
    ConversionFailureCode.ZIP_INVALID_ENTRY,
    ConversionFailureCode.ZIP_DUPLICATE_ENTRY,
    ConversionFailureCode.ZIP_ENTRY_LIMIT_EXCEEDED,
    ConversionFailureCode.ZIP_ENTRY_TOO_LARGE,
    ConversionFailureCode.ZIP_TOTAL_TOO_LARGE,
    ConversionFailureCode.ZIP_ENTRY_READ_FAILED,
    ConversionFailureCode.NO_OSU_BEATMAP,
    ConversionFailureCode.UNSUPPORTED_MODE,
    ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
    ConversionFailureCode.MISSING_REQUIRED_FIELD,
    ConversionFailureCode.INVALID_OSU_FIELD,
    ConversionFailureCode.MISSING_TIMING_POINT,
    ConversionFailureCode.INVALID_HIT_OBJECT,
    ConversionFailureCode.UNSUPPORTED_OBJECT,
    ConversionFailureCode.INVALID_HOLD,
    ConversionFailureCode.INVALID_GREEN_LINE,
    ConversionFailureCode.GREEN_LINE_BEFORE_RED,
    ConversionFailureCode.MISSING_RESOURCE,
    ConversionFailureCode.MISSING_BACKGROUND,
    ConversionFailureCode.EMPTY_RESOURCE,
    ConversionFailureCode.UNSUPPORTED_RESOURCE,
    ConversionFailureCode.NO_MANIA_4K,
    ConversionFailureCode.RPE_TIME_OUT_OF_RANGE,
    ConversionFailureCode.INVALID_TIMING -> FailureRecoveryAction.SELECT_INPUT

    ConversionFailureCode.BEATMAP_SELECTION_REQUIRED -> FailureRecoveryAction.RETRY_EXPORT

    ConversionFailureCode.INVALID_RPE_SPEED,
    ConversionFailureCode.INVALID_AUDIO_OFFSET,
    ConversionFailureCode.INVALID_JUDGELINE_POSITION -> FailureRecoveryAction.ADJUST_SETTINGS

    ConversionFailureCode.OUTPUT_EXISTS,
    ConversionFailureCode.OUTPUT_NOT_WRITABLE -> FailureRecoveryAction.CHOOSE_OUTPUT

    ConversionFailureCode.OUTPUT_WRITE_FAILED,
    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
    ConversionFailureCode.INTERNAL_ERROR,
    ConversionFailureCode.CANCELLED -> FailureRecoveryAction.RETRY_EXPORT
}

val ConversionFailure.isCancellation: Boolean
    get() = code == ConversionFailureCode.CANCELLED

sealed interface ConversionOutcome<out T> {
    data class Success<T>(val value: T) : ConversionOutcome<T>
    data class Failure(val error: ConversionFailure) : ConversionOutcome<Nothing>
}

/** Captures expected failures at an application boundary without exposing causes to users. */
inline fun <T> captureConversionOutcome(
    section: String,
    block: () -> T,
): ConversionOutcome<T> = try {
    ConversionOutcome.Success(block())
} catch (error: CancellationException) {
    ConversionOutcome.Failure(
        ConversionFailure(
            code = ConversionFailureCode.CANCELLED,
            message = "${section}已取消",
            cause = error,
            section = section,
        )
    )
} catch (error: ConversionException) {
    ConversionOutcome.Failure(error.failure)
} catch (error: Throwable) {
    ConversionOutcome.Failure(
        ConversionFailure(
            code = ConversionFailureCode.INTERNAL_ERROR,
            message = "${section}失败，请重试",
            cause = error,
            section = section,
        )
    )
}

sealed interface BeatmapHeaderValidation {
    data object Supported : BeatmapHeaderValidation
    data class Rejected(val failure: ConversionFailure) : BeatmapHeaderValidation
}

fun validateBeatmapHeader(mode: Int, keyCount: Int): BeatmapHeaderValidation = when {
    mode != ConversionDefaults.supportedMode -> BeatmapHeaderValidation.Rejected(
        ConversionFailure(
            ConversionFailureCode.UNSUPPORTED_MODE,
            "只支持 osu!mania（Mode: 3）",
        )
    )

    keyCount != ConversionDefaults.supportedKeyCount -> BeatmapHeaderValidation.Rejected(
        ConversionFailure(
            ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
            "首版只支持 4K，当前谱面为 ${keyCount}K",
        )
    )

    else -> BeatmapHeaderValidation.Supported
}

data class TimingPoint(
    val timeMs: BigDecimal,
    val beatLengthMs: BigDecimal,
    val uninherited: Boolean,
    val lineNumber: Int = 0,
)

data class ManiaNote(
    val column: Int,
    val startMs: BigDecimal,
    val endMs: BigDecimal = startMs,
    val hold: Boolean = false,
    val sampleFilename: String = "",
    val lineNumber: Int = 0,
)

data class ParsedBeatmap(
    val mode: Int,
    val keyCount: Int,
    val firstRedLine: TimingPoint,
    val notes: List<ManiaNote>,
)

/** Parsed .osu content used by the pure Kotlin conversion core. */
data class OsuBeatmap(
    val sourceName: String,
    val mode: Int,
    val keyCount: Int,
    val title: String = "",
    val titleUnicode: String = "",
    val artist: String = "",
    val artistUnicode: String = "",
    val creator: String = "",
    val version: String = "",
    val audioFilename: String = "",
    val backgroundFilename: String = "",
    val previewTimeMs: BigDecimal = BigDecimal("-1"),
    val overallDifficulty: BigDecimal = BigDecimal.ZERO,
    val timingPoints: List<TimingPoint> = emptyList(),
    val notes: List<ManiaNote> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val firstRedLine: TimingPoint?
        get() = timingPoints.firstOrNull { it.uninherited }
}

data class AudioTimingInputs(
    val firstRedLineMs: Long,
    val audioLeadInMs: Long = 0L,
    val codecDelayMs: Long = 0L,
    val additionalOffsetMs: Long = ConversionDefaults.defaultAdditionalAudioOffsetMs,
)

data class RpeTime(
    val integer: Int,
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(denominator > 0L) { "RPE 时间分母必须大于 0" }
        require(denominator <= ConversionDefaults.maxRpeUnsigned) {
            "RPE 时间分母必须位于 u32 范围内"
        }
        require(numerator in 0L until denominator) { "RPE 时间分子必须位于 [0, denominator)" }
    }

    fun asList(): List<Long> = listOf(integer.toLong(), numerator, denominator)
}

data class RpeSpeedEvent(
    val start: BigDecimal,
    val end: BigDecimal,
    val startTime: RpeTime,
    val endTime: RpeTime,
)

data class RpeBpmEvent(
    val bpm: BigDecimal,
    val startTime: RpeTime,
)

data class RpeScalarEvent(
    val start: BigDecimal,
    val end: BigDecimal,
    val startTime: RpeTime,
    val endTime: RpeTime,
)

data class RpeNote(
    val type: Int,
    val startTime: RpeTime,
    val endTime: RpeTime,
    val positionX: BigDecimal,
    val above: Int = 1,
    val alpha: Int = 255,
    val isFake: Int = 0,
    val size: BigDecimal = BigDecimal.ONE,
    val speed: BigDecimal = BigDecimal.ONE,
    val visibleTime: BigDecimal = BigDecimal("999999.0"),
    val yOffset: BigDecimal = BigDecimal.ZERO,
    val hitsound: String? = null,
)

data class RpeMeta(
    val name: String,
    val level: String,
    val composer: String,
    val charter: String,
    val offsetMs: Long,
)

data class ConvertedManiaChart(
    val source: OsuBeatmap,
    val settings: ConversionSettings,
    val bpmList: List<RpeBpmEvent>,
    val meta: RpeMeta,
    val notes: List<RpeNote>,
    val speedEvents: List<RpeSpeedEvent>,
    val moveYEvent: RpeJudgelinePositionEvent,
    val alphaEvent: RpeScalarEvent,
    val warnings: List<String> = emptyList(),
    val maxTimingErrorMs: BigDecimal = BigDecimal.ZERO,
) {
    val holdCount: Int
        get() = notes.count { it.type == 2 }

    val tapCount: Int
        get() = notes.count { it.type == 1 }

    val judgeLineName: String
        get() = "${source.keyCount}K"
}

data class RpeJudgelinePositionEvent(
    val start: Int,
    val end: Int,
    val startTime: RpeTime,
    val endTime: RpeTime,
)

private fun floorDivide(numerator: BigInteger, denominator: BigInteger): BigInteger {
    val result = numerator.divideAndRemainder(denominator)
    return if (result[1].signum() < 0) result[0] - BigInteger.ONE else result[0]
}

/** Exact rational arithmetic keeps beat positions stable before RPE encoding. */
class Rational private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) {
    override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    operator fun plus(other: Rational): Rational = of(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun minus(other: Rational): Rational = of(
        numerator * other.denominator - other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun div(other: Rational): Rational = of(
        numerator * other.denominator,
        denominator * other.numerator,
    )

    operator fun times(other: Rational): Rational = of(
        numerator * other.numerator,
        denominator * other.denominator,
    )

    operator fun compareTo(other: Rational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    fun abs(): Rational = if (numerator.signum() < 0) of(numerator.negate(), denominator) else this

    /** Port of Fraction.limit_denominator using exact BigInteger arithmetic. */
    fun limitDenominator(maxDenominator: BigInteger): Rational {
        require(maxDenominator >= BigInteger.ONE) { "有理数分母上限必须大于 0" }
        if (denominator <= maxDenominator) return this

        var p0 = BigInteger.ZERO
        var q0 = BigInteger.ONE
        var p1 = BigInteger.ONE
        var q1 = BigInteger.ZERO
        var n = numerator
        var d = denominator
        while (true) {
            val a = floorDivide(n, d)
            val q2 = q0 + a * q1
            if (q2 > maxDenominator) break
            val p2 = p0 + a * p1
            p0 = p1
            q0 = q1
            p1 = p2
            q1 = q2
            val remainder = n - a * d
            n = d
            d = remainder
            if (d == BigInteger.ZERO) return of(p1, q1)
        }

        val k = (maxDenominator - q0) / q1
        val lower = of(p0 + k * p1, q0 + k * q1)
        val upper = of(p1, q1)
        return if ((this - upper).abs() <= (this - lower).abs()) upper else lower
    }

    fun toBigDecimal(mathContext: java.math.MathContext = java.math.MathContext.DECIMAL128): BigDecimal =
        BigDecimal(numerator).divide(BigDecimal(denominator), mathContext)

    fun toRpeTime(): RpeTime {
        val maxInt = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        val maxUint = BigInteger.valueOf(ConversionDefaults.maxRpeUnsigned)
        val limited = limitDenominator(maxUint)

        var whole = limited.numerator.divide(limited.denominator)
        var remainder = limited.numerator.remainder(limited.denominator)
        if (remainder.signum() < 0) {
            whole -= BigInteger.ONE
            remainder += limited.denominator
        }
        if (whole < maxInt.negate() || whole > maxInt) {
            throw IllegalArgumentException("RPE 时间整数部分超出 i32 范围: $whole")
        }
        return RpeTime(
            integer = whole.toInt(),
            numerator = remainder.toLong(),
            denominator = limited.denominator.toLong(),
        )
    }

    companion object {
        fun of(numerator: BigInteger, denominator: BigInteger): Rational {
            require(denominator != BigInteger.ZERO) { "有理数分母不能为 0" }
            val sign = if (denominator.signum() < 0) BigInteger.valueOf(-1L) else BigInteger.ONE
            val positiveNumerator = numerator * sign
            val positiveDenominator = denominator * sign
            val gcd = positiveNumerator.gcd(positiveDenominator)
            return Rational(positiveNumerator / gcd, positiveDenominator / gcd)
        }

        fun of(numerator: Long, denominator: Long = 1L): Rational = of(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator),
        )

        fun fromDecimal(value: BigDecimal): Rational {
            val scale = value.scale()
            return if (scale >= 0) {
                of(value.unscaledValue(), BigInteger.TEN.pow(scale))
            } else {
                of(value.unscaledValue() * BigInteger.TEN.pow(-scale), BigInteger.ONE)
            }
        }
    }
}

object TimingContract {
    fun automaticMetaOffsetMs(input: AudioTimingInputs): Long =
        input.firstRedLineMs + input.audioLeadInMs + input.codecDelayMs - ConversionDefaults.chartPrerollMs

    fun effectiveMetaOffsetMs(input: AudioTimingInputs, settings: ConversionSettings): Long {
        val automatic = automaticMetaOffsetMs(input)
        return if (settings.audioOffsetMode == AudioOffsetMode.MANUAL) {
            automatic + settings.manualAudioOffsetMs
        } else {
            automatic
        }
    }

    fun prerollBeats(firstRedLineBeatLengthMs: BigDecimal): Rational {
        require(firstRedLineBeatLengthMs > BigDecimal.ZERO) { "红线 beatLength 必须大于 0" }
        return Rational.of(ConversionDefaults.chartPrerollMs).div(
            Rational.fromDecimal(firstRedLineBeatLengthMs)
        )
    }

    fun beatAt(timeMs: BigDecimal, firstRedLine: TimingPoint): Rational {
        require(firstRedLine.uninherited) { "首个 TimingPoint 必须是红线" }
        return Rational.fromDecimal(timeMs - firstRedLine.timeMs).div(
            Rational.fromDecimal(firstRedLine.beatLengthMs)
        )
    }

    fun rpeBeatAt(timeMs: BigDecimal, firstRedLine: TimingPoint): RpeTime =
        (beatAt(timeMs, firstRedLine) + prerollBeats(firstRedLine.beatLengthMs)).toRpeTime()

    fun rpeSpeed(settings: ConversionSettings): BigDecimal = settings.rpeBaseSpeed

    fun judgeLineEvent(settings: ConversionSettings, endTime: RpeTime): RpeJudgelinePositionEvent =
        RpeJudgelinePositionEvent(
            start = settings.judgeLineY,
            end = settings.judgeLineY,
            startTime = RpeTime(0, 0, 1),
            endTime = endTime,
        )
}
