package dev.sixbridge.manager.wrapper

import dev.sixbridge.manager.importer.ImportFailure
import java.io.File

fun interface ArchiveInspector {
    fun inspect(apk: File): ArchiveFacts
}

data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    val metadata: Map<String, String>,
    val activities: List<ArchiveActivity>,
    val providers: List<ArchiveProvider>,
    val crxBytes: ByteArray,
)

data class ArchiveActivity(
    val name: String,
    val exported: Boolean,
    val intentFilters: List<ArchiveIntentFilter>,
)

data class ArchiveIntentFilter(
    val actions: Set<String>,
    val categories: Set<String>,
    val schemes: Set<String>,
    val hosts: Set<String>,
)

data class ArchiveProvider(
    val name: String,
    val authority: String,
    val exported: Boolean,
)

data class BinaryManifestFacts(
    val metadata: Map<String, String>,
    val activities: List<ArchiveActivity>,
    val providers: List<ArchiveProvider>,
)

class ArchiveInspectionException(
    val failure: ImportFailure,
) : IllegalArgumentException()
