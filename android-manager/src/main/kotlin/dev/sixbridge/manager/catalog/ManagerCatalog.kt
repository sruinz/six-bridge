package dev.sixbridge.manager.catalog

import kotlinx.coroutines.flow.Flow

data class CatalogRecord(
    val packageName: String,
    val extensionId: String,
    val name: String,
    val iconPng: ByteArray?,
    val installedVersionCode: Long,
    val signerSha256: String,
    val compatibilityStatus: String,
    val reportJson: String?,
    val lastVerifiedAtEpochMillis: Long,
)

data class CatalogIdentity(
    val packageName: String,
    val extensionId: String,
    val installedVersionCode: Long,
    val signerSha256: String,
)

fun CatalogRecord.identity() = CatalogIdentity(
    packageName = packageName,
    extensionId = extensionId,
    installedVersionCode = installedVersionCode,
    signerSha256 = signerSha256,
)

interface ManagerCatalog {
    fun observe(): Flow<List<CatalogRecord>>
    suspend fun replaceVerified(record: CatalogRecord)
    suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord
    suspend fun persistIconIfIdentity(
        identity: CatalogIdentity,
        iconPng: ByteArray,
    ): CatalogRecord?
    suspend fun remove(packageName: String)
    suspend fun removeAll(packageNames: Set<String>)
    suspend fun get(packageName: String): CatalogRecord?
}
