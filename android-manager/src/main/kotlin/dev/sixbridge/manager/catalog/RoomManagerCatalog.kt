package dev.sixbridge.manager.catalog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomManagerCatalog(
    private val dao: CatalogEntryDao,
) : ManagerCatalog {
    override fun observe(): Flow<List<CatalogRecord>> = dao.observeAll().map { entries ->
        entries.map(CatalogEntryEntity::toRecord)
    }

    override suspend fun replaceVerified(record: CatalogRecord) {
        dao.upsert(record.toEntity())
    }

    override suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord =
        dao.ensureDiscoveredIdentity(discovered.toEntity()).toRecord()

    override suspend fun persistIconIfIdentity(
        identity: CatalogIdentity,
        iconPng: ByteArray,
    ): CatalogRecord? = dao.persistIconIfIdentity(identity, iconPng)?.toRecord()

    override suspend fun remove(packageName: String) {
        dao.delete(packageName)
    }

    override suspend fun removeAll(packageNames: Set<String>) {
        if (packageNames.isNotEmpty()) {
            dao.deleteAll(packageNames)
        }
    }

    override suspend fun get(packageName: String): CatalogRecord? = dao.get(packageName)?.toRecord()
}
