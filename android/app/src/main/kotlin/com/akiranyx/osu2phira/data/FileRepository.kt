package com.akiranyx.osu2phira.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.akiranyx.osu2phira.domain.InputFileErrorCode
import com.akiranyx.osu2phira.domain.InputFileException
import com.akiranyx.osu2phira.domain.OszInputValidator
import com.akiranyx.osu2phira.domain.ConversionException
import com.akiranyx.osu2phira.domain.ConversionFailure
import com.akiranyx.osu2phira.domain.ConversionFailureCode
import com.akiranyx.osu2phira.domain.ConversionOutcome
import com.akiranyx.osu2phira.domain.SafeArchiveResult
import com.akiranyx.osu2phira.domain.SafeZipExtractor
import com.akiranyx.osu2phira.domain.BeatmapCandidate
import com.akiranyx.osu2phira.domain.FourKeyBeatmapSelector
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.UUID

data class InputFileInspection(
    val uri: Uri,
    val displayName: String,
    val sourceSizeBytes: Long?,
    val persistedReadPermission: Boolean,
    val taskDirectory: File,
    val archive: SafeArchiveResult,
    val beatmapCandidates: List<BeatmapCandidate>,
)

/**
 * Android boundary for user-selected .osz files.
 * It never turns a content Uri into a guessed filesystem path.
 */
class FileRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val tasksRoot = File(appContext.cacheDir, TASKS_DIRECTORY)
    private var activeTaskDirectory: File? = null

    init {
        cleanupStaleTaskDirectories()
    }

    @Synchronized
    fun inspect(uri: Uri): InputFileInspection {
        val metadata = queryMetadata(uri)
        val displayName = metadata.displayName
        OszInputValidator.validateDisplayName(displayName)

        clearActiveTaskDirectory()
        clearSavedUri()
        val taskDirectory = createTaskDirectory()
        val persistedReadPermission = tryPersistReadPermission(uri)
        try {
            val archive = resolver.openInputStream(uri)?.use { input ->
                SafeZipExtractor.extract(input, taskDirectory)
            } ?: throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "无法读取所选文件",
            )

            val beatmapCandidates = FourKeyBeatmapSelector.discover(taskDirectory)
            if (persistedReadPermission) saveUri(uri) else clearSavedUri()
            activeTaskDirectory = taskDirectory
            return InputFileInspection(
                uri = uri,
                displayName = displayName,
                sourceSizeBytes = metadata.sizeBytes,
                persistedReadPermission = persistedReadPermission,
                taskDirectory = taskDirectory,
                archive = archive,
                beatmapCandidates = beatmapCandidates,
            )
        } catch (error: CancellationException) {
            deleteTaskDirectory(taskDirectory)
            throw error
        } catch (error: InputFileException) {
            deleteTaskDirectory(taskDirectory)
            throw error
        } catch (error: ConversionException) {
            deleteTaskDirectory(taskDirectory)
            throw error
        } catch (error: FileNotFoundException) {
            deleteTaskDirectory(taskDirectory)
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "无法打开所选文件，请重新选择 .osz",
                error,
            )
        } catch (error: SecurityException) {
            deleteTaskDirectory(taskDirectory)
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "没有读取所选文件的权限，请重新选择 .osz",
                error,
            )
        } catch (error: IOException) {
            deleteTaskDirectory(taskDirectory)
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "读取所选文件失败，请重新选择 .osz",
                error,
            )
        } catch (error: RuntimeException) {
            deleteTaskDirectory(taskDirectory)
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "读取所选文件失败，请重新选择 .osz",
                error,
            )
        }
    }

    fun inspectOutcome(uri: Uri): ConversionOutcome<InputFileInspection> = try {
        ConversionOutcome.Success(inspect(uri))
    } catch (error: CancellationException) {
        ConversionOutcome.Failure(
            ConversionFailure(
                code = ConversionFailureCode.CANCELLED,
                message = "读取已取消",
                cause = error,
                section = "输入",
            )
        )
    } catch (error: ConversionException) {
        ConversionOutcome.Failure(error.failure)
    } catch (error: Throwable) {
        ConversionOutcome.Failure(
            ConversionFailure(
                code = ConversionFailureCode.URI_READ_FAILED,
                message = "读取所选文件失败，请重新选择 .osz",
                cause = error,
                section = "输入",
            )
        )
    }

    fun savedUri(): Uri? {
        val value = preferences.getString(KEY_SAVED_URI, null) ?: return null
        return try {
            Uri.parse(value)
        } catch (_: IllegalArgumentException) {
            clearSavedUri()
            null
        }
    }

    fun clearSavedUri() {
        preferences.edit().remove(KEY_SAVED_URI).apply()
    }

    private fun queryMetadata(uri: Uri): UriMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex).takeIf { it >= 0L }?.let { sizeBytes = it }
                    }
                }
            }
        } catch (error: SecurityException) {
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "无法读取所选文件信息",
                error,
            )
        } catch (error: RuntimeException) {
            throw InputFileException(
                InputFileErrorCode.URI_READ_FAILED,
                "无法读取所选文件信息",
                error,
            )
        }

        if (displayName.isNullOrBlank()) {
            displayName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.let(Uri::decode)
                ?.takeIf(String::isNotBlank)
        }
        return UriMetadata(
            displayName = displayName ?: "",
            sizeBytes = sizeBytes,
        )
    }

    private fun tryPersistReadPermission(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    } catch (_: SecurityException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    private fun saveUri(uri: Uri) {
        preferences.edit().putString(KEY_SAVED_URI, uri.toString()).apply()
    }

    private fun createTaskDirectory(): File {
        if ((!tasksRoot.exists() && !tasksRoot.mkdirs()) || !tasksRoot.isDirectory) {
            throw InputFileException(
                InputFileErrorCode.TEMP_DIRECTORY_FAILED,
                "无法创建应用临时目录",
            )
        }
        val taskDirectory = File(tasksRoot, "task-${UUID.randomUUID()}")
        if (!taskDirectory.mkdirs() || !taskDirectory.isDirectory) {
            throw InputFileException(
                InputFileErrorCode.TEMP_DIRECTORY_FAILED,
                "无法创建本次读取的临时目录",
            )
        }
        return taskDirectory
    }

    private fun cleanupStaleTaskDirectories() {
        if (!tasksRoot.exists()) return
        tasksRoot.listFiles()?.forEach(::deleteTaskDirectory)
    }

    private fun clearActiveTaskDirectory() {
        activeTaskDirectory?.let(::deleteTaskDirectory)
        activeTaskDirectory = null
    }

    private fun deleteTaskDirectory(directory: File) {
        val root = try {
            tasksRoot.canonicalFile
        } catch (_: IOException) {
            return
        }
        val candidate = try {
            directory.canonicalFile
        } catch (_: IOException) {
            return
        }
        if (candidate == root || !candidate.path.startsWith(root.path + File.separator)) return
        candidate.walkBottomUp().forEach { file ->
            file.delete()
        }
    }

    private data class UriMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    private companion object {
        const val PREFERENCES_NAME = "osz2phira_input"
        const val KEY_SAVED_URI = "saved_uri"
        const val TASKS_DIRECTORY = "osz2phira/tasks"
    }
}
