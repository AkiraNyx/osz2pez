package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.data.ExportState
import com.akiranyx.osu2phira.domain.AudioOffsetMode
import com.akiranyx.osu2phira.domain.ConversionDefaults
import com.akiranyx.osu2phira.domain.ConversionException
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionOutcome
import com.akiranyx.osu2phira.domain.ConversionSettings
import com.akiranyx.osu2phira.domain.FourKeyBeatmapSelector
import com.akiranyx.osu2phira.domain.PezOutputBuilder
import com.akiranyx.osu2phira.domain.PezPackageValidator
import com.akiranyx.osu2phira.domain.PezPackageWriter
import com.akiranyx.osu2phira.domain.SafeZipExtractor
import com.akiranyx.osu2phira.domain.ZipSafetyLimits
import com.akiranyx.osu2phira.domain.captureConversionOutcome
import com.akiranyx.osu2phira.domain.recoveryAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * P-08 framework-free regression matrix.
 *
 * This intentionally exercises contracts across selector, safe extraction,
 * output validation, settings and state recovery instead of mirroring private
 * implementation details. It can run directly from the compiled test classes.
 */
fun main() {
    val rows = listOf(
        "correctness" to selectorMatrix(),
        "security" to safeZipMatrix(),
        "data-integrity" to outputIntegrityMatrix(),
        "settings" to settingsMatrix(),
        "reliability" to recoveryMatrix(),
    )
    val total = rows.sumOf { it.second }
    check(total >= 30) { "P-08 matrix unexpectedly small: $total" }
    println(
        "phase-8 self-test passed: ${total} matrix cases across " +
            rows.joinToString { "${it.first}=${it.second}" },
    )
}

private data class SelectorCase(
    val name: String,
    val beatmapText: String?,
    val expectedFailure: ConversionFailureCode?,
)

private fun selectorMatrix(): Int {
    val cases = listOf(
        SelectorCase("4k-positive", sampleBeatmapText(), null),
        SelectorCase(
            "5k-rejected",
            sampleBeatmapText().replace("CircleSize: 4", "CircleSize: 5"),
            ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
        ),
        SelectorCase(
            "7k-rejected",
            sampleBeatmapText().replace("CircleSize: 4", "CircleSize: 7"),
            ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
        ),
        SelectorCase(
            "non-mania-rejected",
            sampleBeatmapText().replace("Mode: 3", "Mode: 0"),
            ConversionFailureCode.UNSUPPORTED_MODE,
        ),
        SelectorCase("no-4k-file", null, ConversionFailureCode.NO_OSU_BEATMAP),
    )
    var count = 0
    cases.forEach { case ->
        val root = Files.createTempDirectory("osz2phira-p08-selector-").toFile()
        try {
            case.beatmapText?.let {
                root.resolve("difficulty.osu").writeText(it, Charsets.UTF_8)
            }
            if (case.expectedFailure == null) {
                val selected = FourKeyBeatmapSelector.select(root)
                check(selected.mode == ConversionDefaults.supportedMode)
                check(selected.keyCount == ConversionDefaults.supportedKeyCount)
            } else {
                expectConversionFailure(case.expectedFailure) {
                    FourKeyBeatmapSelector.select(root)
                }
            }
            count += 1
        } finally {
            deleteTree(root)
        }
    }

    val mixedRoot = Files.createTempDirectory("osz2phira-p08-mixed-").toFile()
    try {
        mixedRoot.resolve("a-seven.osu").writeText(
            sampleBeatmapText().replace("CircleSize: 4", "CircleSize: 7"),
            Charsets.UTF_8,
        )
        mixedRoot.resolve("z-four.osu").writeText(sampleBeatmapText(), Charsets.UTF_8)
        check(FourKeyBeatmapSelector.select(mixedRoot).keyCount == 4)
        count += 1
    } finally {
        deleteTree(mixedRoot)
    }
    return count
}

private data class ZipCase(
    val name: String,
    val entries: Array<out Pair<String, ByteArray?>>,
    val expectedFailure: ConversionFailureCode,
    val limits: ZipSafetyLimits = ZipSafetyLimits(),
)

