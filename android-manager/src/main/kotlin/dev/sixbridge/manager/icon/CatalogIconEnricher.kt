package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class IconIdentity(
    val packageName: String,
    val extensionId: String,
    val versionCode: Long,
    val signerSha256: String,
)

internal sealed interface IconPersistenceResult {
    data class Stored(val record: CatalogRecord) : IconPersistenceResult
    data object IdentityMismatch : IconPersistenceResult
    data object WriteFailed : IconPersistenceResult
}

internal class CatalogIconEnricher(
    private val catalog: ManagerCatalog,
    private val source: InstalledExtensionIconSource,
) {
    private val resultsMutex = Mutex()
    private val results = mutableMapOf<IconIdentity, ByteArray?>()

    suspend fun load(record: CatalogRecord): ByteArray? {
        record.iconPng?.let { return it }
        val identity = record.iconIdentity()
        val cached = resultsMutex.withLock {
            if (results.containsKey(identity)) {
                CachedIcon(results.getValue(identity))
            } else {
                null
            }
        }
        if (cached != null) return cached.bytes

        val loaded = source.load(record.packageName)
        return resultsMutex.withLock {
            if (results.containsKey(identity)) {
                results.getValue(identity)
            } else {
                results[identity] = loaded
                loaded
            }
        }
    }

    suspend fun persist(
        current: CatalogRecord,
        iconPng: ByteArray,
    ): IconPersistenceResult = try {
        catalog.persistIconIfIdentity(current, iconPng)
            ?.let(IconPersistenceResult::Stored)
            ?: IconPersistenceResult.IdentityMismatch
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        IconPersistenceResult.WriteFailed
    }

    private data class CachedIcon(val bytes: ByteArray?)
}

internal fun CatalogRecord.iconIdentity() = IconIdentity(
    packageName = packageName,
    extensionId = extensionId,
    versionCode = installedVersionCode,
    signerSha256 = signerSha256,
)
