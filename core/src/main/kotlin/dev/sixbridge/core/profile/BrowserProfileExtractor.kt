package dev.sixbridge.core.profile

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class BrowserProfileExtractor private constructor(
    private val checkCancelled: () -> Unit,
    private val analyzerCandidates: () -> List<Path>,
    private val signerCandidates: () -> List<Path>,
    private val startProcess: (List<String>) -> Process,
) {
    constructor() : this(
        checkCancelled = {},
        analyzerCandidates = ::defaultApkAnalyzerCandidates,
        signerCandidates = ::defaultApkSignerCandidates,
        startProcess = { command -> ProcessBuilder(command).start() },
    )

    constructor(checkCancelled: () -> Unit) : this(
        checkCancelled = checkCancelled,
        analyzerCandidates = ::defaultApkAnalyzerCandidates,
        signerCandidates = ::defaultApkSignerCandidates,
        startProcess = { command -> ProcessBuilder(command).start() },
    )

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
        return try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (true) {
                checkCancelled()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) {
                    destroyProcess(process)
                    return null
                }
                val waitMillis = minOf(
                    TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L),
                    PROCESS_POLL_MILLIS,
                )
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) break
            }
            checkCancelled()
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim().takeIf(String::isNotEmpty)
            }
        } catch (error: InterruptedException) {
            destroyProcess(process)
            Thread.currentThread().interrupt()
            throw CancellationException("browser profile 외부 분석이 취소되었습니다.").also {
                it.initCause(error)
            }
        } catch (error: CancellationException) {
            destroyProcess(process)
            throw error
        } catch (_: Exception) {
            if (process.isAlive) destroyProcess(process)
            null
        }
    }

    private fun destroyProcess(process: Process) {
        process.destroyForcibly()
        try {
            process.waitFor(PROCESS_DESTROY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        private const val PROCESS_DESTROY_WAIT_MILLIS = 1_000L
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
        ): BrowserProfileExtractor = BrowserProfileExtractor(
            checkCancelled,
            analyzerCandidates,
            signerCandidates,
            startProcess,
        )
    }
}

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
