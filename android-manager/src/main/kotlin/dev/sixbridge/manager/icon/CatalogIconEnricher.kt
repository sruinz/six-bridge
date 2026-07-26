package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class IconProcessEntry(
    val bytes: ByteArray?,
    val persistence: IconPersistenceState,
)

internal enum class IconPersistenceState {
    NOT_ATTEMPTED,
    STORED,
    FAILED_FOR_PROCESS,
    STALE_FOR_PROCESS,
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
    private val inFlight = mutableMapOf<CatalogIdentity, CompletableDeferred<ByteArray?>>()

    suspend fun load(record: CatalogRecord): ByteArray? {
        record.iconPng?.let { return it }
        val identity = record.identity()
        val request = resultsMutex.withLock {
            results[identity]?.let { entry ->
                return@withLock LoadRequest.Cached(entry.bytes)
            }
            inFlight[identity]?.let { deferred ->
                return@withLock LoadRequest.Waiting(deferred)
            }
            CompletableDeferred<ByteArray?>().also { deferred ->
                inFlight[identity] = deferred
            }.let(LoadRequest::Owner)
        }

        return when (request) {
            is LoadRequest.Cached -> request.bytes
            is LoadRequest.Waiting -> request.deferred.await()
            is LoadRequest.Owner -> loadOwner(identity, record.packageName, request.deferred)
        }
    }

    private suspend fun loadOwner(
        identity: CatalogIdentity,
        packageName: String,
        ownDeferred: CompletableDeferred<ByteArray?>,
    ): ByteArray? = try {
        val loaded = source.load(packageName)
        resultsMutex.withLock {
            if (inFlight[identity] === ownDeferred) {
                results.putIfAbsent(
                    identity,
                    IconProcessEntry(
                        bytes = loaded,
                        persistence = IconPersistenceState.NOT_ATTEMPTED,
                    ),
                )
                inFlight.remove(identity)
            }
            ownDeferred.complete(loaded)
        }
        loaded
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            resultsMutex.withLock {
                if (inFlight[identity] === ownDeferred) {
                    inFlight.remove(identity)
                }
                ownDeferred.completeExceptionally(error)
            }
        }
        throw error
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

            IconPersistenceState.STALE_FOR_PROCESS -> IconPersistenceResult.IdentityMismatch

            IconPersistenceState.NOT_ATTEMPTED -> try {
                catalog.persistIconIfIdentity(identity, iconPng)
                    ?.also {
                        results[identity] = entry.copy(
                            persistence = IconPersistenceState.STORED,
                        )
                    }
                    ?.let(IconPersistenceResult::Stored)
                    ?: run {
                        results[identity] = entry.copy(
                            persistence = IconPersistenceState.STALE_FOR_PROCESS,
                        )
                        IconPersistenceResult.IdentityMismatch
                    }
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
        results[record.identity()]
            ?.takeIf { it.persistence != IconPersistenceState.STALE_FOR_PROCESS }
            ?.bytes
            ?.let { bytes ->
                record.copy(iconPng = bytes)
            } ?: record
    }

    suspend fun retainOnly(identities: Set<CatalogIdentity>) = resultsMutex.withLock {
        results.keys.retainAll(identities)
        inFlight.keys.retainAll(identities)
    }

    private sealed interface LoadRequest {
        data class Cached(val bytes: ByteArray?) : LoadRequest
        data class Waiting(val deferred: CompletableDeferred<ByteArray?>) : LoadRequest
        data class Owner(val deferred: CompletableDeferred<ByteArray?>) : LoadRequest
    }
}
