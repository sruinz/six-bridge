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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
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
        try {
            return coroutineScope {
                val extraction = async(extractionDispatcher) {
                    runInterruptible {
                        extract(lease.stream)
                    }
                }
                try {
                    extraction.await()
                } finally {
                    extraction.cancel()
                    withContext(NonCancellable) {
                        closeLease(lease)
                        extraction.join()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                closeLease(lease)
            }
        }
    }

    private companion object {
        val WRAPPER_PACKAGE = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}

internal class AndroidProviderStreamOpener(
    private val contentResolver: ContentResolver,
) {
    suspend fun open(uri: String): ProviderStreamLease? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val leaseReference = AtomicReference<ProviderStreamLease?>()
            val openingThread = Thread.currentThread()
            continuation.invokeOnCancellation {
                cancelSignal(cancellationSignal)
                closeLease(leaseReference.getAndSet(null))
                openingThread.interrupt()
            }
            try {
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
                val lease = stream?.let(::ProviderStreamLease)
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
                } else if (error is Error) {
                    throw error
                }
            } finally {
                Thread.interrupted()
            }
        }
    }
}

private fun cancelSignal(signal: CancellationSignal) {
    try {
        signal.cancel()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Provider icon cleanup is best-effort.
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
