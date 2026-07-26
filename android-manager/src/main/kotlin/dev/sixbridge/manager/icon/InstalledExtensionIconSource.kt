package dev.sixbridge.manager.icon

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

fun interface InstalledExtensionIconSource {
    suspend fun load(packageName: String): ByteArray?
}

internal class ProviderStreamLease(
    val stream: InputStream,
) : Closeable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            stream.close()
        }
    }
}

internal class CleanupFailureCollector {
    private val fatalFailures = mutableListOf<Throwable>()

    fun recordFailure(error: Throwable) {
        fatalFailures += error
    }

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            fatalFailures += error
        } catch (_: Exception) {
            // Provider icon cleanup is best-effort.
        } catch (error: Throwable) {
            fatalFailures += error
        }
    }

    suspend fun attemptSuspend(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            fatalFailures += error
        } catch (_: Exception) {
            // Provider icon cleanup is best-effort.
        } catch (error: Throwable) {
            fatalFailures += error
        }
    }

    fun throwWith(original: Throwable? = null) {
        val failures = buildList {
            original?.let(::add)
            addAll(fatalFailures)
        }
        val primary = failures.firstOrNull { it is Error }
            ?: failures.firstOrNull { it is CancellationException }
            ?: failures.firstOrNull()
            ?: return
        failures.forEach { failure ->
            if (failure !== primary &&
                primary.suppressed.none { suppressed -> suppressed === failure }
            ) {
                primary.addSuppressed(failure)
            }
        }
        throw primary
    }
}

internal class OpeningThreadLifetime(
    private val interruptThread: (Thread) -> Unit = Thread::interrupt,
) {
    constructor(
        openingThread: Thread,
        interruptThread: (Thread) -> Unit = Thread::interrupt,
    ) : this(interruptThread) {
        activate(openingThread)
    }

    private val lock = Any()
    private var activeThread: Thread? = null
    private var interruptRequested = false
    private var interruptDelivered = false

    fun activate(openingThread: Thread) {
        synchronized(lock) {
            activeThread = openingThread
            deliverInterruptIfNeeded()
        }
    }

    fun interruptIfActive() {
        synchronized(lock) {
            interruptRequested = true
            deliverInterruptIfNeeded()
        }
    }

    fun deactivate() {
        synchronized(lock) {
            activeThread = null
            Thread.interrupted()
        }
    }

    private fun deliverInterruptIfNeeded() {
        val thread = activeThread
        if (interruptRequested && !interruptDelivered && thread != null) {
            interruptDelivered = true
            interruptThread(thread)
        }
    }
}

internal class ContentProviderInstalledExtensionIconSource(
    private val extract: (InputStream) -> ByteArray?,
    private val openLease: suspend (String) -> ProviderStreamLease?,
    private val extractionDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InstalledExtensionIconSource {
    constructor(contentResolver: ContentResolver, cacheDirectory: File) : this(
        extract = CrxExtensionIconExtractor(cacheDirectory)::extract,
        openLease = AndroidProviderStreamOpener(contentResolver)::open,
    )

    override suspend fun load(packageName: String): ByteArray? {
        if (!WRAPPER_PACKAGE.matches(packageName)) {
            return null
        }
        val uri = "content://$packageName.contentprovider"
        return try {
            extractFromProvider(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractFromProvider(uri: String): ByteArray? {
        val lease = openLease(uri) ?: return null
        var cleanupCompleted = false
        var result: ByteArray? = null
        var failure: Throwable? = null
        val cleanup = CleanupFailureCollector()
        try {
            supervisorScope {
                val extraction = async(extractionDispatcher) {
                    runInterruptible {
                        extract(lease.stream)
                    }
                }
                try {
                    result = extraction.await()
                } catch (error: Throwable) {
                    failure = error
                }
                withContext(NonCancellable) {
                    cleanup.attempt {
                        extraction.cancel()
                    }
                    cleanup.attempt {
                        lease.close()
                    }
                    cleanup.attemptSuspend {
                        extraction.join()
                    }
                }
                cleanupCompleted = true
            }
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else if (failure !== error) {
                cleanup.recordFailure(error)
            }
        }
        if (!cleanupCompleted) {
            withContext(NonCancellable) {
                cleanup.attempt {
                    lease.close()
                }
            }
        }
        cleanup.throwWith(failure)
        return result
    }

    private companion object {
        val WRAPPER_PACKAGE = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}

internal class AndroidProviderStreamOpener(
    private val contentResolver: ContentResolver,
    private val openingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val interruptThread: (Thread) -> Unit = Thread::interrupt,
    private val leaseFactory: (InputStream) -> ProviderStreamLease = ::ProviderStreamLease,
    private val afterOpeningThreadInactive: () -> Unit = {},
) {
    suspend fun open(uri: String): ProviderStreamLease? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val leaseReference = AtomicReference<ProviderStreamLease?>()
            val openingThread = OpeningThreadLifetime(
                interruptThread = interruptThread,
            )
            continuation.invokeOnCancellation {
                val cleanup = CleanupFailureCollector()
                cleanup.attempt {
                    cancellationSignal.cancel()
                }
                cleanup.attempt {
                    leaseReference.getAndSet(null)?.close()
                }
                cleanup.attempt {
                    openingThread.interruptIfActive()
                }
                cleanup.throwWith()
            }
            try {
                openingDispatcher.dispatch(
                    continuation.context,
                    Runnable {
                        try {
                            openingThread.activate(Thread.currentThread())
                            val descriptor = contentResolver.openAssetFileDescriptor(
                                Uri.parse(uri),
                                "r",
                                cancellationSignal,
                            )
                            val stream = try {
                                descriptor?.createInputStream()
                            } catch (error: Throwable) {
                                closeResource(descriptor)
                                throw error
                            }
                            val lease = stream?.let(leaseFactory)
                            leaseReference.set(lease)
                            if (!continuation.isActive) {
                                closeLease(leaseReference.getAndSet(null))
                            } else {
                                continuation.resume(lease) { _, value, _ ->
                                    closeLease(value)
                                }
                                leaseReference.compareAndSet(lease, null)
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.failure(error))
                            } else if (
                                error is CancellationException ||
                                error is Error
                            ) {
                                throw error
                            }
                        } finally {
                            openingThread.deactivate()
                            afterOpeningThreadInactive()
                        }
                    }
                )
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                } else if (error is CancellationException || error is Error) {
                    throw error
                }
            }
        }
}

private fun closeResource(resource: Closeable?) {
    try {
        resource?.close()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Provider icon cleanup is best-effort.
    }
}

private fun closeLease(lease: ProviderStreamLease?) {
    try {
        lease?.close()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Provider icon cleanup is best-effort.
    }
}
