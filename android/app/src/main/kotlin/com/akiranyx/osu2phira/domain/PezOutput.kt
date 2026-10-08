package com.akiranyx.osu2phira.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PezBuildRequest(
    val beatmap: OsuBeatmap,
    val extractedRoot: File,
    val settings: ConversionSettings = ConversionSettings.defaults(),
    val audioTiming: AudioTimingInputs? = null,
)

data class PezResource(
    val targetName: String,
    val sourceFile: File,
)

data class PezPackageArtifact(
    val packageName: String,
    val infoYaml: String,
    val chartJson: String,
    val resources: List<PezResource>,
    val chart: ConvertedManiaChart,
    val warnings: List<String> = emptyList(),
)

data class PezValidationReport(
    val entries: List<String>,
    val resourceBytes: Long,
)

/** Builds the complete metadata/resource view before any destination is touched. */
object PezOutputBuilder {
    fun build(request: PezBuildRequest): PezPackageArtifact = build(
        beatmap = request.beatmap,
        extractedRoot = request.extractedRoot,
        settings = request.settings,
        audioTiming = request.audioTiming,
    )

    fun build(
        beatmap: OsuBeatmap,
        extractedRoot: File,
        settings: ConversionSettings = ConversionSettings.defaults(),
        audioTiming: AudioTimingInputs? = null,
    ): PezPackageArtifact {
        val root = canonicalDirectory(extractedRoot)
        val converted = ManiaConversionCore.convertOrThrow(beatmap, settings, audioTiming)
        val osuFile = resolveBeatmapFileFromRoot(root, beatmap.sourceName)
        val resources = ResourceCollector()

        val audioSource = resolveResource(root, osuFile, beatmap.audioFilename, "音频")
        val audioTarget = resources.add(audioSource)

        if (beatmap.backgroundFilename.isBlank()) {
            outputFailure(
                ConversionFailureCode.MISSING_BACKGROUND,
                "${beatmap.sourceName}：Events 没有可用的背景图引用",
                sourceName = beatmap.sourceName,
            )
        }
        val backgroundSource = resolveResource(root, osuFile, beatmap.backgroundFilename, "背景图")
        val backgroundTarget = resources.add(backgroundSource)

        val sampleTargets = LinkedHashMap<String, String>()
        val warnings = LinkedHashSet<String>()
        warnings.addAll(converted.warnings)
        beatmap.notes.forEach { note ->
            if (note.sampleFilename.isBlank()) return@forEach
            if (note.hold) {
                warnings += "${beatmap.sourceName} 第 ${note.lineNumber} 行 Hold 的自定义音效已忽略（RPE Hold 不使用 hitsound）"
                return@forEach
            }
            val sampleSource = resolveResource(root, osuFile, note.sampleFilename, "自定义音效")
            sampleTargets[note.sampleFilename] = resources.add(sampleSource)
        }

        if (root.walkTopDown().any { it.isFile && it.extension.equals("osb", ignoreCase = true) }) {
            warnings += "${beatmap.sourceName}：检测到 .osb 故事板，首版未转换"
        }

        val notes = converted.notes.mapIndexed { index, note ->
            val sourceNote = beatmap.notes.getOrNull(index)
            if (note.type == 2) {
                note.copy(hitsound = null)
            } else {
                val target = sourceNote?.sampleFilename?.let(sampleTargets::get)
                note.copy(hitsound = target)
            }
        }
        val chart = converted.copy(
            notes = notes,
            warnings = warnings.toList(),
        )
        val infoYaml = renderInfoYaml(chart, audioTarget, backgroundTarget)
        val chartJson = renderChartJson(chart, audioTarget, backgroundTarget)
        val packageStem = safeOutputStem(
            value = "${chart.meta.name} - ${chart.meta.level}",
            fallback = "chart-${chart.source.keyCount}K",
        )
        return PezPackageArtifact(
            packageName = "$packageStem.pez",
            infoYaml = infoYaml,
            chartJson = chartJson,
            resources = resources.items(),
            chart = chart,
            warnings = warnings.toList(),
        )
    }

