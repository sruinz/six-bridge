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

interface ManagerCatalog {
    fun observe(): Flow<List<CatalogRecord>>
    suspend fun replaceVerified(record: CatalogRecord)
    suspend fun persistIconIfIdentity(
        discovered: CatalogRecord,
        iconPng: ByteArray,
    ): CatalogRecord?
    suspend fun remove(packageName: String)
    suspend fun removeAll(packageNames: Set<String>)
    suspend fun get(packageName: String): CatalogRecord?
}
