package com.akiranyx.osu2phira

import com.akiranyx.osu2phira.domain.ConversionException
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.FourKeyBeatmapSelector
import com.akiranyx.osu2phira.domain.PezBuildRequest
import com.akiranyx.osu2phira.domain.PezOutputBuilder
import com.akiranyx.osu2phira.domain.PezPackageWriter
import com.akiranyx.osu2phira.domain.SafeZipExtractor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One archive-level check of explicit selection through the generated .pez. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory("osz2phira-p09a-").toFile()
    try {
        val osz = root.resolve("multi.osz")
        ZipOutputStream(FileOutputStream(osz)).use { output ->
            fun add(name: String, bytes: ByteArray) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
            add("Easy.osu", beatmap("Easy", 4).toByteArray())
            add("Hard.osu", beatmap("Hard", 4).toByteArray())
            add("Other.osu", beatmap("Other", 7).toByteArray())
            add("audio.ogg", byteArrayOf(1, 2, 3))
            add("background.png", byteArrayOf(4, 5, 6))
        }
        val extracted = root.resolve("extracted").apply { mkdirs() }
        FileInputStream(osz).use { SafeZipExtractor.extract(it, extracted) }

        val candidates = FourKeyBeatmapSelector.discover(extracted)
        check(candidates.map { it.sourceName } == listOf("Easy.osu", "Hard.osu"))
        check(candidates.map { it.version } == listOf("Easy", "Hard"))
        expectFailure(ConversionFailureCode.BEATMAP_SELECTION_REQUIRED) {
            FourKeyBeatmapSelector.select(extracted)
        }
        val selected = FourKeyBeatmapSelector.select(extracted, "Hard.osu")
        check(selected.version == "Hard")
        val artifact = PezOutputBuilder.build(PezBuildRequest(selected, extracted))
        check(artifact.packageName.contains("Hard"))
        check(artifact.chart.source.sourceName == "Hard.osu")
        PezPackageWriter.writeToStagingFile(artifact, root.resolve("selected.pez"))

        extracted.resolve("Hard.osu").writeText(beatmap("Hard", 7))
        expectFailure(ConversionFailureCode.NO_OSU_BEATMAP) {
            FourKeyBeatmapSelector.select(extracted, "Hard.osu")
        }
        check(FourKeyBeatmapSelector.select(extracted).sourceName == "Easy.osu")
        expectFailure(ConversionFailureCode.NO_OSU_BEATMAP) {
            FourKeyBeatmapSelector.select(extracted, "../Hard.osu")
        }
        try {
            FourKeyBeatmapSelector.discover(extracted) { true }
            error("cancelled scan unexpectedly succeeded")
        } catch (_: CancellationException) {
            // Expected before reading the archive.
        }
        args.firstOrNull()?.let { path ->
            val real = root.resolve("real").apply { mkdirs() }
            FileInputStream(File(path)).use { SafeZipExtractor.extract(it, real) }
            val realCandidates = FourKeyBeatmapSelector.discover(real)
            val expectedCount = args.getOrNull(1)?.toIntOrNull() ?: 1
            check(realCandidates.size == expectedCount)
            val sourceName = if (expectedCount == 1) realCandidates.single().sourceName
                else "phase9a-second.osu"
            val chosen = FourKeyBeatmapSelector.select(real, sourceName)
            check(chosen.sourceName == sourceName)
            PezOutputBuilder.build(PezBuildRequest(chosen, real))
            println("real .osz candidate regression passed: count=$expectedCount, chosen=$sourceName")
        }
        println("phase-9a self-test passed: multi-osz selection, package, stale path, 4K gate, cancellation")
    } finally {
        root.walkBottomUp().forEach { it.delete() }
    }
}

private fun expectFailure(code: ConversionFailureCode, block: () -> Unit) {
    try {
        block()
        error("expected $code")
    } catch (error: ConversionException) {
        check(error.code == code) { "expected $code, got ${error.code}" }
    }
}

private fun beatmap(version: String, keys: Int) = """osu file format v14

[General]
AudioFilename: audio.ogg
Mode: 3

[Metadata]
Title: Demo
Artist: Artist
Creator: Mapper
Version: $version

[Difficulty]
CircleSize: $keys
OverallDifficulty: 7

[Events]
0,0,"background.png",0,0

[TimingPoints]
0,500,4,1,0,100,1,0

[HitObjects]
64,192,1000,1,0
""".trimIndent()
