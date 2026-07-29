package dev.sixbridge.manager.wrapper

data class InstalledWrapper(
    val packageName: String,
    val extensionId: String,
    val name: String,
    val versionCode: Long,
    val signerSha256: String,
    val apkSha256: String? = null,
    val installSourceEvidence: InstallSourceEvidence = InstallSourceEvidence.Unavailable,
)

sealed interface InstallSourceEvidence {
    data object Unavailable : InstallSourceEvidence

    data class Known(
        val installingPackageName: String?,
        val originatingPackageName: String?,
    ) : InstallSourceEvidence
}

interface InstalledWrapperRepository {
    suspend fun findAll(): List<InstalledWrapper>
    suspend fun find(packageName: String): InstalledWrapper?
}
