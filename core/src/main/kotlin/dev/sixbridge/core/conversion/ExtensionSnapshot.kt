package dev.sixbridge.core.conversion

import java.nio.file.Path

data class ExtensionSnapshot(
    val sourceZip: ByteArray,
    val sourceSha256: String,
    val originalRoot: Path,
    val workingRoot: Path,
)
