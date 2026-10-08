package com.akiranyx.osu2phira.domain

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class InputFileException(
    code: InputFileErrorCode,
    message: String,
    cause: Throwable? = null,
    sourceName: String? = null,
    section: String = "输入",
    lineNumber: Int? = null,
) : ConversionException(
    ConversionFailure(
        code = code,
        message = message,
        cause = cause,
        sourceName = sourceName,
        section = section,
        lineNumber = lineNumber,
    )
)

data class ZipSafetyLimits(
    val maxEntries: Int = 4_096,
    val maxEntryBytes: Long = 256L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
    val maxPathCharacters: Int = 4_096,
)

data class ZipEntrySummary(
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
)

data class SafeArchiveResult(
    val entries: List<ZipEntrySummary>,
    val fileCount: Int,
    val osuFileCount: Int,
    val totalBytes: Long,
)

/**
 * Extracts an .osz stream into a task directory without trusting archive paths.
 * The caller owns cleanup of [destination] when this function fails.
 */
object SafeZipExtractor {
    private const val COPY_BUFFER_BYTES = 32 * 1024

    fun extract(
        input: InputStream,
        destination: File,
        limits: ZipSafetyLimits = ZipSafetyLimits(),
    ): SafeArchiveResult {
        require(limits.maxEntries > 0) { "ZIP 条目上限必须大于 0" }
        require(limits.maxEntryBytes > 0L) { "ZIP 单文件大小上限必须大于 0" }
        require(limits.maxTotalBytes > 0L) { "ZIP 总展开大小上限必须大于 0" }
        require(limits.maxPathCharacters > 0) { "ZIP 路径长度上限必须大于 0" }

        val root = try {
            destination.canonicalFile
        } catch (error: IOException) {
            throw InputFileException(
                InputFileErrorCode.TEMP_DIRECTORY_FAILED,
                "无法准备应用临时目录",
                error,
            )
        }
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) {
            throw InputFileException(
                InputFileErrorCode.TEMP_DIRECTORY_FAILED,
                "无法准备应用临时目录",
            )
        }

        val seenPaths = HashSet<String>()
        val summaries = ArrayList<ZipEntrySummary>()
        var totalBytes = 0L
        var fileCount = 0
        var osuFileCount = 0

        val bufferedInput = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val signature = readSignature(bufferedInput)
        if (!isZipSignature(signature)) {
            throw invalidZip(IllegalArgumentException("ZIP 文件头无效"))
        }

