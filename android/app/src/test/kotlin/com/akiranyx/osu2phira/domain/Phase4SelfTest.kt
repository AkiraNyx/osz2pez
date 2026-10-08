package com.akiranyx.osu2phira.domain

import java.nio.file.Files
import java.util.zip.ZipFile

/** Framework-free phase-4 check; runnable with the Kotlin compiler and `main`. */
fun main() {
    val root = Files.createTempDirectory("osz2phira-phase4-root").toFile()
    try {
        val charts = root.resolve("charts").apply { mkdirs() }
        val media = charts.resolve("media").apply { mkdirs() }
        val art = charts.resolve("art").apply { mkdirs() }
        charts.resolve("song.osu").writeText(testBeatmapText(), Charsets.UTF_8)
        media.resolve("chart.json").writeBytes(byteArrayOf(1, 2, 3))
        art.resolve("info.yml").writeBytes(byteArrayOf(4, 5, 6))
        media.resolve("tap.wav").writeBytes(byteArrayOf(7, 8, 9))

        val beatmap = OsuBeatmapParser.parseUtf8(
            charts.resolve("song.osu").readBytes(),
            sourceName = "charts/song.osu",
        )
        val artifact = PezOutputBuilder.build(
            beatmap = beatmap,
            extractedRoot = root,
        )
        check(artifact.packageName == "Demo Unicode - 4K.pez")
        check(artifact.resources.map { it.targetName } == listOf("chart-2.json", "info-2.yml", "tap.wav"))
        check(artifact.chart.notes[0].hitsound == "tap.wav")
        check(artifact.chart.notes[3].hitsound == null)
        check(artifact.warnings.any { it.contains("Hold") })
        check("\"song\": \"chart-2.json\"" in artifact.chartJson)
        check("\"background\": \"info-2.yml\"" in artifact.chartJson)
        check("music: \"chart-2.json\"" in artifact.infoYaml)
        check("illustration: \"info-2.yml\"" in artifact.infoYaml)

        val output = root.resolve("out/demo.pez")
        val report = PezPackageWriter.writeToFile(artifact, output)
        check(report.entries == listOf("info.yml", "chart.json", "chart-2.json", "info-2.yml", "tap.wav"))
        check(report.resourceBytes == 9L)
        ZipFile(output).use { archive ->
            check(archive.getEntry("info.yml") != null)
            check(archive.getEntry("chart.json") != null)
            check(archive.getEntry("chart-2.json") != null)
            check(archive.getEntry("info-2.yml") != null)
            check(archive.getEntry("tap.wav") != null)
            check(archive.entries().asSequence().all { !it.name.contains('/') })
        }
        check(PezPackageValidator.validate(output, artifact).entries == report.entries)

        expectFailure(ConversionFailureCode.OUTPUT_EXISTS) {
            PezPackageWriter.writeToFile(artifact, output)
        }
        check(output.isFile && output.length() > 0L)

        expectFailure(ConversionFailureCode.MISSING_RESOURCE) {
            PezOutputBuilder.build(
                beatmap.copy(audioFilename = "missing.ogg"),
                root,
            )
        }
        expectFailure(ConversionFailureCode.MISSING_BACKGROUND) {
            PezOutputBuilder.build(
                beatmap.copy(backgroundFilename = ""),
                root,
            )
        }
        expectFailure(ConversionFailureCode.UNSUPPORTED_RESOURCE) {
            PezOutputBuilder.build(
                beatmap.copy(audioFilename = "../song.ogg"),
                root,
            )
        }
        media.resolve("empty.wav").writeBytes(byteArrayOf())
        expectFailure(ConversionFailureCode.EMPTY_RESOURCE) {
            PezOutputBuilder.build(
                beatmap.copy(audioFilename = "media/empty.wav"),
                root,
            )
        }
        expectFailure(ConversionFailureCode.UNSUPPORTED_KEY_COUNT) {
            PezOutputBuilder.build(
                beatmap.copy(keyCount = 7),
                root,
            )
        }
        check(artifact.chartJson.endsWith('\n'))
        println("phase-4 self-test passed: metadata, resources, conflicts, ZIP reread, CRC and no-overwrite")
    } finally {
        root.walkBottomUp().forEach { it.delete() }
    }
}

private fun testBeatmapText(): String = """osu file format v14

[General]
AudioFilename: media/chart.json
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
0,0,"art/info.yml",0,0

[TimingPoints]
100,500,4,1,0,100,1,0
100,-100,4,1,0,100,0,0
2000,500,4,1,0,100,1,0
2000,-50,4,1,0,100,0,0

[HitObjects]
64,192,1000,1,0,0:0:0:0:media/tap.wav
192,192,1500,1,0
320,192,1500,1,0
448,192,2000,128,0,3500:0:0:0:0:media/tap.wav
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
