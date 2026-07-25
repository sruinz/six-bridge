package dev.sixbridge.manager.wrapper

data class InstalledWrapper(
    val packageName: String,
    val extensionId: String,
    val name: String,
    val versionCode: Long,
    val signerSha256: String,
)

interface InstalledWrapperRepository {
    suspend fun findAll(): List<InstalledWrapper>
    suspend fun find(packageName: String): InstalledWrapper?
}