    /** Finds the archive-relative source file named by [sourceName]. */
    fun resolveBeatmapFile(extractedRoot: File, sourceName: String): File =
        resolveBeatmapFileFromRoot(canonicalDirectory(extractedRoot), sourceName)

    private fun resolveBeatmapFileFromRoot(root: File, sourceName: String): File {
        val parts = safeRelativeParts(sourceName, "谱面")
        val candidate = canonicalChild(root, parts, ConversionFailureCode.MISSING_RESOURCE, sourceName)
        if (!candidate.isFile) {
            outputFailure(
                ConversionFailureCode.MISSING_RESOURCE,
                "$sourceName：临时目录中找不到对应的 .osu 文件",
                sourceName = sourceName,
            )
        }
        return candidate
    }

    private fun resolveResource(
        root: File,
        osuFile: File,
        reference: String,
        kind: String,
    ): File {
        val parts = safeRelativeParts(reference, kind)
        val parent = osuFile.parentFile ?: root
        val candidate = canonicalChild(
            root = root,
            parts = parts,
            code = ConversionFailureCode.MISSING_RESOURCE,
            original = reference,
            parent = parent,
        )
        if (!candidate.isFile) {
            outputFailure(
                ConversionFailureCode.MISSING_RESOURCE,
                "${osuFile.name}：${kind}不存在：$reference",
                sourceName = osuFile.name,
            )
        }
        if (candidate.length() <= 0L) {
            outputFailure(
                ConversionFailureCode.EMPTY_RESOURCE,
                "${osuFile.name}：${kind}为空文件：$reference",
                sourceName = osuFile.name,
            )
        }
        return candidate
    }

    private fun canonicalDirectory(directory: File): File {
        val root = try {
            directory.canonicalFile
        } catch (error: IOException) {
            outputFailure(
                ConversionFailureCode.MISSING_RESOURCE,
                "无法解析解包临时目录：${directory.path}",
                cause = error,
            )
        }
        if (!root.isDirectory) {
            outputFailure(
                ConversionFailureCode.MISSING_RESOURCE,
                "解包临时目录不存在：${directory.path}",
            )
        }
        return root
    }

    private fun safeRelativeParts(reference: String, kind: String): List<String> {
        val normalized = reference.trim().trim('"').replace('\\', '/')
        if (normalized.isBlank()) {
            outputFailure(
                if (kind == "背景图") ConversionFailureCode.MISSING_BACKGROUND
                else ConversionFailureCode.MISSING_RESOURCE,
                "${kind}引用为空",
            )
        }
        if (normalized.startsWith('/') || normalized.matches(Regex("^[A-Za-z]:.*"))) {
            outputFailure(
                ConversionFailureCode.UNSUPPORTED_RESOURCE,
                "${kind}不能使用绝对路径：$reference",
            )
        }
        val parts = normalized.split('/')
        if (parts.any { it == ".." }) {
            outputFailure(
                ConversionFailureCode.UNSUPPORTED_RESOURCE,
                "${kind}包含路径穿越：$reference",
            )
        }
        val safe = parts.filter { it.isNotEmpty() && it != "." }
        if (safe.isEmpty()) {
            outputFailure(
                ConversionFailureCode.MISSING_RESOURCE,
                "${kind}引用为空：$reference",
            )
        }
        return safe
    }

    private fun canonicalChild(
        root: File,
        parts: List<String>,
        code: ConversionFailureCode,
        original: String,
        parent: File = root,
    ): File {
        val candidate = try {
            var current = parent
            parts.forEach { current = File(current, it) }
            current.canonicalFile
        } catch (error: IOException) {
            outputFailure(code, "资源路径无法解析：$original", error)
        }
        val rootPath = root.path
        if (candidate.path != rootPath && !candidate.path.startsWith(rootPath + File.separator)) {
            outputFailure(
                ConversionFailureCode.UNSUPPORTED_RESOURCE,
                "资源超出解包目录：$original",
            )
        }
        return candidate
    }