private fun safeZipMatrix(): Int {
    val cases = listOf(
        ZipCase(
            "invalid-signature",
            emptyArray(),
            ConversionFailureCode.INVALID_ZIP,
        ),
        ZipCase(
            "empty-archive",
            emptyArray(),
            ConversionFailureCode.EMPTY_ZIP,
        ),
        ZipCase(
            "traversal",
            arrayOf("../escape.txt" to byteArrayOf(1)),
            ConversionFailureCode.ZIP_PATH_TRAVERSAL,
        ),
        ZipCase(
            "absolute",
            arrayOf("/absolute.txt" to byteArrayOf(1)),
            ConversionFailureCode.ZIP_ABSOLUTE_PATH,
        ),
        ZipCase(
            "duplicate-case",
            arrayOf("Map.osu" to byteArrayOf(1), "map.OSU" to byteArrayOf(2)),
            ConversionFailureCode.ZIP_DUPLICATE_ENTRY,
        ),
        ZipCase(
            "entry-limit",
            arrayOf("one.bin" to byteArrayOf(1), "two.bin" to byteArrayOf(2)),
            ConversionFailureCode.ZIP_ENTRY_LIMIT_EXCEEDED,
            ZipSafetyLimits(maxEntries = 1, maxEntryBytes = 10, maxTotalBytes = 20),
        ),
        ZipCase(
            "entry-size-limit",
            arrayOf("large.bin" to byteArrayOf(1, 2, 3, 4)),
            ConversionFailureCode.ZIP_ENTRY_TOO_LARGE,
            ZipSafetyLimits(maxEntries = 4, maxEntryBytes = 3, maxTotalBytes = 20),
        ),
        ZipCase(
            "total-size-limit",
            arrayOf("a.bin" to byteArrayOf(1, 2, 3), "b.bin" to byteArrayOf(4, 5, 6)),
            ConversionFailureCode.ZIP_TOTAL_TOO_LARGE,
            ZipSafetyLimits(maxEntries = 4, maxEntryBytes = 10, maxTotalBytes = 5),
        ),
        ZipCase(
            "path-length-limit",
            arrayOf("long-name.bin" to byteArrayOf(1)),
            ConversionFailureCode.ZIP_INVALID_ENTRY,
            ZipSafetyLimits(maxPathCharacters = 4),
        ),
    )
    var count = 0
    cases.forEach { case ->
        val root = Files.createTempDirectory("osz2phira-p08-zip-").toFile()
        try {
            val input = if (case.name == "invalid-signature") {
                byteArrayOf(1, 2, 3)
            } else {
                zipOf(*case.entries)
            }
            expectConversionFailure(case.expectedFailure) {
                SafeZipExtractor.extract(ByteArrayInputStream(input), root, case.limits)
            }
            check(root.walkTopDown().none { it.isFile && it.name == "escape.txt" })
            count += 1
        } finally {
            deleteTree(root)
        }
    }

    val validRoot = Files.createTempDirectory("osz2phira-p08-valid-").toFile()
    try {
        val result = SafeZipExtractor.extract(
            ByteArrayInputStream(
                zipOf(
                    "maps\\easy.osu" to sampleBeatmapText().toByteArray(),
                    "audio.ogg" to byteArrayOf(1, 2, 3),
                    "images/" to null,
                ),
            ),
            validRoot,
        )
        check(result.fileCount == 2)
        check(result.osuFileCount == 1)
        check(validRoot.resolve("maps/easy.osu").isFile)
        count += 1
    } finally {
        deleteTree(validRoot)
    }
    return count
}

private fun outputIntegrityMatrix(): Int {
    val root = Files.createTempDirectory("osz2phira-p08-output-").toFile()
    try {
        val charts = root.resolve("charts").apply { mkdirs() }
        val beatmapFile = charts.resolve("song.osu").apply {
            writeText(sampleBeatmapText(), Charsets.UTF_8)
        }
        val audio = charts.resolve("audio.ogg").apply { writeBytes(byteArrayOf(7, 8, 9, 10)) }
        val background = charts.resolve("background.png").apply { writeBytes(byteArrayOf(11, 12, 13)) }
        val sourceDigests = listOf(beatmapFile, audio, background).associateWith(::sha256)

        val beatmap = com.akiranyx.osu2phira.domain.OsuBeatmapParser.parseUtf8(
            beatmapFile.readBytes(),
            "charts/song.osu",
        )
        val artifact = PezOutputBuilder.build(beatmap, root)
        val output = root.resolve("exports/song.pez")
        val report = PezPackageWriter.writeToFile(artifact, output)
        val validation = PezPackageValidator.validate(output, artifact)
        check(report == validation)
        check(report.entries == listOf("info.yml", "chart.json", "audio.ogg", "background.png"))
        ZipFile(output).use { archive ->
            val names = archive.entries().asSequence().map(ZipEntry::getName).toList()
            check(names == report.entries)
            archive.entries().asSequence().forEach { entry ->
                check(entry.crc >= 0L) { "entry CRC missing: ${entry.name}" }
                archive.getInputStream(entry).use { it.readBytes() }
            }
        }
        sourceDigests.forEach { (file, digest) -> check(sha256(file) == digest) }

        val sentinel = root.resolve("exports/existing.pez").apply {
            parentFile?.mkdirs()
            writeBytes("keep-me".toByteArray())
        }
        expectConversionFailure(ConversionFailureCode.OUTPUT_EXISTS) {
            PezPackageWriter.writeToFile(artifact, sentinel)
        }
        check(sentinel.readBytes().contentEquals("keep-me".toByteArray()))

        val entries = artifactEntries(artifact)
        val malformed = listOf(
            "missing-chart" to entries.filterNot { it.first == "chart.json" },
            "nested-entry" to entries + ("nested/extra.bin" to byteArrayOf(1)),
            "missing-resource" to entries.filterNot { it.first == "audio.ogg" },
        )
        malformed.forEach { (name, rawEntries) ->
            val file = root.resolve("exports/malformed-$name.pez")
            writeArchive(file, rawEntries)
            expectConversionFailure(ConversionFailureCode.OUTPUT_VALIDATION_FAILED) {
                PezPackageValidator.validate(file)
            }
        }
        return 8
    } finally {
        deleteTree(root)
    }
}