        try {
            ZipInputStream(bufferedInput).use { archive ->
                while (true) {
                    val entry = try {
                        archive.nextEntry
                    } catch (error: ZipException) {
                        throw invalidZip(error)
                    }
                    if (entry == null) break

                    if (summaries.size >= limits.maxEntries) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_ENTRY_LIMIT_EXCEEDED,
                            "ZIP 条目数量超过上限（${limits.maxEntries}）",
                        )
                    }

                    val normalizedPath = normalizePath(entry.name, limits)
                    val key = normalizedPath.lowercase(Locale.ROOT)
                    if (!seenPaths.add(key)) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_DUPLICATE_ENTRY,
                            "ZIP 中存在重复或大小写冲突的条目：${entry.name}",
                        )
                    }

                    val target = try {
                        File(root, normalizedPath).canonicalFile
                    } catch (error: IOException) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_INVALID_ENTRY,
                            "ZIP 条目路径无法解析：${entry.name}",
                            error,
                        )
                    }
                    ensureWithin(root, target, entry.name)

                    val directory = entry.isDirectory ||
                        entry.name.endsWith("/") ||
                        entry.name.endsWith("\\")
                    if (directory) {
                        if (target.exists() && !target.isDirectory) {
                            throw InputFileException(
                                InputFileErrorCode.ZIP_DUPLICATE_ENTRY,
                                "ZIP 条目文件与目录冲突：${entry.name}",
                            )
                        }
                        if (!target.exists() && !target.mkdirs()) {
                            throw InputFileException(
                                InputFileErrorCode.ZIP_ENTRY_READ_FAILED,
                                "无法创建 ZIP 目录：${entry.name}",
                            )
                        }
                        summaries += ZipEntrySummary(normalizedPath, directory = true, sizeBytes = 0L)
                        archive.closeEntry()
                        continue
                    }

                    val declaredSize = entry.size
                    if (declaredSize > limits.maxEntryBytes) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_ENTRY_TOO_LARGE,
                            "ZIP 条目超过单文件大小上限：${entry.name}",
                        )
                    }

                    val parent = target.parentFile
                    if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_ENTRY_READ_FAILED,
                            "无法创建 ZIP 条目目录：${entry.name}",
                        )
                    }
                    if (target.exists() && target.isDirectory) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_DUPLICATE_ENTRY,
                            "ZIP 条目文件与目录冲突：${entry.name}",
                        )
                    }

                    var entryBytes = 0L
                    try {
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val count = archive.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue

                                if (entryBytes > limits.maxEntryBytes - count.toLong()) {
                                    throw InputFileException(
                                        InputFileErrorCode.ZIP_ENTRY_TOO_LARGE,
                                        "ZIP 条目超过单文件大小上限：${entry.name}",
                                    )
                                }
                                if (totalBytes > limits.maxTotalBytes - count.toLong()) {
                                    throw InputFileException(
                                        InputFileErrorCode.ZIP_TOTAL_TOO_LARGE,
                                        "ZIP 展开总大小超过上限（${limits.maxTotalBytes} 字节）",
                                    )
                                }

                                output.write(buffer, 0, count)
                                entryBytes += count
                                totalBytes += count
                            }
                        }
                        archive.closeEntry()
                    } catch (error: InputFileException) {
                        throw error
                    } catch (error: ZipException) {
                        throw invalidZip(error)
                    } catch (error: IOException) {
                        throw InputFileException(
                            InputFileErrorCode.ZIP_ENTRY_READ_FAILED,
                            "无法读取 ZIP 条目：${entry.name}",
                            error,
                        )
                    }

                    fileCount += 1
                    if (normalizedPath.endsWith(".osu", ignoreCase = true)) osuFileCount += 1
                    summaries += ZipEntrySummary(normalizedPath, directory = false, sizeBytes = entryBytes)
                }
            }
        } catch (error: InputFileException) {
            throw error
        } catch (error: ZipException) {
            throw invalidZip(error)
        } catch (error: IOException) {
            throw InputFileException(
                InputFileErrorCode.INVALID_ZIP,
                "无法读取 ZIP 文件",
                error,
            )
        }

        if (summaries.isEmpty() || fileCount == 0) {
            throw InputFileException(
                InputFileErrorCode.EMPTY_ZIP,
                "ZIP 中没有可读取的文件",
            )
        }

        return SafeArchiveResult(
            entries = summaries,
            fileCount = fileCount,
            osuFileCount = osuFileCount,
            totalBytes = totalBytes,
        )
    }

    private fun readSignature(input: BufferedInputStream): ByteArray {
        input.mark(4)
        val signature = ByteArray(4)
        val count = input.read(signature)
        input.reset()
        return if (count == 4) signature else ByteArray(0)
    }

    private fun isZipSignature(signature: ByteArray): Boolean {
        if (signature.size < 4 || signature[0] != 'P'.code.toByte() || signature[1] != 'K'.code.toByte()) {
            return false
        }
        return signature[2].toInt() and 0xff in setOf(0x03, 0x05, 0x06, 0x07)
    }

    private fun normalizePath(name: String, limits: ZipSafetyLimits): String {
        if (name.isEmpty() || name.length > limits.maxPathCharacters || name.contains('\u0000')) {
            throw InputFileException(
                InputFileErrorCode.ZIP_INVALID_ENTRY,
                "ZIP 条目名称无效或过长",
            )
        }

        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.matches(Regex("^[A-Za-z]:.*"))) {
            throw InputFileException(
                InputFileErrorCode.ZIP_ABSOLUTE_PATH,
                "ZIP 条目不能使用绝对路径：$name",
            )
        }

        val components = normalized.split('/')
        if (components.any { it == ".." }) {
            throw InputFileException(
                InputFileErrorCode.ZIP_PATH_TRAVERSAL,
                "ZIP 条目包含路径穿越：$name",
            )
        }

        val safeComponents = components.filter { it.isNotEmpty() && it != "." }
        if (safeComponents.isEmpty()) {
            throw InputFileException(
                InputFileErrorCode.ZIP_INVALID_ENTRY,
                "ZIP 条目名称无效：$name",
            )
        }
        return safeComponents.joinToString("/")
    }

    private fun ensureWithin(root: File, candidate: File, originalName: String) {
        val rootPath = root.path
        val candidatePath = candidate.path
        if (candidatePath != rootPath && !candidatePath.startsWith(rootPath + File.separator)) {
            throw InputFileException(
                InputFileErrorCode.ZIP_PATH_TRAVERSAL,
                "ZIP 条目超出临时目录：$originalName",
            )
        }
    }

    private fun invalidZip(cause: Throwable): InputFileException = InputFileException(
        InputFileErrorCode.INVALID_ZIP,
        "无法读取合法的 ZIP 文件",
        cause,
    )
}
