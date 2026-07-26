package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class IconProcessEntry(
    val bytes: ByteArray?,
    val persistence: IconPersistenceState,
)

internal enum class IconPersistenceState {
    NOT_ATTEMPTED,
    STORED,
    FAILED_FOR_PROCESS,
}

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
    private val results = mutableMapOf<CatalogIdentity, IconProcessEntry>()

    suspend fun load(record: CatalogRecord): ByteArray? {
        record.iconPng?.let { return it }
        val identity = record.identity()
        val cached = resultsMutex.withLock {
            if (results.containsKey(identity)) {
                CachedIcon(results.getValue(identity).bytes)
            } else {
                null
            }
        }
        if (cached != null) return cached.bytes

        val loaded = source.load(record.packageName)
        return resultsMutex.withLock {
            if (results.containsKey(identity)) {
                results.getValue(identity).bytes
            } else {
                results[identity] = IconProcessEntry(
                    bytes = loaded,
                    persistence = IconPersistenceState.NOT_ATTEMPTED,
                )
                loaded
            }
        }
    }

    suspend fun persist(
        current: CatalogRecord,
        iconPng: ByteArray,
    ): IconPersistenceResult = resultsMutex.withLock {
        val identity = current.identity()
        val entry = results[identity] ?: IconProcessEntry(
            bytes = iconPng,
            persistence = IconPersistenceState.NOT_ATTEMPTED,
        )

        when (entry.persistence) {
            IconPersistenceState.STORED -> IconPersistenceResult.Stored(
                current.copy(iconPng = iconPng),
            )

            IconPersistenceState.FAILED_FOR_PROCESS -> IconPersistenceResult.WriteFailed

            IconPersistenceState.NOT_ATTEMPTED -> try {
                catalog.persistIconIfIdentity(identity, iconPng)
                    ?.also {
                        results[identity] = entry.copy(
                            persistence = IconPersistenceState.STORED,
                        )
                    }
                    ?.let(IconPersistenceResult::Stored)
                    ?: IconPersistenceResult.IdentityMismatch
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                results[identity] = entry.copy(
                    persistence = IconPersistenceState.FAILED_FOR_PROCESS,
                )
                IconPersistenceResult.WriteFailed
            }
        }
    }

    suspend fun mergeCached(record: CatalogRecord): CatalogRecord = resultsMutex.withLock {
        results[record.identity()]?.bytes?.let { bytes ->
            record.copy(iconPng = bytes)
        } ?: record
    }

    suspend fun retainOnly(identities: Set<CatalogIdentity>) = resultsMutex.withLock {
        results.keys.retainAll(identities)
    }

    private data class CachedIcon(val bytes: ByteArray?)
}
