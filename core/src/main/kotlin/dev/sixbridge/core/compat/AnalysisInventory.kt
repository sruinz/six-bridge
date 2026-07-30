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
    SINGLE_SOURCE_BYTES,
    TOTAL_SOURCE_BYTES,
    SOURCE_FILE_COUNT,
    RUNTIME_DEPENDENCY_COUNT,
    TOTAL_SOURCE_CHARS,
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
    ): AnalysisInventoryResult = inspect(
        root,
        AnalysisBudgetLedger(policy),
        checkCancelled,
    )

    internal fun inspect(
        root: Path,
        ledger: AnalysisBudgetLedger,
        checkCancelled: () -> Unit = {},
    ): AnalysisInventoryResult {
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

                    try {
                        ledger.reserveScript(file, attributes.size())
                    } catch (error: AnalysisBudgetExceededException) {
                        exceeded = error.exceeded
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

class AnalysisBudgetExceededException(
    val exceeded: AnalysisInventoryResult.Exceeded,
) : IllegalArgumentException(
    "${exceeded.metric}: ${exceeded.actual} > ${exceeded.limit}",
) {
    val code: String = "analysis_budget_exceeded"
}

fun analysisBudgetExceededReport(
    exceeded: AnalysisInventoryResult.Exceeded,
    manifestVersion: Int? = null,
    name: String? = null,
    version: String? = null,
    permissions: Set<String> = emptySet(),
): CompatibilityReport = CompatibilityReport(
    status = CompatibilityStatus.UNKNOWN,
    manifestVersion = manifestVersion,
    extensionName = name,
    extensionVersion = version,
    declaredPermissions = permissions.toSortedSet(),
    usedApis = emptySet(),
    unsupportedApis = emptySet(),
    issues = listOf(
        CompatibilityIssue(
            code = "analysis_budget_exceeded",
            message = "${exceeded.metric}: ${exceeded.actual} > ${exceeded.limit}",
            blocking = true,
        ),
    ),
)

internal class AnalysisBudgetLedger(
    val policy: AnalysisSafetyPolicy,
) {
    private val sourceFiles = linkedSetOf<Path>()
    private val scriptFiles = linkedSetOf<Path>()
    private val runtimeDependencies = linkedSetOf<Path>()
    private val textSources = linkedSetOf<Path>()
    private var sourceBytes = 0L
    private var scriptBytes = 0L
    private var sourceChars = 0L

    fun reserveSource(path: Path, size: Long) {
        require(size >= 0L) { "분석 source 크기가 올바르지 않습니다." }
        val key = path.toAbsolutePath().normalize()
        if (key in sourceFiles) return
        exceed(
            AnalysisBudgetMetric.SOURCE_FILE_COUNT,
            sourceFiles.size.toLong() + 1,
            policy.maxScriptFiles.toLong(),
        )
        exceed(
            AnalysisBudgetMetric.SINGLE_SOURCE_BYTES,
            size,
            policy.maxSingleScriptBytes,
        )
        exceed(
            AnalysisBudgetMetric.TOTAL_SOURCE_BYTES,
            checkedAdd(sourceBytes, size),
            policy.maxTotalScriptBytes,
        )
        sourceFiles.add(key)
        sourceBytes += size
    }

    fun reserveScript(path: Path, size: Long) {
        val key = path.toAbsolutePath().normalize()
        if (key in scriptFiles) return
        exceed(
            AnalysisBudgetMetric.SCRIPT_FILE_COUNT,
            scriptFiles.size.toLong() + 1,
            policy.maxScriptFiles.toLong(),
        )
        exceed(
            AnalysisBudgetMetric.SINGLE_SCRIPT_BYTES,
            size,
            policy.maxSingleScriptBytes,
        )
        exceed(
            AnalysisBudgetMetric.TOTAL_SCRIPT_BYTES,
            checkedAdd(scriptBytes, size),
            policy.maxTotalScriptBytes,
        )
        reserveSource(key, size)
        scriptFiles.add(key)
        scriptBytes += size
    }

    fun reserveRuntimeDependency(path: Path, size: Long, text: Boolean) {
        val key = path.toAbsolutePath().normalize()
        if (key !in runtimeDependencies) {
            exceed(
                AnalysisBudgetMetric.RUNTIME_DEPENDENCY_COUNT,
                runtimeDependencies.size.toLong() + 1,
                policy.maxScriptFiles.toLong(),
            )
            reserveSource(key, size)
            runtimeDependencies.add(key)
        }
        if (text) reserveText(key, size)
    }

    fun reserveText(path: Path, maximumChars: Long) {
        val key = path.toAbsolutePath().normalize()
        if (key in textSources) return
        exceed(
            AnalysisBudgetMetric.TOTAL_SOURCE_CHARS,
            checkedAdd(sourceChars, maximumChars),
            policy.maxTotalScriptBytes,
        )
        textSources.add(key)
        sourceChars += maximumChars
    }

    private fun exceed(metric: AnalysisBudgetMetric, actual: Long, limit: Long) {
        if (actual > limit) {
            throw AnalysisBudgetExceededException(
                AnalysisInventoryResult.Exceeded(metric, actual, limit),
            )
        }
    }

    private fun checkedAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
}