private fun settingsMatrix(): Int {
    val cases = listOf(
        "speed-min" to ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "0.1"),
        "speed-max" to ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "100.0"),
        "speed-step-reject" to ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "16.55"),
        "offset-min" to ConversionSettingsFormState.defaults().copy(
            audioOffsetMode = AudioOffsetMode.MANUAL,
            manualAudioOffsetText = "-2000",
        ),
        "offset-max" to ConversionSettingsFormState.defaults().copy(
            audioOffsetMode = AudioOffsetMode.MANUAL,
            manualAudioOffsetText = "2000",
        ),
        "offset-range-reject" to ConversionSettingsFormState.defaults().copy(
            audioOffsetMode = AudioOffsetMode.MANUAL,
            manualAudioOffsetText = "2001",
        ),
        "judge-min" to ConversionSettingsFormState.defaults().copy(judgeLineYText = "-450"),
        "judge-max" to ConversionSettingsFormState.defaults().copy(judgeLineYText = "450"),
        "judge-range-reject" to ConversionSettingsFormState.defaults().copy(judgeLineYText = "451"),
        "malformed-reject" to ConversionSettingsFormState.defaults().copy(rpeBaseSpeedText = "fast"),
    )
    cases.forEach { (name, state) ->
        when {
            name.endsWith("reject") -> check(!state.isValid) { "expected invalid settings: ${name}" }
            else -> check(state.isValid) { "expected valid settings: ${name}" }
        }
    }
    val changed = ConversionSettingsFormState.defaults().copy(
        rpeBaseSpeedText = "22.2",
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetText = "125",
        judgeLineYText = "100",
    )
    check(changed.toSettingsOrNull() == ConversionSettings(
        rpeBaseSpeed = java.math.BigDecimal("22.2"),
        audioOffsetMode = AudioOffsetMode.MANUAL,
        manualAudioOffsetMs = 125,
        judgeLineY = 100,
    ))
    check(ConversionSettingsFormState.defaults().toSettingsOrNull() == ConversionSettings.defaults())
    return cases.size + 2
}

private fun recoveryMatrix(): Int {
    val root = Files.createTempDirectory("osz2phira-p08-recovery-").toFile()
    try {
        root.resolve("cancel.osu").writeText(sampleBeatmapText(), Charsets.UTF_8)
        val cancelled = captureConversionOutcome("谱面筛选") {
            FourKeyBeatmapSelector.select(root) { true }
        }
        val cancelledError = (cancelled as? ConversionOutcome.Failure)?.error
            ?: error("expected cancellation failure")
        check(cancelledError.code == ConversionFailureCode.CANCELLED)
        check(cancelledError.cause is CancellationException)

        val unknown = captureConversionOutcome("矩阵") {
            error("synthetic failure")
        }
        val unknownError = (unknown as? ConversionOutcome.Failure)?.error
            ?: error("expected internal failure")
        check(unknownError.code == ConversionFailureCode.INTERNAL_ERROR)
        check(unknownError.cause != null)

        check(MainUiState(exportState = ExportState.Cancelling).operation == MainOperation.CANCELLING)
        check(
            ExportState.Error(ConversionFailureCode.OUTPUT_EXISTS, "existing").recoveryAction ==
                com.akiranyx.osu2phira.domain.FailureRecoveryAction.CHOOSE_OUTPUT
        )
        check(
            ExportState.Error(ConversionFailureCode.CANCELLED, "cancelled").recoveryAction ==
                com.akiranyx.osu2phira.domain.FailureRecoveryAction.RETRY_EXPORT
        )
        return 5
    } finally {
        deleteTree(root)
    }
}

private fun artifactEntries(
    artifact: com.akiranyx.osu2phira.domain.PezPackageArtifact,
): List<Pair<String, ByteArray>> = buildList {
    add("info.yml" to artifact.infoYaml.toByteArray(Charsets.UTF_8))
    add("chart.json" to artifact.chartJson.toByteArray(Charsets.UTF_8))
    artifact.resources.forEach { resource ->
        add(resource.targetName to resource.sourceFile.readBytes())
    }
}

private fun writeArchive(file: File, entries: List<Pair<String, ByteArray>>) {
    file.parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(file)).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(bytes)
            archive.closeEntry()
        }
    }
}

private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { archive ->
        entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name))
            bytes?.let(archive::write)
            archive.closeEntry()
        }
    }
    return output.toByteArray()
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private inline fun expectConversionFailure(
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

private fun deleteTree(root: File) {
    root.walkBottomUp().forEach { it.delete() }
}

private fun sampleBeatmapText(): String = """osu file format v14

[General]
AudioFilename: audio.ogg
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
0,500,4,1,0,100,1,0

[HitObjects]
64,192,1000,1,0
192,192,1500,1,0
320,192,1500,1,0
448,192,2000,128,0,3500:0:0:0:
""".trimIndent()
