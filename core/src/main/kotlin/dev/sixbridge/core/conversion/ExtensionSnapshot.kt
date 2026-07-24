package dev.sixbridge.core.conversion

import java.nio.file.Path

class ExtensionSnapshot private constructor(
    val sourceZip: ByteArray,
    val sourceSha256: String,
    val originalRoot: Path,
    val workingRoot: Path,
    val sourceProjectIdentity: String?,
) {
    companion object {
        internal fun create(
            sourceZip: ByteArray,
            sourceSha256: String,
            originalRoot: Path,
            workingRoot: Path,
            sourceProjectIdentity: String?,
        ) = ExtensionSnapshot(
            sourceZip,
            sourceSha256,
            originalRoot,
            workingRoot,
            sourceProjectIdentity,
        )
    }
}