    private fun safeOutputStem(value: String, fallback: String): String {
        val cleaned = value.replace(Regex("[<>:\"/\\\\|?*]+"), "_").trim(' ', '.')
        return cleaned.ifBlank { fallback }
    }

    private class ResourceCollector {
        private val sourceToTarget = LinkedHashMap<String, String>()
        private val sourceFiles = LinkedHashMap<String, File>()
        private val used = linkedSetOf("info.yml", "chart.json")

        fun add(source: File): String {
            val canonical = source.canonicalFile
            val key = canonical.path
            sourceToTarget[key]?.let { return it }
            val base = safeAssetName(canonical.name)
            var target = base
            var counter = 2
            while (used.any { it.equals(target, ignoreCase = true) }) {
                val dot = base.lastIndexOf('.')
                val stem = if (dot > 0) base.substring(0, dot) else base
                val extension = if (dot > 0) base.substring(dot) else ""
                target = "$stem-$counter$extension"
                counter += 1
            }
            used += target
            sourceToTarget[key] = target
            sourceFiles[key] = canonical
            return target
        }

        fun items(): List<PezResource> = sourceToTarget.entries.map { (key, target) ->
            PezResource(targetName = target, sourceFile = sourceFiles.getValue(key))
        }

        private fun safeAssetName(value: String): String {
            val cleaned = value.replace(Regex("[<>:\"/\\\\|?*]+"), "_").trim(' ', '.')
            return cleaned.ifBlank { "asset" }
        }
    }
}

/** Writes a validated archive through a local staging file before finalizing it. */
object PezPackageWriter {
    private const val COPY_BUFFER_BYTES = 32 * 1024

    fun writeToFile(artifact: PezPackageArtifact, destination: File): PezValidationReport {
        if (destination.exists()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_EXISTS,
                "输出文件已存在，不覆盖：${destination.path}",
            )
        }
        val parent = destination.parentFile ?: File(".")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            outputFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法创建输出目录：${parent.path}",
            )
        }
        val staging = File(parent, ".${destination.name}.part-${UUID.randomUUID()}")
        try {
            val report = writeToStagingFile(artifact, staging)
            try {
                Files.move(staging.toPath(), destination.toPath())
            } catch (error: IOException) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_WRITE_FAILED,
                    "无法完成输出文件：${destination.path}",
                    error,
                )
            }
            return report
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun writeToStagingFile(artifact: PezPackageArtifact, staging: File): PezValidationReport {
        if (staging.exists()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_EXISTS,
                "临时输出文件已存在，不覆盖：${staging.path}",
            )
        }
        val parent = staging.parentFile ?: File(".")
        if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            outputFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法创建临时输出目录：${parent.path}",
            )
        }
        var created = false
        try {
            FileOutputStream(staging).use { raw ->
                created = true
                ZipOutputStream(BufferedOutputStream(raw)).use { archive ->
                    writeEntry(archive, "info.yml", artifact.infoYaml.toByteArray(Charsets.UTF_8))
                    writeEntry(archive, "chart.json", artifact.chartJson.toByteArray(Charsets.UTF_8))
                    artifact.resources.forEach { resource ->
                        validateResourceTarget(resource.targetName)
                        if (!resource.sourceFile.isFile) {
                            outputFailure(
                                ConversionFailureCode.MISSING_RESOURCE,
                                "资源文件不存在：${resource.sourceFile.path}",
                            )
                        }
                        if (resource.sourceFile.length() <= 0L) {
                            outputFailure(
                                ConversionFailureCode.EMPTY_RESOURCE,
                                "资源文件为空：${resource.sourceFile.path}",
                            )
                        }
                        val entry = ZipEntry(resource.targetName)
                        entry.time = 0L
                        archive.putNextEntry(entry)
                        BufferedInputStream(FileInputStream(resource.sourceFile)).use { input ->
                            copy(input, archive)
                        }
                        archive.closeEntry()
                    }
                }
            }
            return try {
                PezPackageValidator.validate(staging, artifact)
            } catch (error: ConversionException) {
                throw error
            } catch (error: Throwable) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "生成的 .pez 校验失败：${staging.path}",
                    error,
                )
            }
        } catch (error: ConversionException) {
            if (created) staging.delete()
            throw error
        } catch (error: IOException) {
            if (created) staging.delete()
            outputFailure(
                ConversionFailureCode.OUTPUT_WRITE_FAILED,
                "无法写入 .pez 临时文件：${staging.path}",
                error,
            )
        } catch (error: CancellationException) {
            if (created) staging.delete()
            throw error
        } catch (error: RuntimeException) {
            if (created) staging.delete()
            outputFailure(
                ConversionFailureCode.OUTPUT_WRITE_FAILED,
                "无法写入 .pez 临时文件：${staging.path}",
                error,
            )
        }
    }

    private fun writeEntry(archive: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = 0L
        archive.putNextEntry(entry)
        archive.write(bytes)
        archive.closeEntry()
    }

    private fun copy(input: BufferedInputStream, output: ZipOutputStream) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    }

    private fun validateResourceTarget(name: String) {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains('\u0000') ||
            name.startsWith('/') || name.matches(Regex("^[A-Za-z]:.*")) || name == "info.yml" || name == "chart.json"
        ) {
            outputFailure(
                ConversionFailureCode.UNSUPPORTED_RESOURCE,
                "资源输出名称不安全：$name",
            )
        }
    }
}

