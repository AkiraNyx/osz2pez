package com.akiranyx.osu2phira.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.akiranyx.osu2phira.domain.ConversionException
import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionOutcome
import com.akiranyx.osu2phira.domain.ConversionSettings
import com.akiranyx.osu2phira.domain.OsuBeatmap
import com.akiranyx.osu2phira.domain.FourKeyBeatmapSelector
import com.akiranyx.osu2phira.domain.PezBuildRequest
import com.akiranyx.osu2phira.domain.PezOutputBuilder
import com.akiranyx.osu2phira.domain.PezPackageArtifact
import com.akiranyx.osu2phira.domain.PezPackageWriter
import com.akiranyx.osu2phira.domain.PezValidationReport
import com.akiranyx.osu2phira.domain.captureConversionOutcome
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.UUID

data class PezExportResult(
    val artifact: PezPackageArtifact,
    val validation: PezValidationReport,
    val savedFileName: String,
)

/** Android boundary for selecting a 4K difficulty and writing a staged package to SAF. */
class PezExportService(
    private val resolver: ContentResolver,
    private val cacheDirectory: File,
) {
    fun exportToUri(
        inspection: InputFileInspection,
        outputUri: Uri,
        selectedBeatmapPath: String,
        settings: ConversionSettings = ConversionSettings.defaults(),
        shouldCancel: () -> Boolean = { false },
    ): PezExportResult {
        checkCancellation(shouldCancel)
        if (inspection.beatmapCandidates.none { it.sourceName == selectedBeatmapPath }) {
            exportFailure(
                ConversionFailureCode.BEATMAP_SELECTION_REQUIRED,
                "谱面选择不属于当前 .osz，请重新选择后导出",
                section = "谱面筛选",
            )
        }
        val targetFileName = rejectExistingTarget(outputUri)
        val beatmap = FourKeyBeatmapSelector.select(
            inspection.taskDirectory,
            selectedBeatmapPath,
            shouldCancel,
        )
        checkCancellation(shouldCancel)
        val artifact = PezOutputBuilder.build(
            PezBuildRequest(
                beatmap = beatmap,
                extractedRoot = inspection.taskDirectory,
                settings = settings,
            )
        )
        checkCancellation(shouldCancel)
        val outputRoot = File(cacheDirectory, "osz2phira/outputs")
        if ((!outputRoot.exists() && !outputRoot.mkdirs()) || !outputRoot.isDirectory) {
            exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法创建 .pez 临时输出目录",
            )
        }
        val taskDirectory = File(outputRoot, "export-${UUID.randomUUID()}")
        if (!taskDirectory.mkdirs() || !taskDirectory.isDirectory) {
            exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法创建本次 .pez 临时目录",
            )
        }
        val staging = File(taskDirectory, artifact.packageName)
        try {
            val validation = PezPackageWriter.writeToStagingFile(artifact, staging)
            checkCancellation(shouldCancel)
            writeStagingToUri(staging, outputUri, shouldCancel)
            return PezExportResult(
                artifact = artifact,
                validation = validation,
                savedFileName = targetFileName ?: artifact.packageName,
            )
        } finally {
            taskDirectory.walkBottomUp().forEach { file -> file.delete() }
        }
    }

    fun exportToUriOutcome(
        inspection: InputFileInspection,
        outputUri: Uri,
        selectedBeatmapPath: String,
        settings: ConversionSettings = ConversionSettings.defaults(),
        shouldCancel: () -> Boolean = { false },
    ): ConversionOutcome<PezExportResult> = captureConversionOutcome("导出 .pez") {
        exportToUri(inspection, outputUri, selectedBeatmapPath, settings, shouldCancel)
    }

    fun findFourKeyBeatmap(
        extractedRoot: File,
        shouldCancel: () -> Boolean = { false },
    ): OsuBeatmap = FourKeyBeatmapSelector.select(extractedRoot, shouldCancel)

    private fun rejectExistingTarget(uri: Uri): String? {
        val metadata = try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor: Cursor ->
                if (!cursor.moveToFirst()) null
                else {
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.takeUnless(cursor::isNull)
                        ?.let(cursor::getLong)
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.takeUnless(cursor::isNull)
                        ?.let(cursor::getString)
                    name to size
                }
            }
        } catch (error: SecurityException) {
            exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法读取输出目标信息",
                error,
            )
        } catch (error: RuntimeException) {
            exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "无法读取输出目标信息",
                error,
            )
        }
        if (metadata?.second?.let { it > 0L } == true) {
            exportFailure(
                ConversionFailureCode.OUTPUT_EXISTS,
                "输出目标已有内容，不覆盖：$uri",
            )
        }
        return metadata?.first?.takeIf(String::isNotBlank)
    }

    private fun writeStagingToUri(
        staging: File,
        outputUri: Uri,
        shouldCancel: () -> Boolean,
    ) {
        try {
            checkCancellation(shouldCancel)
            resolver.openOutputStream(outputUri)?.use { rawOutput ->
                BufferedOutputStream(rawOutput).use { output ->
                    BufferedInputStream(FileInputStream(staging)).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                }
            } ?: exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "系统无法打开输出目标",
            )
            checkCancellation(shouldCancel)
        } catch (error: ConversionException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            exportFailure(
                ConversionFailureCode.OUTPUT_NOT_WRITABLE,
                "没有写入输出目标的权限",
                error,
            )
        } catch (error: IOException) {
            exportFailure(
                ConversionFailureCode.OUTPUT_WRITE_FAILED,
                "写入 .pez 输出目标失败",
                error,
            )
        } catch (error: RuntimeException) {
            exportFailure(
                ConversionFailureCode.OUTPUT_WRITE_FAILED,
                "写入 .pez 输出目标失败",
                error,
            )
        }
    }

    private fun checkCancellation(shouldCancel: () -> Boolean) {
        if (shouldCancel()) throw CancellationException("导出已取消")
    }
}

private fun exportFailure(
    code: ConversionFailureCode,
    message: String,
    cause: Throwable? = null,
    section: String = "输出",
): Nothing = throw ConversionException(
    ConversionFailure(
        code = code,
        message = message,
        cause = cause,
        section = section,
    )
)
