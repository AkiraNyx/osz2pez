package com.akiranyx.osu2phira.domain

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

data class BeatmapCandidate(
    val sourceName: String,
    val title: String,
    val artist: String,
    val creator: String,
    val version: String,
)

/** Finds every supported difficulty and refuses to guess when there is more than one. */
object FourKeyBeatmapSelector {
    fun discover(
        extractedRoot: File,
        shouldCancel: () -> Boolean = { false },
    ): List<BeatmapCandidate> = scan(extractedRoot, shouldCancel).map { beatmap ->
        BeatmapCandidate(
            sourceName = beatmap.sourceName,
            title = beatmap.titleUnicode.ifBlank { beatmap.title },
            artist = beatmap.artistUnicode.ifBlank { beatmap.artist },
            creator = beatmap.creator,
            version = beatmap.version,
        )
    }

    fun select(
        extractedRoot: File,
        shouldCancel: () -> Boolean = { false },
    ): OsuBeatmap {
        val supported = scan(extractedRoot, shouldCancel)
        if (supported.size == 1) return supported.single()
        selectionFailure(
            ConversionFailureCode.BEATMAP_SELECTION_REQUIRED,
            "输入包有多个可转换的 4K 难度，请先选择实际要导出的谱面",
        )
    }

    fun select(
        extractedRoot: File,
        sourceName: String,
        shouldCancel: () -> Boolean = { false },
    ): OsuBeatmap = scan(extractedRoot, shouldCancel)
        .firstOrNull { it.sourceName == sourceName }
        ?: selectionFailure(
            ConversionFailureCode.NO_OSU_BEATMAP,
            "已选择的 4K 谱面不存在或不再可转换，请重新选择 .osz",
        )

    private fun scan(extractedRoot: File, shouldCancel: () -> Boolean): List<OsuBeatmap> {
        checkCancellation(shouldCancel)
        val root = try {
            extractedRoot.canonicalFile
        } catch (error: IOException) {
            selectionFailure(
                ConversionFailureCode.NO_OSU_BEATMAP,
                "无法解析 .osz 临时目录",
                error,
            )
        }
        if (!root.isDirectory) {
            selectionFailure(
                ConversionFailureCode.NO_OSU_BEATMAP,
                "解包临时目录不存在，未生成输出",
            )
        }

        val candidates = root.walkTopDown()
            .filter { file -> file.isFile && file.extension.equals("osu", ignoreCase = true) }
            .sortedBy { file -> file.path.lowercase() }
            .toList()
        if (candidates.isEmpty()) {
            selectionFailure(
                ConversionFailureCode.NO_OSU_BEATMAP,
                "输入包中没有 .osu 难度文件，未生成输出",
            )
        }

        val supported = mutableListOf<OsuBeatmap>()
        var unsupportedKeyCount: Int? = null
        var unsupportedMode: Int? = null
        var sawMania = false
        var firstParseFailure: ConversionException? = null
        candidates.forEach { file ->
            checkCancellation(shouldCancel)
            val sourceName = file.relativeTo(root).invariantSeparatorsPath
            val canonical = try {
                file.canonicalFile
            } catch (error: IOException) {
                selectionFailure(ConversionFailureCode.URI_READ_FAILED, "$sourceName：无法解析谱面路径", error)
            }
            if (!canonical.path.startsWith(root.path + File.separator)) {
                selectionFailure(ConversionFailureCode.ZIP_PATH_TRAVERSAL, "$sourceName：谱面路径超出解包目录")
            }
            val beatmap = try {
                OsuBeatmapParser.parseUtf8(file.readBytes(), sourceName)
            } catch (error: ConversionException) {
                if (firstParseFailure == null) firstParseFailure = error
                return@forEach
            } catch (error: IOException) {
                if (firstParseFailure == null) {
                    firstParseFailure = ConversionException(
                        ConversionFailure(
                            code = ConversionFailureCode.URI_READ_FAILED,
                            message = "$sourceName：无法读取谱面文件",
                            cause = error,
                            sourceName = sourceName,
                            section = "谱面筛选",
                        )
                    )
                }
                return@forEach
            }
            if (beatmap.mode != ConversionDefaults.supportedMode) {
                unsupportedMode = beatmap.mode
                return@forEach
            }
            sawMania = true
            if (beatmap.keyCount == ConversionDefaults.supportedKeyCount) {
                supported += beatmap
            } else {
                unsupportedKeyCount = beatmap.keyCount
            }
        }

        if (supported.isNotEmpty()) return supported

        val parseFailure = firstParseFailure
        if (parseFailure != null && !sawMania && unsupportedKeyCount == null && unsupportedMode == null) {
            throw parseFailure
        }
        if (unsupportedKeyCount != null) {
            selectionFailure(
                ConversionFailureCode.UNSUPPORTED_KEY_COUNT,
                "输入包中没有可转换的 4K 难度，发现 ${unsupportedKeyCount}K 谱面，未生成输出",
            )
        }
        if (unsupportedMode != null) {
            selectionFailure(
                ConversionFailureCode.UNSUPPORTED_MODE,
                "输入包中没有 osu!mania（Mode: 3）难度，发现 Mode: $unsupportedMode，未生成输出",
            )
        }
        selectionFailure(
            ConversionFailureCode.NO_MANIA_4K,
            "输入包中没有可转换的 osu!mania 4K 难度，未生成输出",
        )
    }

    private fun checkCancellation(shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw CancellationException("谱面筛选已取消")
    }

    private fun selectionFailure(
        code: ConversionFailureCode,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw ConversionException(
        ConversionFailure(
            code = code,
            message = message,
            cause = cause,
            section = "谱面筛选",
        )
    )
}
