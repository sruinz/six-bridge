package dev.sixbridge.core.conversion

enum class LogicalIdentityEvidence {
    VERIFIED_STORE_EXTENSION_ID,
    SIGNED_DRAFT_METADATA,
    PROJECT_MARKER,
    MANIFEST_KEY,
}

sealed interface LogicalSourceIdentityResult {
    data class Stable(
        val value: String,
        val evidence: LogicalIdentityEvidence,
    ) : LogicalSourceIdentityResult

    data object NeedsUserSelection : LogicalSourceIdentityResult
}
