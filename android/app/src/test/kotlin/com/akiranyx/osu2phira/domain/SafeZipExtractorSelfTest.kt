package com.akiranyx.osu2phira.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Framework-free stage-2 checks for the archive boundary and cleanup contract. */
fun main() {
    inputFileNameIsValidated()
    validArchiveIsExtractedWithNormalizedSeparators()
    emptyArchiveIsRejected()
    invalidArchiveIsRejected()
    unsafePathsAreRejected()
    duplicatePathsAreRejectedCaseInsensitively()
    archiveLimitsAreEnforced()
    println("phase-2 self-test passed: SAF boundary model, safe ZIP paths, limits, and invalid archives")
}

private fun inputFileNameIsValidated() {
    OszInputValidator.validateDisplayName("song.OSZ")
    expectFailure(InputFileErrorCode.UNSUPPORTED_FILE_TYPE) {
        OszInputValidator.validateDisplayName("song.zip")
    }
}

private fun validArchiveIsExtractedWithNormalizedSeparators() {
    val root = Files.createTempDirectory("osz2phira-valid-").toFile()
    try {
        val archive = zipOf(
            "maps\\easy.osu" to "[General]\nMode: 3\n".encodeToByteArray(),
            "audio.ogg" to byteArrayOf(1, 2, 3),
            "images/" to null,
        )
        val result = SafeZipExtractor.extract(ByteArrayInputStream(archive), root)
        check(result.fileCount == 2)
        check(result.osuFileCount == 1)
        check(result.totalBytes == 21L)
        check(File(root, "maps/easy.osu").isFile)
        check(File(root, "audio.ogg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    } finally {
        deleteTree(root)
    }
}

private fun emptyArchiveIsRejected() {
    val root = Files.createTempDirectory("osz2phira-empty-").toFile()
    try {
        expectFailure(InputFileErrorCode.EMPTY_ZIP) {
            SafeZipExtractor.extract(ByteArrayInputStream(zipOf()), root)
        }
    } finally {
        deleteTree(root)
    }
}

private fun invalidArchiveIsRejected() {
    val root = Files.createTempDirectory("osz2phira-invalid-").toFile()
    try {
        expectFailure(InputFileErrorCode.INVALID_ZIP) {
            SafeZipExtractor.extract(ByteArrayInputStream(byteArrayOf(1, 2, 3)), root)
        }
    } finally {
        deleteTree(root)
    }
}

private fun unsafePathsAreRejected() {
    val root = Files.createTempDirectory("osz2phira-path-").toFile()
    try {
        expectFailure(InputFileErrorCode.ZIP_PATH_TRAVERSAL) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(zipOf("../outside.txt" to byteArrayOf(1))),
                root,
            )
        }
        expectFailure(InputFileErrorCode.ZIP_ABSOLUTE_PATH) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(zipOf("/absolute.txt" to byteArrayOf(1))),
                root,
            )
        }
        check(!File(root.parentFile, "outside.txt").exists())
    } finally {
        deleteTree(root)
    }
}

private fun duplicatePathsAreRejectedCaseInsensitively() {
    val root = Files.createTempDirectory("osz2phira-duplicate-").toFile()
    try {
        expectFailure(InputFileErrorCode.ZIP_DUPLICATE_ENTRY) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(zipOf("Map.osu" to byteArrayOf(1), "map.OSU" to byteArrayOf(2))),
                root,
            )
        }
    } finally {
        deleteTree(root)
    }
}

private fun archiveLimitsAreEnforced() {
    val root = Files.createTempDirectory("osz2phira-limits-").toFile()
    try {
        val twoFiles = zipOf("one.bin" to byteArrayOf(1), "two.bin" to byteArrayOf(2))
        expectFailure(InputFileErrorCode.ZIP_ENTRY_LIMIT_EXCEEDED) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(twoFiles),
                root,
                ZipSafetyLimits(maxEntries = 1, maxEntryBytes = 10, maxTotalBytes = 20),
            )
        }
        expectFailure(InputFileErrorCode.ZIP_ENTRY_TOO_LARGE) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(zipOf("large.bin" to byteArrayOf(1, 2, 3, 4))),
                root,
                ZipSafetyLimits(maxEntries = 4, maxEntryBytes = 3, maxTotalBytes = 20),
            )
        }
        expectFailure(InputFileErrorCode.ZIP_TOTAL_TOO_LARGE) {
            SafeZipExtractor.extract(
                ByteArrayInputStream(zipOf("a.bin" to byteArrayOf(1, 2, 3), "b.bin" to byteArrayOf(4, 5, 6))),
                root,
                ZipSafetyLimits(maxEntries = 4, maxEntryBytes = 10, maxTotalBytes = 5),
            )
        }
    } finally {
        deleteTree(root)
    }
}

private fun zipOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { archive ->
        for ((name, bytes) in entries) {
            archive.putNextEntry(ZipEntry(name))
            if (bytes != null) archive.write(bytes)
            archive.closeEntry()
        }
    }
    return output.toByteArray()
}

private inline fun expectFailure(
    expected: InputFileErrorCode,
    action: () -> Unit,
) {
    try {
        action()
        error("expected $expected")
    } catch (failure: InputFileException) {
        check(failure.code == expected) {
            "expected $expected, got ${failure.code}: ${failure.message}"
        }
    }
}

private fun deleteTree(root: File) {
    root.walkBottomUp().forEach { it.delete() }
}
