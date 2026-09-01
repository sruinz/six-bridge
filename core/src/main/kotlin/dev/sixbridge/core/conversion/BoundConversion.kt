package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport

class BoundConversion internal constructor(
    sourceZip: ByteArray,
    val sourceSha256: String,
    val extensionId: String,
    compatibility: CompatibilityReport,
    forcedCompatibility: ForcedCompatibilityProvenance?,
    passthroughCrx: ByteArray?,
) {
    private val stableSourceZip = sourceZip.copyOf()
    private val stablePassthroughCrx = passthroughCrx?.copyOf()

    val sourceZip: ByteArray
        get() = stableSourceZip.copyOf()

    val compatibility: CompatibilityReport =
        compatibility.deepImmutableCopy()
    val forcedCompatibility: ForcedCompatibilityProvenance? =
        forcedCompatibility.deepImmutableCopy()

    val passthroughCrx: ByteArray?
        get() = stablePassthroughCrx?.copyOf()
}
