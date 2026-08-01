package dev.sixbridge.core.profile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class BrowserProfileExtractor private constructor(
    private val checkCancelled: () -> Unit,
    private val analyzerCandidates: () -> List<Path>,
    private val signerCandidates: () -> List<Path>,
    private val startProcess: (List<String>) -> Process,
    private val maxToolOutputBytes: Int,
    private val toolTimeoutMillis: Long,
    private val cleanupTimeoutMillis: Long,
    private val readerThreadNamePrefix: String,
) {
    constructor() : this(
        checkCancelled = {},
        analyzerCandidates = ::defaultApkAnalyzerCandidates,
        signerCandidates = ::defaultApkSignerCandidates,
        startProcess = { command -> ProcessBuilder(command).start() },
        maxToolOutputBytes = MAX_TOOL_OUTPUT_BYTES,
        toolTimeoutMillis = TOOL_TIMEOUT_MILLIS,
        cleanupTimeoutMillis = CLEANUP_TIMEOUT_MILLIS,
        readerThreadNamePrefix = "six-profile-tool-reader",
    )

    constructor(checkCancelled: () -> Unit) : this(
        checkCancelled = checkCancelled,
        analyzerCandidates = ::defaultApkAnalyzerCandidates,
        signerCandidates = ::defaultApkSignerCandidates,
        startProcess = { command -> ProcessBuilder(command).start() },
        maxToolOutputBytes = MAX_TOOL_OUTPUT_BYTES,
        toolTimeoutMillis = TOOL_TIMEOUT_MILLIS,
        cleanupTimeoutMillis = CLEANUP_TIMEOUT_MILLIS,
        readerThreadNamePrefix = "six-profile-tool-reader",
    )

    init {
        require(maxToolOutputBytes > 0)
        require(toolTimeoutMillis > 0)
        require(cleanupTimeoutMillis > 0)
        require(readerThreadNamePrefix.isNotBlank())
    }

    fun extract(browserApk: Path): BrowserProfile {
        checkCancelled()
        val manifestVersion = readManifestVersion(browserApk)
        checkCancelled()
        val filenameVersion = versionFromFilename(browserApk.fileName.toString())
        val filenameVersionCode = versionCodeFromFilename(browserApk.fileName.toString())
        val versionName = manifestVersion?.first
            ?: filenameVersion.takeUnless { it == "unknown" }
            ?: "unknown"
        val versionCode = manifestVersion?.second
            ?: filenameVersionCode.takeUnless { it == 0L }
            ?: 0
        val baseApk = PathBrowserArchiveInput(
            name = browserApk.fileName.toString(),
            path = browserApk,
            sha256 = sha256(browserApk),
        )
        checkCancelled()
        return BrowserArchiveEvidenceScanner(checkCancelled).scan(
            BrowserPackageEvidence(
                packageName = "com.sec.android.app.sbrowser",
                versionName = versionName,
                versionCode = versionCode,
                signerSha256 = readSignerSha256(browserApk).orEmpty(),
                baseApk = baseApk,
                splitApks = emptyList(),
            ),
        )
    }

    private fun versionFromFilename(name: String): String =
        VERSION_NAME_REGEX.find(name)?.groupValues?.get(1) ?: "unknown"

    private fun versionCodeFromFilename(name: String): Long =
        VERSION_CODE_FILENAME_REGEX.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0

    private fun readManifestVersion(browserApk: Path): Pair<String, Long>? {
        checkCancelled()
        val analyzer = analyzerCandidates().firstOrNull { it.exists() } ?: return null
        val versionName = runTool(
            listOf(analyzer.toString(), "manifest", "version-name", browserApk.toString()),
        ) ?: return null
        val versionCode = runTool(
            listOf(analyzer.toString(), "manifest", "version-code", browserApk.toString()),
        )?.toLongOrNull() ?: return null
        return versionName to versionCode
    }

    private fun readSignerSha256(browserApk: Path): String? {
        checkCancelled()
        val apkSigner = signerCandidates().firstOrNull { it.exists() } ?: return null
        val output = runTool(
            listOf(
                apkSigner.toString(),
                "verify",
                "--print-certs",
                browserApk.toString(),
            ),
        ) ?: return null
        return SIGNER_SHA_REGEX.find(output)
            ?.groupValues
            ?.get(1)
            ?.replace(":", "")
            ?.lowercase()
    }

    private fun runTool(command: List<String>): String? {
        checkCancelled()
        val process = try {
            startProcess(command)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val descendants = ProcessDescendantTracker(
            process,
            "$readerThreadNamePrefix-descendant-watcher",
        )
        val readerSequence = AtomicInteger()
        val readers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(
                runnable,
                "$readerThreadNamePrefix-${readerSequence.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
        val stdout = readers.submit<ByteArray> {
            readToolStream(process.inputStream, capture = true)
        }
        val stderr = readers.submit<ByteArray> {
            readToolStream(process.errorStream, capture = false)
        }
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(toolTimeoutMillis)
            while (true) {
                checkCancelled()
                throwCompletedReaderFailure(stdout)
                throwCompletedReaderFailure(stderr)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    cleanupOrThrow(process, descendants, stdout, stderr, readers)
                    return null
                }
                val waitMillis = minOf(
                    TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L),
                    PROCESS_POLL_MILLIS,
                )
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) break
            }
            checkCancelled()
            if (process.isAlive) {
                cleanupOrThrow(process, descendants, stdout, stderr, readers)
                return null
            }
            val readerDeadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMillis)
            val output = awaitReader(stdout, readerDeadline)
            awaitReader(stderr, readerDeadline)
            val exitValue = process.exitValue()
            cleanupOrThrow(process, descendants, stdout, stderr, readers)
            if (exitValue != 0) return null
            output.decodeToString().trim().takeIf(String::isNotEmpty)
        } catch (error: InterruptedException) {
            val cleanupFailure = cleanupFailure(process, descendants, stdout, stderr, readers)
            Thread.currentThread().interrupt()
            throw CancellationException("browser profile 외부 분석이 취소되었습니다.").also {
                it.initCause(error)
                cleanupFailure?.let(it::addSuppressed)
            }
        } catch (error: CancellationException) {
            cleanupFailure(process, descendants, stdout, stderr, readers)
                ?.let(error::addSuppressed)
            throw error
        } catch (error: Error) {
            cleanupFailure(process, descendants, stdout, stderr, readers)
                ?.let(error::addSuppressed)
            throw error
        } catch (error: ToolProcessCleanupException) {
            throw error
        } catch (_: Exception) {
            cleanupOrThrow(process, descendants, stdout, stderr, readers)
            null
        }
    }

    private fun cleanupOrThrow(
        process: Process,
        descendants: ProcessDescendantTracker,
        stdout: Future<ByteArray>,
        stderr: Future<ByteArray>,
        readers: ExecutorService,
    ) {
        cleanupFailure(process, descendants, stdout, stderr, readers)?.let { throw it }
    }

    private fun cleanupFailure(
        process: Process,
        descendants: ProcessDescendantTracker,
        stdout: Future<ByteArray>,
        stderr: Future<ByteArray>,
        readers: ExecutorService,
    ): ToolProcessCleanupException? = if (
        terminateProcessSession(process, descendants, stdout, stderr, readers)
    ) {
        null
    } else {
        ToolProcessCleanupException()
    }

    private fun readToolStream(input: InputStream, capture: Boolean): ByteArray {
        val output = if (capture) ByteArrayOutputStream() else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        input.use {
            while (true) {
                checkCancelled()
                val read = it.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (total.toLong() + read > maxToolOutputBytes.toLong()) {
                    throw ToolOutputLimitException()
                }
                total += read
                output?.write(buffer, 0, read)
            }
        }
        return output?.toByteArray() ?: ByteArray(0)
    }

    private fun throwCompletedReaderFailure(reader: Future<ByteArray>) {
        if (!reader.isDone) return
        try {
            reader.get()
        } catch (error: ExecutionException) {
            throwReaderCause(error.cause)
        }
    }

    private fun awaitReader(reader: Future<ByteArray>, deadline: Long): ByteArray {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0L) throw ToolReaderFailureException()
        return try {
            reader.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: ExecutionException) {
            throwReaderCause(error.cause)
        } catch (_: TimeoutException) {
            throw ToolReaderFailureException()
        }
    }

    private fun throwReaderCause(cause: Throwable?): Nothing = when (cause) {
        is CancellationException -> throw cause
        is Error -> throw cause
        else -> throw ToolReaderFailureException()
    }

    private fun terminateProcessSession(
        process: Process,
        descendants: ProcessDescendantTracker,
        stdout: Future<ByteArray>,
        stderr: Future<ByteArray>,
        readers: ExecutorService,
    ): Boolean {
        descendants.observeNow()
        val initialHandles = descendants.snapshot()
        // 관찰 기록의 깊이로 자식을 먼저 종료한다.
        initialHandles.forEach { handle ->
            runCatching { if (handle.isAlive) handle.destroyForcibly() }
        }
        runCatching { process.destroyForcibly() }
        val tracked = descendants.stopAndSnapshot(cleanupTimeoutMillis)
        tracked.handles.forEach { handle ->
            runCatching { if (handle.isAlive) handle.destroyForcibly() }
        }
        closeProcessStreams(process)
        stdout.cancel(true)
        stderr.cancel(true)
        readers.shutdownNow()
        val deadline = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(cleanupTimeoutMillis)
        while (System.nanoTime() < deadline) {
            val processAlive = runCatching { process.isAlive }.getOrDefault(true)
            val descendantAlive = tracked.handles.any { handle ->
                runCatching { handle.isAlive }.getOrDefault(true)
            }
            if (
                !processAlive &&
                !descendantAlive &&
                readers.isTerminated &&
                tracked.stopped &&
                !tracked.observationFailed
            ) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        closeProcessStreams(process)
        return !runCatching { process.isAlive }.getOrDefault(true) &&
            tracked.handles.none { runCatching { it.isAlive }.getOrDefault(true) } &&
            readers.isTerminated &&
            tracked.stopped &&
            !tracked.observationFailed
    }

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        checkCancelled()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class PathBrowserArchiveInput(
        override val name: String,
        private val path: Path,
        override val sha256: String,
    ) : BrowserArchiveInput {
        override fun openStream() = path.inputStream()
    }

    companion object {
        private const val PROCESS_POLL_MILLIS = 100L
        private const val MAX_TOOL_OUTPUT_BYTES = 1024 * 1024
        private const val TOOL_TIMEOUT_MILLIS = 30_000L
        private const val CLEANUP_TIMEOUT_MILLIS = 2_000L
        private val VERSION_NAME_REGEX =
            Regex("""(?:versionName[\s:=_-]*)?(\d{2}\.\d+\.\d+\.\d+)""")
        private val VERSION_CODE_FILENAME_REGEX =
            Regex("""-(\d{6,})_""")
        private val SIGNER_SHA_REGEX =
            Regex("""Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")

        internal fun testing(
            checkCancelled: () -> Unit = {},
            analyzerCandidates: () -> List<Path> = { emptyList() },
            signerCandidates: () -> List<Path> = { emptyList() },
            startProcess: (List<String>) -> Process = { command ->
                ProcessBuilder(command).start()
            },
            maxToolOutputBytes: Int = MAX_TOOL_OUTPUT_BYTES,
            toolTimeoutMillis: Long = TOOL_TIMEOUT_MILLIS,
            cleanupTimeoutMillis: Long = CLEANUP_TIMEOUT_MILLIS,
            readerThreadNamePrefix: String = "six-profile-tool-reader-test",
        ): BrowserProfileExtractor = BrowserProfileExtractor(
            checkCancelled,
            analyzerCandidates,
            signerCandidates,
            startProcess,
            maxToolOutputBytes,
            toolTimeoutMillis,
            cleanupTimeoutMillis,
            readerThreadNamePrefix,
        )
    }
}

private data class TrackedProcessSnapshot(
    val handles: List<ProcessHandle>,
    val stopped: Boolean,
    val observationFailed: Boolean,
)

private class ProcessDescendantTracker(
    process: Process,
    threadName: String,
) {
    private val failed = AtomicBoolean()
    private val parent = try {
        process.toHandle()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: RuntimeException) {
        failed.set(true)
        null
    }
    private val running = AtomicBoolean(parent != null)
    private val observed = ConcurrentHashMap<Long, ObservedProcess>()
    private val watcher = parent?.let {
        Thread({ watch() }, threadName).apply { isDaemon = true }
    }

    init {
        observeNow()
        watcher?.start()
    }

    fun observeNow() {
        val root = parent ?: return
        try {
            root.descendants().use { descendants ->
                descendants.forEach { handle ->
                    val candidate = ObservedProcess(handle, depthFromRoot(root, handle))
                    observed.compute(handle.pid()) { _, previous ->
                        if (previous == null || candidate.depth > previous.depth) {
                            candidate
                        } else {
                            previous
                        }
                    }
                }
            }
        } catch (_: UnsupportedOperationException) {
            running.set(false)
        } catch (_: RuntimeException) {
            failed.set(true)
            running.set(false)
        }
    }

    fun snapshot(): List<ProcessHandle> = observed.values
        .sortedByDescending(ObservedProcess::depth)
        .map(ObservedProcess::handle)

    fun stopAndSnapshot(timeoutMillis: Long): TrackedProcessSnapshot {
        observeNow()
        running.set(false)
        watcher?.interrupt()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (watcher?.isAlive == true && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        return TrackedProcessSnapshot(
            handles = snapshot(),
            stopped = watcher?.isAlive != true,
            observationFailed = failed.get(),
        )
    }

    private fun watch() {
        val root = parent ?: return
        while (running.get()) {
            observeNow()
            val rootAlive = try {
                root.isAlive
            } catch (_: RuntimeException) {
                failed.set(true)
                false
            }
            if (!rootAlive) break
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(WATCH_POLL_MILLIS))
        }
        observeNow()
        running.set(false)
    }

    private fun depthFromRoot(root: ProcessHandle, handle: ProcessHandle): Int {
        var cursor = handle
        var depth = 1
        repeat(MAX_TRACKED_PROCESS_DEPTH) {
            val parentHandle = try {
                cursor.parent().orElse(null)
            } catch (_: RuntimeException) {
                failed.set(true)
                return depth
            } ?: return depth
            if (parentHandle.pid() == root.pid()) return depth
            cursor = parentHandle
            depth += 1
        }
        return depth
    }

    private data class ObservedProcess(
        val handle: ProcessHandle,
        val depth: Int,
    )

    private companion object {
        const val MAX_TRACKED_PROCESS_DEPTH = 64
        const val WATCH_POLL_MILLIS = 5L
    }
}

private class ToolOutputLimitException : IllegalStateException()
private class ToolReaderFailureException : IllegalStateException()
internal class ToolProcessCleanupException : IllegalStateException()

private fun defaultApkAnalyzerCandidates(): List<Path> = buildList {
    System.getenv("ANDROID_SDK_ROOT")?.let { root ->
        add(Path.of(root, "cmdline-tools/latest/bin/apkanalyzer"))
    }
    System.getenv("ANDROID_HOME")?.let { root ->
        add(Path.of(root, "cmdline-tools/latest/bin/apkanalyzer"))
    }
    add(
        Path.of(
            "/opt/homebrew/share/android-commandlinetools/" +
                "cmdline-tools/latest/bin/apkanalyzer",
        ),
    )
    add(
        Path.of(
            System.getProperty("user.home"),
            "Library/Android/sdk/cmdline-tools/latest/bin/apkanalyzer",
        ),
    )
}.distinct()

private fun defaultApkSignerCandidates(): List<Path> = buildList {
    listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        "/opt/homebrew/share/android-commandlinetools",
        Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString(),
    ).distinct().forEach { rootValue ->
        val buildTools = Path.of(rootValue, "build-tools")
        if (Files.isDirectory(buildTools)) {
            Files.list(buildTools).use { versions ->
                versions.sorted(Comparator.reverseOrder()).forEach { version ->
                    add(version.resolve("apksigner"))
                }
            }
        }
    }
}
