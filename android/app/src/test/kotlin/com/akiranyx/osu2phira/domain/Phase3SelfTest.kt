package com.akiranyx.osu2phira.domain

import java.math.BigDecimal

/** Framework-free phase-3 check; runnable with the Kotlin compiler and `main`. */
fun main() {
    val source = testBeatmapText().replace("\n", "\r\n").let { "\uFEFF$it" }
    val beatmap = when (val result = OsuBeatmapParser.parseOutcome(source, "charts/song.osu")) {
        is ConversionOutcome.Success -> result.value
        is ConversionOutcome.Failure -> error(result.error.message)
    }

    check(beatmap.mode == 3)
    check(beatmap.keyCount == 4)
    check(beatmap.notes.size == 4)
    check(beatmap.notes.count { it.hold } == 1)
    check(beatmap.notes[0].sampleFilename == "clap.wav")
    check(beatmap.notes[1].startMs == beatmap.notes[2].startMs)
    check(beatmap.timingPoints.count { it.uninherited } == 2)

    val chart = when (val result = ManiaConversionCore.convert(beatmap)) {
        is ConversionOutcome.Success -> result.value
        is ConversionOutcome.Failure -> error(result.error.message)
    }
    check(chart.notes.size == 4)
    check(chart.holdCount == 1)
    check(chart.tapCount == 3)
    check(chart.notes.map { it.positionX.compareTo(BigDecimal.valueOf(it.positionX.toDouble())) == 0 }.all { it })
    check(chart.notes.map { it.positionX.toInt() } == listOf(-450, -150, 150, 450))
    check(chart.notes[0].startTime == RpeTime(2, 3, 5))
    check(chart.notes[1].startTime == chart.notes[2].startTime)
    check(chart.notes[3].type == 2)
    check(chart.notes[3].endTime != chart.notes[3].startTime)
    check(chart.bpmList.size == 3)
    check(chart.speedEvents.map { it.start.toDouble() }.map { "%.4f".format(it) } == listOf("16.5000", "33.0000"))
    check(chart.meta.offsetMs == -225L)
    check(chart.meta.name == "Demo Unicode")
    check(chart.judgeLineName == "4K")
    check(chart.moveYEvent.start == -280)
    check(chart.maxTimingErrorMs.compareTo(BigDecimal("0.000001")) <= 0)

    val customized = ManiaConversionCore.convertTextOrThrow(
        source,
        sourceName = "charts/song.osu",
        settings = ConversionSettings(
            rpeBaseSpeed = BigDecimal("20.0"),
            audioOffsetMode = AudioOffsetMode.MANUAL,
            manualAudioOffsetMs = 100,
            judgeLineY = 120,
        ),
        audioTiming = AudioTimingInputs(
            firstRedLineMs = 100,
            audioLeadInMs = 10,
            codecDelayMs = 5,
            additionalOffsetMs = 75,
        ),
    )
    check(customized.speedEvents.map { it.start.toInt() } == listOf(20, 40))
    check(customized.meta.offsetMs == -110L)
    check(customized.moveYEvent.start == 120)

    expectFailure(ConversionFailureCode.UNSUPPORTED_MODE) {
        ManiaConversionCore.convertTextOrThrow(
            "[General]\nMode: 0\n",
            sourceName = "standard.osu",
        )
    }
    expectFailure(ConversionFailureCode.UNSUPPORTED_KEY_COUNT) {
        ManiaConversionCore.convertTextOrThrow(
            source.replace("CircleSize: 4", "CircleSize: 7"),
            sourceName = "seven-key.osu",
        )
    }
    expectFailure(ConversionFailureCode.INVALID_GREEN_LINE) {
        ManiaConversionCore.convertTextOrThrow(
            source.replace("2000,-50", "2000,50"),
            sourceName = "positive-green.osu",
        )
    }
    expectFailure(ConversionFailureCode.INVALID_HOLD) {
        ManiaConversionCore.convertTextOrThrow(
            source.replace("448,192,2000,128,0,3500", "448,192,2000,128,0,1500"),
            sourceName = "bad-hold.osu",
        )
    }
    expectFailure(ConversionFailureCode.UNSUPPORTED_OBJECT) {
        ManiaConversionCore.convertTextOrThrow(
            source.replace("192,192,1500,1,0", "192,192,1500,2,0"),
            sourceName = "slider.osu",
        )
    }
    check(Rational.of(1L, 10L).limitDenominator(BigIntegerConstants.maxUint) == Rational.of(1L, 10L))
    check(Rational.of(1L, 1_000_000_000_000L).toRpeTime().denominator <= 4_294_967_295L)

    println("phase-3 self-test passed: osu sections, Mode 3/4K gate, exact timing, BPM/SV, Tap/Hold and settings")
}

private object BigIntegerConstants {
    val maxUint = java.math.BigInteger("4294967295")
}

private fun testBeatmapText(): String = """osu file format v14

[General]
AudioFilename: song.ogg
PreviewTime: 1000
Mode: 3

[Metadata]
Title: Demo
TitleUnicode: Demo Unicode
Artist: Artist
ArtistUnicode: Artist Unicode
Creator: Mapper
Version: 4K

[Difficulty]
CircleSize: 4
OverallDifficulty: 7

[Events]
0,0,"background.png",0,0

[TimingPoints]
100,500,4,1,0,100,1,0
100,-100,4,1,0,100,0,0
2000,500,4,1,0,100,1,0
2000,-50,4,1,0,100,0,0

[HitObjects]
64,192,1000,1,0,0:0:0:0:clap.wav
192,192,1500,1,0
320,192,1500,1,0
448,192,2000,128,0,3500:0:0:0:
""".trimIndent()

private inline fun expectFailure(
    expected: ConversionFailureCode,
    action: () -> Unit,
) {
    try {
        action()
        error("expected $expected")
    } catch (failure: ConversionException) {
        check(failure.failure.code == expected) {
            "expected $expected, got ${failure.failure.code}: ${failure.failure.message}"
        }
    }
}
