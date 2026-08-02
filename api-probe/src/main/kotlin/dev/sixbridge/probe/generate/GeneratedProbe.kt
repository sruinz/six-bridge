package dev.sixbridge.probe.generate

import java.nio.file.Path

data class GeneratedProbe(
    val groupId: String,
    val sourceRoot: Path,
    val manifestSha256: String,
    val catalogSha256: String,
)
