package dev.sixbridge.manager.conversion

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AnalysisCacheCleanupResult(
    val deleted: Int,
    val failed: Int,
)

class AnalysisCacheCleaner(
    private val cacheRoot: Path,
) {
    suspend fun clean(): AnalysisCacheCleanupResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!Files.exists(cacheRoot, NOFOLLOW_LINKS)) {
            return@withContext AnalysisCacheCleanupResult(deleted = 0, failed = 0)
        }
        if (!Files.isDirectory(cacheRoot, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(cacheRoot)
        ) {
            return@withContext AnalysisCacheCleanupResult(deleted = 0, failed = 1)
        }

        var deleted = 0
        var failed = 0
        try {
            Files.newDirectoryStream(cacheRoot).use { children ->
                val iterator = children.iterator()
                var inspected = 0
                while (inspected < MAX_DIRECT_CHILDREN && iterator.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val candidate = iterator.next()
                    inspected += 1
                    if (!candidate.isAnalysisDirectory()) continue
                    try {
                        deleteTree(candidate)
                        deleted += 1
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed += 1
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed += 1
        }
        AnalysisCacheCleanupResult(deleted = deleted, failed = failed)
    }

    private fun Path.isAnalysisDirectory(): Boolean =
        fileName.toString().startsWith(ANALYSIS_DIRECTORY_PREFIX) &&
            Files.isDirectory(this, NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(this)

    private suspend fun deleteTree(root: Path) {
        val coroutineContext = currentCoroutineContext()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    coroutineContext.ensureActive()
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    coroutineContext.ensureActive()
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        const val ANALYSIS_DIRECTORY_PREFIX = "sixbridge-analysis-"
        const val MAX_DIRECT_CHILDREN = 64
    }
}
