package dev.sixbridge.manager.icon

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
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

internal class ContentProviderInstalledExtensionIconSource(
    private val extract: (InputStream) -> ByteArray?,
    private val openStream: suspend (String) -> InputStream?,
) : InstalledExtensionIconSource {
    constructor(contentResolver: ContentResolver, cacheDirectory: File) : this(
        extract = CrxExtensionIconExtractor(cacheDirectory)::extract,
        openStream = { uri -> contentResolver.openProviderStream(uri) },
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

    private suspend fun extractFromProvider(uri: String): ByteArray? = coroutineScope {
        val stream = openStream(uri) ?: return@coroutineScope null
        val extraction = async(Dispatchers.IO) {
            runInterruptible {
                stream.use(extract)
            }
        }
        try {
            extraction.await()
        } finally {
            if (!extraction.isCompleted) {
                extraction.cancel()
                withContext(NonCancellable) {
                    runCatching(stream::close)
                }
            }
        }
    }

    private companion object {
        val WRAPPER_PACKAGE = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}

private suspend fun ContentResolver.openProviderStream(uri: String): InputStream? =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val streamReference = AtomicReference<InputStream?>()
            val openingThread = Thread.currentThread()
            continuation.invokeOnCancellation {
                runCatching(cancellationSignal::cancel)
                runCatching { streamReference.getAndSet(null)?.close() }
                openingThread.interrupt()
            }
            try {
                val descriptor = openAssetFileDescriptor(
                    Uri.parse(uri),
                    "r",
                    cancellationSignal,
                )
                val stream = try {
                    descriptor?.createInputStream()
                } catch (error: Throwable) {
                    runCatching { descriptor?.close() }
                    throw error
                }
                streamReference.set(stream)
                if (!continuation.isActive) {
                    runCatching { streamReference.getAndSet(null)?.close() }
                } else {
                    continuation.resume(stream) { _, value, _ ->
                        runCatching { value?.close() }
                    }
                    streamReference.compareAndSet(stream, null)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            } finally {
                Thread.interrupted()
            }
        }
    }
