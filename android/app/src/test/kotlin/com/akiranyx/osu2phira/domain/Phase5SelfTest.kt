package com.akiranyx.osu2phira.domain

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Framework-free phase-5 check; runnable with the Kotlin compiler and `main`. */
fun main() {
    failureModelIsStable()
    parserAndSelectorFailuresAreSpecific()
    invalidZipKeepsCauseAndCode()
    stagingFailureCleansPartialOutput()
    cancellationIsNotReportedAsAnUnknownFailure()
    println("phase-5 self-test passed: unified failures, selection, cleanup and cancellation")
}

private fun failureModelIsStable() {
    val cause = IllegalStateException("diagnostic")
    val input = InputFileException(
        InputFileErrorCode.INVALID_ZIP,
        "无法读取合法的 ZIP 文件",
        cause,
    )
    check(input.failure.code == ConversionFailureCode.INVALID_ZIP)
    check(input.failure.cause === cause)
    check(input.failure.recoveryAction() == FailureRecoveryAction.SELECT_INPUT)
    check(
        ConversionFailure(ConversionFailureCode.INVALID_TIMING, "测试错误").recoveryAction() ==
            FailureRecoveryAction.SELECT_INPUT
    )
    check(
        ConversionFailure(ConversionFailureCode.RPE_TIME_OUT_OF_RANGE, "测试错误").recoveryAction() ==
            FailureRecoveryAction.SELECT_INPUT
    )

    ConversionFailureCode.entries.forEach { code ->
        check(ConversionFailure(code, "测试错误").recoveryAction() != FailureRecoveryAction.NONE) {
            "missing recovery action for $code"
        }
    }
}

private fun parserAndSelectorFailuresAreSpecific() {
    val nonMania = ManiaConversionCore.convertText(
        "[General]\nMode: 0\n",
        sourceName = "standard.osu",
    )
    checkFailure(nonMania, ConversionFailureCode.UNSUPPORTED_MODE)

    val invalidField = OsuBeatmapParser.parseOutcome(
        "[General]\nMode: nope\n",
        sourceName = "broken.osu",
    )
    checkFailure(invalidField, ConversionFailureCode.INVALID_OSU_FIELD)
    check((invalidField as ConversionOutcome.Failure).error.sourceName == "broken.osu")

    val root = Files.createTempDirectory("osz2phira-phase5-selector").toFile()
    try {
        expectFailure(ConversionFailureCode.NO_OSU_BEATMAP) {
            FourKeyBeatmapSelector.select(root)
        }

        root.resolve("standard.osu").writeText("[General]\nMode: 0\n", Charsets.UTF_8)
        expectFailure(ConversionFailureCode.UNSUPPORTED_MODE) {
            FourKeyBeatmapSelector.select(root)
        }

        root.resolve("standard.osu").delete()
        root.resolve("seven.osu").writeText(testBeatmapText().replace("CircleSize: 4", "CircleSize: 7"), Charsets.UTF_8)
        expectFailure(ConversionFailureCode.UNSUPPORTED_KEY_COUNT) {
            FourKeyBeatmapSelector.select(root)
        }

        root.resolve("seven.osu").delete()
        root.resolve("four.osu").writeText(testBeatmapText(), Charsets.UTF_8)
        check(FourKeyBeatmapSelector.select(root).keyCount == 4)
    } finally {
        root.walkBottomUp().forEach(File::delete)
    }
}

private fun invalidZipKeepsCauseAndCode() {
    val root = Files.createTempDirectory("osz2phira-phase5-zip").toFile()
    try {
        expectFailure(ConversionFailureCode.INVALID_ZIP) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(byteArrayOf(0x01, 0x02, 0x03)),
                root.resolve("task"),
            )
        }
    } finally {
        root.walkBottomUp().forEach(File::delete)
    }

    val archive = zipOf("../escape.txt" to byteArrayOf(1))
    val traversalRoot = Files.createTempDirectory("osz2phira-phase5-traversal").toFile()
    try {
        expectFailure(ConversionFailureCode.ZIP_PATH_TRAVERSAL) {
            SafeZipExtractor.extract(ByteArrayInputStream(archive), traversalRoot.resolve("task"))
        }
    } finally {
        traversalRoot.walkBottomUp().forEach(File::delete)
    }
}

private fun stagingFailureCleansPartialOutput() {
    val root = Files.createTempDirectory("osz2phira-phase5-output").toFile()
    try {
        val beatmapFile = root.resolve("song.osu")
        root.resolve("song.ogg").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("bg.png").writeBytes(byteArrayOf(4, 5, 6))
        beatmapFile.writeText(testBeatmapText(), Charsets.UTF_8)
        val beatmap = OsuBeatmapParser.parseUtf8(beatmapFile.readBytes(), "song.osu")
        val artifact = PezOutputBuilder.build(beatmap, root)
        val broken = artifact.copy(
            resources = artifact.resources + PezResource("missing.bin", root.resolve("missing.bin")),
        )
        val staging = root.resolve("broken.pez")
        expectFailure(ConversionFailureCode.MISSING_RESOURCE) {
            PezPackageWriter.writeToStagingFile(broken, staging)
        }
        check(!staging.exists()) { "partial staging file remains" }

        val output = root.resolve("ok.pez")
        PezPackageWriter.writeToFile(artifact, output)
        expectFailure(ConversionFailureCode.OUTPUT_EXISTS) {
            PezPackageWriter.writeToFile(artifact, output)
        }
        check(output.isFile && output.length() > 0L)
    } finally {
        root.walkBottomUp().forEach(File::delete)
    }
}

private fun cancellationIsNotReportedAsAnUnknownFailure() {
    val outcome = captureConversionOutcome("转换") {
        throw CancellationException("user cancelled")
    }
    check(outcome is ConversionOutcome.Failure)
    check(outcome.error.code == ConversionFailureCode.CANCELLED)
    check(outcome.error.isCancellation)
    check(outcome.error.cause is CancellationException)

    val unknownCause = IllegalStateException("diagnostic")
    val unknown = captureConversionOutcome("转换") { throw unknownCause }
    check(unknown is ConversionOutcome.Failure)
    check(unknown.error.code == ConversionFailureCode.INTERNAL_ERROR)
    check(unknown.error.cause === unknownCause)
    check(unknown.error.message == "转换失败，请重试")
}

private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
    java.io.ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { archive ->
            entries.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content)
                archive.closeEntry()
            }
        }
        bytes.toByteArray()
    }

private fun checkFailure(
    outcome: ConversionOutcome<*>,
    expected: ConversionFailureCode,
) {
    check(outcome is ConversionOutcome.Failure) { "expected $expected" }
    check(outcome.error.code == expected) {
        "expected $expected, got ${outcome.error.code}: ${outcome.error.message}"
    }
}

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
0,0,"bg.png",0,0

[TimingPoints]
100,500,4,1,0,100,1,0
100,-100,4,1,0,100,0,0

[HitObjects]
64,192,1000,1,0
""".trimIndent()
