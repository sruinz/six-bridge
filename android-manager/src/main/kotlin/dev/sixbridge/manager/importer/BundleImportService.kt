package dev.sixbridge.manager.importer

import android.net.Uri
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class BundleImportService(
    private val source: BundleSource,
    private val verifier: AndroidWrapperVerifier,
    private val cacheDirectory: File,
    internal val maximumSourceBytes: Long = SixBundleContract.MAX_ARCHIVE_SIZE,
    internal val ioDispatcher: CoroutineDispatcher? = null,
    internal val afterPublish: (File) -> Unit = {},
) {
    init {
        require(maximumSourceBytes in 1..SixBundleContract.MAX_ARCHIVE_SIZE)
    }

    suspend fun import(uri: Uri): ImportResult = importRequest(uri)

    internal suspend fun importRequest(request: Any): ImportResult {
        val ownedDirectory = AtomicReference<File?>()
        var result: ImportResult? = null
        var retained = false
        var cleanupSucceeded = true
        try {
            result = withContext(ioDispatcher ?: Dispatchers.IO) {
                try {
                    importBlocking(request, ownedDirectory::set)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failure(ImportFailureCode.CACHE_IO)
                }
            }
            retained = result is ImportResult.Success
        } finally {
            if (!retained) {
                cleanupSucceeded = withContext(NonCancellable + Dispatchers.IO) {
                    ownedDirectory.get()?.let(::deleteTree) ?: true
                }
            }
        }
        if (!cleanupSucceeded) return failure(ImportFailureCode.CACHE_IO)
        return requireNotNull(result)
    }

    private suspend fun importBlocking(
        request: Any,
        ownDirectory: (File) -> Unit,
    ): ImportResult {
        val root = importRoot() ?: return failure(ImportFailureCode.CACHE_BOUNDARY_INVALID)
        val token = UUID.randomUUID().toString()
        val tokenDirectory = File(root, token)
        if (!tokenDirectory.mkdir()) {
            return failure(ImportFailureCode.CACHE_BOUNDARY_INVALID)
        }
        ownDirectory(tokenDirectory)
        if (!isDirectChild(root, tokenDirectory)) {
            return failure(ImportFailureCode.CACHE_BOUNDARY_INVALID)
        }
        val sourceFile = File(tokenDirectory, SOURCE_FILE)
        val candidateFile = File(tokenDirectory, CANDIDATE_FILE)
        val wrapperFile = File(tokenDirectory, WRAPPER_FILE)
        if (!isDirectChild(tokenDirectory, sourceFile) ||
            !isDirectChild(tokenDirectory, candidateFile) ||
            !isDirectChild(tokenDirectory, wrapperFile)
        ) {
            return failure(ImportFailureCode.CACHE_BOUNDARY_INVALID)
        }

        val input = try {
            source.open(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(ImportFailureCode.SOURCE_UNREADABLE)
        }
        try {
            input.use { copyBounded(it, sourceFile, maximumSourceBytes) }
        } catch (_: SourceTooLargeException) {
            return failure(ImportFailureCode.SOURCE_TOO_LARGE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(ImportFailureCode.SOURCE_UNREADABLE)
        }

        val bundle = try {
            sourceFile.inputStream().use(SixBundleContract::read)
        } catch (error: IllegalArgumentException) {
            return failure(
                if (error.message.orEmpty().contains("wrapper.apk SHA-256")) {
                    ImportFailureCode.WRAPPER_HASH_MISMATCH
                } else {
                    ImportFailureCode.INVALID_BUNDLE
                },
            )
        } catch (_: IOException) {
            return failure(ImportFailureCode.CACHE_IO)
        }

        try {
            FileOutputStream(candidateFile).use { it.write(bundle.wrapperApk) }
        } catch (_: IOException) {
            return failure(ImportFailureCode.CACHE_IO)
        }

        val verified = when (
            val verified = verifier.verify(
                apk = candidateFile,
                descriptor = bundle.extension,
                report = bundle.compatibility,
            )
        ) {
            is WrapperVerificationResult.Failure -> return ImportResult.Failure(verified.reason)
            is WrapperVerificationResult.Success -> verified.value
        }
        try {
            Files.move(
                candidateFile.toPath(),
                wrapperFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            afterPublish(wrapperFile)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(ImportFailureCode.CACHE_IO)
        }
        val publishedSha256 = runCatching { sha256(wrapperFile) }
            .getOrElse { return failure(ImportFailureCode.CACHE_IO) }
        if (publishedSha256 != verified.wrapperSha256) {
            return failure(ImportFailureCode.WRAPPER_HASH_MISMATCH)
        }
        if (!sourceFile.delete()) {
            return failure(ImportFailureCode.CACHE_IO)
        }
        return ImportResult.Success(
            ImportedBundle(
                token = token,
                descriptor = bundle.extension,
                report = bundle.compatibility,
                verifiedApk = verified,
                wrapperFile = wrapperFile,
                wrapperSha256 = verified.wrapperSha256,
            ),
        )
    }

    fun discard(token: String) {
        if (!isCanonicalToken(token)) return
        val root = importRoot() ?: return
        val target = File(root, token)
        if (isDirectChild(root, target)) deleteTree(target)
    }

    private fun importRoot(): File? = runCatching {
        val cache = cacheDirectory.canonicalFile
        val root = File(cache, IMPORT_DIRECTORY)
        if ((!root.exists() && !root.mkdirs()) || !root.isDirectory) return null
        if (Files.isSymbolicLink(root.toPath())) return null
        val canonicalRoot = root.canonicalFile
        if (canonicalRoot.parentFile != cache) return null
        canonicalRoot
    }.getOrNull()

    private fun isDirectChild(parent: File, child: File): Boolean = runCatching {
        child.canonicalFile.parentFile == parent.canonicalFile
    }.getOrDefault(false)

    private suspend fun copyBounded(
        input: InputStream,
        destination: File,
        maximumBytes: Long,
    ) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = readCancellable(input, buffer)
                currentCoroutineContext().ensureActive()
                if (count < 0) return
                if (total > maximumBytes - count) throw SourceTooLargeException()
                output.write(buffer, 0, count)
                total += count
            }
        }
    }

    private suspend fun readCancellable(input: InputStream, buffer: ByteArray): Int =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching(input::close)
            }
            try {
                val count = input.read(buffer)
                continuation.resumeWith(Result.success(count))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(directory: File): Boolean {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        error: IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun failure(code: ImportFailureCode) = ImportResult.Failure(ImportFailure(code))

    private class SourceTooLargeException : IOException()

    private companion object {
        const val IMPORT_DIRECTORY = "imports"
        const val SOURCE_FILE = "source.six"
        const val CANDIDATE_FILE = "wrapper.candidate.apk"
        const val WRAPPER_FILE = "wrapper.apk"
    }
}
