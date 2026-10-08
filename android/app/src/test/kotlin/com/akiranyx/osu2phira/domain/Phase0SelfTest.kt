package com.akiranyx.osu2phira.domain

import java.math.BigDecimal

/** Framework-free phase-0 check; runnable with the Kotlin compiler and `main`. */
fun main() {
    val defaults = ConversionSettings.defaults()
    check(defaults.rpeBaseSpeed == BigDecimal("16.5"))
    check(defaults.audioOffsetMode == AudioOffsetMode.AUTO)
    check(defaults.manualAudioOffsetMs == 0)
    check(defaults.judgeLineY == -280)
    check(defaults.validate().isEmpty())
    check(defaults.resetToDefaults() == defaults)

    check(
        ConversionSettings(rpeBaseSpeed = BigDecimal("16.55")).validate()
            .single().code == ConversionFailureCode.INVALID_RPE_SPEED
    )
    check(
        ConversionSettings(judgeLineY = 451).validate()
            .single().code == ConversionFailureCode.INVALID_JUDGELINE_POSITION
    )
    check(
        ConversionSettings(manualAudioOffsetMs = 2001).validate()
            .single().code == ConversionFailureCode.INVALID_AUDIO_OFFSET
    )

    val input = AudioTimingInputs(firstRedLineMs = 1195L)
    check(TimingContract.automaticMetaOffsetMs(input) == 795L)
    val manual = defaults.copy(
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetMs = 75,
    )
    check(TimingContract.effectiveMetaOffsetMs(input, manual) == 870L)
    check(TimingContract.effectiveMetaOffsetMs(input, defaults) == 795L)

    val firstRedLine = TimingPoint(
        timeMs = BigDecimal("1195"),
        beatLengthMs = BigDecimal("500"),
        uninherited = true,
    )
    check(TimingContract.prerollBeats(firstRedLine.beatLengthMs) == Rational.of(4L, 5L))
    check(TimingContract.rpeBeatAt(BigDecimal("1195"), firstRedLine) == RpeTime(0, 4, 5))
    check(TimingContract.rpeBeatAt(BigDecimal("1695"), firstRedLine) == RpeTime(1, 4, 5))
    check(TimingContract.rpeSpeed(defaults) == BigDecimal("16.5"))
    check(TimingContract.judgeLineEvent(defaults, RpeTime(4, 0, 1)).start == -280)

    check(validateBeatmapHeader(3, 4) == BeatmapHeaderValidation.Supported)
    val unsupportedKeys = validateBeatmapHeader(3, 7) as BeatmapHeaderValidation.Rejected
    check(unsupportedKeys.failure.code == ConversionFailureCode.UNSUPPORTED_KEY_COUNT)
    val unsupportedMode = validateBeatmapHeader(0, 4) as BeatmapHeaderValidation.Rejected
    check(unsupportedMode.failure.code == ConversionFailureCode.UNSUPPORTED_MODE)

    println("phase-0 self-test passed: defaults, offsets, RPE time, pre-roll, 4K gate")
}