/** Reopens a generated ZIP and validates structure, references, JSON, and RPE times. */
object PezPackageValidator {
    private const val COPY_BUFFER_BYTES = 32 * 1024

    fun validate(packageFile: File, artifact: PezPackageArtifact? = null): PezValidationReport {
        if (!packageFile.isFile) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "生成的 .pez 不存在：${packageFile.path}",
            )
        }
        val names = ArrayList<String>()
        val nameKeys = HashSet<String>()
        val sizes = LinkedHashMap<String, Long>()
        var infoBytes: ByteArray? = null
        var chartBytes: ByteArray? = null
        var resourceBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(packageFile))).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    validateEntryName(entry.name)
                    if (entry.isDirectory) {
                        outputFailure(
                            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                            "生成的 .pez 包含目录条目：${entry.name}",
                        )
                    }
                    val key = entry.name.lowercase(Locale.ROOT)
                    if (!nameKeys.add(key)) {
                        outputFailure(
                            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                            "生成的 .pez 包含重复或大小写冲突条目：${entry.name}",
                        )
                    }
                    names += entry.name
                    val capture = when (entry.name) {
                        "info.yml", "chart.json" -> ByteArrayOutputStream()
                        else -> null
                    }
                    var size = 0L
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = archive.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count.toLong()
                        capture?.write(buffer, 0, count)
                    }
                    archive.closeEntry() // ZipInputStream checks the entry CRC here.
                    sizes[entry.name] = size
                    if (entry.name == "info.yml") infoBytes = capture?.toByteArray()
                    if (entry.name == "chart.json") chartBytes = capture?.toByteArray()
                    if (entry.name != "info.yml" && entry.name != "chart.json") {
                        if (size <= 0L) {
                            outputFailure(
                                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                                "生成的 .pez 包含空资源：${entry.name}",
                            )
                        }
                        resourceBytes += size
                    }
                }
            }
        } catch (error: ConversionException) {
            throw error
        } catch (error: ZipException) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "生成的 .pez CRC 或 ZIP 结构校验失败：${packageFile.path}",
                error,
            )
        } catch (error: IOException) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "无法重新读取生成的 .pez：${packageFile.path}",
                error,
            )
        }

        if ("info.yml" !in names || "chart.json" !in names) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "生成的 .pez 缺少 info.yml 或 chart.json",
            )
        }
        val infoText = decodeUtf8(infoBytes!!, "info.yml")
        val chartText = decodeUtf8(chartBytes!!, "chart.json")
        if (artifact != null) {
            if (infoText != artifact.infoYaml || chartText != artifact.chartJson) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "重新读取的 .pez 内容与生成内容不一致",
                )
            }
            val expected = buildSet {
                add("info.yml")
                add("chart.json")
                artifact.resources.forEach { add(it.targetName) }
            }
            if (names.toSet() != expected) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "生成的 .pez 包含未声明或缺失的资源",
                )
            }
        }
        val chart = try {
            MiniJson.parse(chartText)
        } catch (error: Throwable) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "chart.json 不是合法 JSON",
                error,
            )
        }
        validateChart(chart, names.toSet())
        val info = parseInfoYaml(infoText)
        validateInfo(info, names.toSet())
        validateMetadataAgreement(chart, info, names.toSet())
        return PezValidationReport(names.toList(), resourceBytes)
    }

    private fun validateEntryName(name: String) {
        if (name.isBlank() || name.contains('\u0000') || name.contains('/') || name.contains('\\') ||
            name.startsWith('/') || name.matches(Regex("^[A-Za-z]:.*")) || name == "." || name == ".."
        ) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "生成的 .pez 包含不安全或外层目录路径：$name",
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray, name: String): String = try {
        bytes.toString(Charsets.UTF_8)
    } catch (error: Throwable) {
        outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$name 不是有效 UTF-8",
            error,
        )
    }

    private fun validateChart(value: Any?, names: Set<String>) {
        val root = asMap(value, "RPE 根对象")
        val bpmList = asList(required(root, "BPMList", "RPE 根对象"), "BPMList")
        bpmList.forEachIndexed { index, item ->
            val event = asMap(item, "BPMList[$index]")
            requireNumber(event, "bpm", "BPMList[$index]")
            requireTime(event["startTime"], "BPMList[$index].startTime")
        }
        val meta = asMap(required(root, "META", "RPE 根对象"), "META")
        if (requireInteger(meta, "RPEVersion", "META") != ConversionDefaults.rpeVersion.toLong()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "META.RPEVersion 必须为 ${ConversionDefaults.rpeVersion}",
            )
        }
        val metaBackground = requireString(meta, "background", "META")
        val metaSong = requireString(meta, "song", "META")
        requireString(meta, "name", "META")
        requireString(meta, "level", "META")
        requireString(meta, "composer", "META")
        requireString(meta, "charter", "META")
        val offset = requireInteger(meta, "offset", "META")
        if (offset !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "META.offset 超出 i32 范围：$offset",
            )
        }
        requireResource(metaBackground, names, "META.background")
        requireResource(metaSong, names, "META.song")

        val lines = asList(required(root, "judgeLineList", "RPE 根对象"), "judgeLineList")
        if (lines.isEmpty()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "judgeLineList 不能为空",
            )
        }
        lines.forEachIndexed { lineIndex, lineValue ->
            val line = asMap(lineValue, "judgeLineList[$lineIndex]")
            val name = requireString(line, "Name", "judgeLineList[$lineIndex]")
            if (name != "4K") {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "judgeLineList[$lineIndex].Name 必须为 4K：$name",
                )
            }
            val texture = requireString(line, "Texture", "judgeLineList[$lineIndex]")
            if (texture != "line.png") requireResource(texture, names, "judgeLineList[$lineIndex].Texture")
            requireInteger(line, "father", "judgeLineList[$lineIndex]")
            requireInteger(line, "isCover", "judgeLineList[$lineIndex]")
            requireNumber(line, "bpmfactor", "judgeLineList[$lineIndex]")
            val notes = asList(required(line, "notes", "judgeLineList[$lineIndex]"), "judgeLineList[$lineIndex].notes")
            val count = requireInteger(line, "numOfNotes", "judgeLineList[$lineIndex]")
            var tappable = 0
            notes.forEachIndexed { noteIndex, noteValue ->
                val note = asMap(noteValue, "judgeLineList[$lineIndex].notes[$noteIndex]")
                val type = requireInteger(note, "type", "note[$noteIndex]")
                if (type != 1L && type != 2L) {
                    outputFailure(
                        ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                        "note[$noteIndex].type 只允许 Tap=1 或 Hold=2：$type",
                    )
                }
                if (type != 2L) tappable += 1
                requireInteger(note, "above", "note[$noteIndex]")
                requireInteger(note, "alpha", "note[$noteIndex]")
                requireInteger(note, "isFake", "note[$noteIndex]")
                requireNumber(note, "positionX", "note[$noteIndex]")
                requireNumber(note, "size", "note[$noteIndex]")
                requireNumber(note, "speed", "note[$noteIndex]")
                requireNumber(note, "visibleTime", "note[$noteIndex]")
                requireNumber(note, "yOffset", "note[$noteIndex]")
                val start = requireTime(note["startTime"], "note[$noteIndex].startTime")
                val end = requireTime(note["endTime"], "note[$noteIndex].endTime")
                if (compareTime(end, start) < 0) {
                    outputFailure(
                        ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                        "note[$noteIndex].endTime 早于 startTime",
                    )
                }
                note["hitsound"]?.let { hitsoundValue ->
                    if (type == 2L) {
                        outputFailure(
                            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                            "Hold 音符不能包含 hitsound",
                        )
                    }
                    val hitsound = asString(hitsoundValue, "note[$noteIndex].hitsound")
                    requireResource(hitsound, names, "note[$noteIndex].hitsound")
                }
            }
            if (count != tappable.toLong()) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "judgeLineList[$lineIndex].numOfNotes 与 Tap 数量不一致",
                )
            }
            val layers = asList(required(line, "eventLayers", "judgeLineList[$lineIndex]"), "eventLayers")
            if (layers.isEmpty()) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "judgeLineList[$lineIndex].eventLayers 不能为空",
                )
            }
            layers.forEachIndexed { layerIndex, layerValue ->
                val layer = asMap(layerValue, "eventLayers[$layerIndex]")
                listOf("speedEvents", "moveYEvents", "alphaEvents").forEach { eventType ->
                    val events = asList(required(layer, eventType, "eventLayers[$layerIndex]"), eventType)
                    events.forEachIndexed { eventIndex, eventValue ->
                        val event = asMap(eventValue, "$eventType[$eventIndex]")
                        requireNumber(event, "start", "$eventType[$eventIndex]")
                        requireNumber(event, "end", "$eventType[$eventIndex]")
                        requireTime(event["startTime"], "$eventType[$eventIndex].startTime")
                        requireTime(event["endTime"], "$eventType[$eventIndex].endTime")
                    }
                }
            }
        }
    }

    private fun parseInfoYaml(text: String): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEachIndexed
            val separator = line.indexOf(':')
            if (separator <= 0) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "info.yml 第 ${index + 1} 行不是受控字段：$line",
                )
            }
            val key = line.substring(0, separator).trim()
            if (result.containsKey(key)) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "info.yml 包含重复字段：$key",
                )
            }
            val valueText = line.substring(separator + 1).trim()
            result[key] = try {
                MiniJson.parse(valueText)
            } catch (error: Throwable) {
                outputFailure(
                    ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                    "info.yml 第 ${index + 1} 行值不是受控 YAML 标量：$key",
                    error,
                )
            }
        }
        return result
    }

    private fun validateInfo(info: Map<String, Any?>, names: Set<String>) {
        val chart = requireString(info, "chart", "info.yml")
        val music = requireString(info, "music", "info.yml")
        val illustration = requireString(info, "illustration", "info.yml")
        requireResource(chart, names, "info.yml.chart")
        requireResource(music, names, "info.yml.music")
        requireResource(illustration, names, "info.yml.illustration")
        if (chart != "chart.json") {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "info.yml.chart 必须指向 chart.json",
            )
        }
        listOf("name", "level", "charter", "composer", "illustrator", "intro").forEach {
            requireString(info, it, "info.yml")
        }
        requireNumber(info, "difficulty", "info.yml")
        requireNumber(info, "previewStart", "info.yml")
        requireNumber(info, "previewEnd", "info.yml")
        requireNumber(info, "aspectRatio", "info.yml")
        requireNumber(info, "backgroundDim", "info.yml")
        requireNumber(info, "lineLength", "info.yml")
        requireNumber(info, "offset", "info.yml")
        if (info["tags"] !is List<*>) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "info.yml.tags 必须为数组",
            )
        }
        if (info["holdPartialCover"] !is Boolean) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "info.yml.holdPartialCover 必须为布尔值",
            )
        }
    }

    private fun validateMetadataAgreement(chartValue: Any?, info: Map<String, Any?>, names: Set<String>) {
        val chart = asMap(chartValue, "RPE 根对象")
        val meta = asMap(required(chart, "META", "RPE 根对象"), "META")
        val music = requireString(info, "music", "info.yml")
        val illustration = requireString(info, "illustration", "info.yml")
        if (requireString(meta, "song", "META") != music || requireString(meta, "background", "META") != illustration) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "info.yml 与 chart.json 的音频或背景引用不一致",
            )
        }
        requireResource(music, names, "info.yml.music")
        requireResource(illustration, names, "info.yml.illustration")
    }

    private fun requireResource(value: String, names: Set<String>, label: String) {
        if (value == "line.png" && label.contains("Texture")) return
        if (value.isBlank()) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 不能为空",
            )
        }
        if (value !in names) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 未指向包内文件：$value",
            )
        }
    }

    private fun requireTime(value: Any?, label: String): List<Long> {
        val list = asList(value, label)
        if (list.size != 3) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 不是 int[3] 时间三元组",
            )
        }
        val values = list.mapIndexed { index, item ->
            val number = item as? java.math.BigDecimal ?: outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label[$index] 不是整数",
            )
            exactLong(number, "$label[$index]")
        }
        val integer = values[0]
        val numerator = values[1]
        val denominator = values[2]
        if (integer !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            numerator < 0L || numerator > ConversionDefaults.maxRpeUnsigned ||
            denominator < 1L || denominator > ConversionDefaults.maxRpeUnsigned ||
            numerator >= denominator
        ) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 超出 RPE i32/u32 时间范围",
            )
        }
        return values
    }

    private fun compareTime(left: List<Long>, right: List<Long>): Int {
        val leftNumerator = left[0].toBigInteger().multiply(left[2].toBigInteger()) + left[1].toBigInteger()
        val rightNumerator = right[0].toBigInteger().multiply(right[2].toBigInteger()) + right[1].toBigInteger()
        return (leftNumerator * right[2].toBigInteger()).compareTo(rightNumerator * left[2].toBigInteger())
    }

    private fun required(map: Map<String, Any?>, key: String, label: String): Any? =
        map[key] ?: outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$label 缺少必填字段：$key",
        )

    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?, label: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$label 必须为对象",
        )

    private fun asList(value: Any?, label: String): List<Any?> =
        value as? List<Any?> ?: outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$label 必须为数组",
        )

    private fun asString(value: Any?, label: String): String =
        value as? String ?: outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$label 必须为字符串",
        )

    private fun requireString(map: Map<String, Any?>, key: String, label: String): String =
        asString(required(map, key, label), "$label.$key")

    private fun requireNumber(map: Map<String, Any?>, key: String, label: String): BigDecimal {
        val value = required(map, key, label)
        return value as? BigDecimal ?: outputFailure(
            ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
            "$label.$key 必须为数字",
        )
    }

    private fun requireInteger(map: Map<String, Any?>, key: String, label: String): Long {
        val value = requireNumber(map, key, label)
        return exactLong(value, "$label.$key")
    }

    private fun exactLong(value: BigDecimal, label: String): Long {
        val integer = try {
            value.toBigIntegerExact()
        } catch (error: ArithmeticException) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 必须为整数",
                error,
            )
        }
        if (integer < BigInteger.valueOf(Long.MIN_VALUE) || integer > BigInteger.valueOf(Long.MAX_VALUE)) {
            outputFailure(
                ConversionFailureCode.OUTPUT_VALIDATION_FAILED,
                "$label 超出整数范围",
            )
        }
        return integer.toLong()
    }
}

private fun outputFailure(
    code: ConversionFailureCode,
    message: String,
    cause: Throwable? = null,
    sourceName: String? = null,
): Nothing = throw ConversionException(
    ConversionFailure(
        code = code,
        message = message,
        cause = cause,
        sourceName = sourceName,
        section = "输出",
    )
)
