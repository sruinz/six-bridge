package dev.sixbridge.core.compat

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension

enum class AnalysisBudgetMetric {
    MANIFEST_BYTES,
    SINGLE_SCRIPT_BYTES,
    TOTAL_SCRIPT_BYTES,
    SCRIPT_FILE_COUNT,
}

sealed interface AnalysisInventoryResult {
    data object Allowed : AnalysisInventoryResult

    data class Exceeded(
        val metric: AnalysisBudgetMetric,
        val actual: Long,
        val limit: Long,
    ) : AnalysisInventoryResult
}

object AnalysisInventory {
    fun inspect(
        root: Path,
        policy: AnalysisSafetyPolicy,
        checkCancelled: () -> Unit = {},
    ): AnalysisInventoryResult {
        var totalScriptBytes = 0L
        var scriptFiles = 0L
        var exceeded: AnalysisInventoryResult.Exceeded? = null

        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    checkCancelled()
                    if (!attributes.isRegularFile || attributes.isSymbolicLink ||
                        file.extension.lowercase() !in SCRIPT_EXTENSIONS
                    ) {
                        return FileVisitResult.CONTINUE
                    }

                    scriptFiles += 1
                    if (scriptFiles > policy.maxScriptFiles) {
                        exceeded = AnalysisInventoryResult.Exceeded(
                            AnalysisBudgetMetric.SCRIPT_FILE_COUNT,
                            scriptFiles,
                            policy.maxScriptFiles.toLong(),
                        )
                        return FileVisitResult.TERMINATE
                    }

                    val size = attributes.size()
                    if (size > policy.maxSingleScriptBytes) {
                        exceeded = AnalysisInventoryResult.Exceeded(
                            AnalysisBudgetMetric.SINGLE_SCRIPT_BYTES,
                            size,
                            policy.maxSingleScriptBytes,
                        )
                        return FileVisitResult.TERMINATE
                    }

                    totalScriptBytes += size
                    if (totalScriptBytes > policy.maxTotalScriptBytes) {
                        exceeded = AnalysisInventoryResult.Exceeded(
                            AnalysisBudgetMetric.TOTAL_SCRIPT_BYTES,
                            totalScriptBytes,
                            policy.maxTotalScriptBytes,
                        )
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return exceeded ?: AnalysisInventoryResult.Allowed
    }

    private val SCRIPT_EXTENSIONS = setOf("js", "mjs", "cjs", "html")
}
