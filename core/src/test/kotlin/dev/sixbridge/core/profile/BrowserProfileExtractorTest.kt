package dev.sixbridge.core.profile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowserProfileExtractorTest {
    @Test
    fun `pipe capacity보다 큰 stdout과 stderr를 concurrent drain해 교착 없이 완료한다`() {
        val analyzer = Files.createTempFile("large-output-analyzer-", "")
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val readerPrefix = "large-tool-reader-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        val extractor = BrowserProfileExtractor.testing(
            analyzerCandidates = { listOf(analyzer) },
            signerCandidates = { emptyList() },
            startProcess = { fixtureProcess("large") },
            maxToolOutputBytes = 512 * 1024,
            toolTimeoutMillis = 5_000,
            cleanupTimeoutMillis = 2_000,
            readerThreadNamePrefix = readerPrefix,
        )

        try {
            val profile = executor.submit<BrowserProfile> { extractor.extract(apk) }
                .get(10, TimeUnit.SECONDS)

            assertEquals("unknown", profile.versionName)
            assertNoReaderThreads(readerPrefix)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `stdout overflow는 process를 bounded 종료하고 reader를 남기지 않는다`() {
        val analyzer = Files.createTempFile("overflow-analyzer-", "")
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val pidFile = Files.createTempFile("overflow-process-", ".pid")
        Files.delete(pidFile)
        val readerPrefix = "overflow-tool-reader-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        val extractor = BrowserProfileExtractor.testing(
            analyzerCandidates = { listOf(analyzer) },
            signerCandidates = { emptyList() },
            startProcess = { fixtureProcess("overflow", pidFile.toString()) },
            maxToolOutputBytes = 1_024,
            toolTimeoutMillis = 5_000,
            cleanupTimeoutMillis = 2_000,
            readerThreadNamePrefix = readerPrefix,
        )

        try {
            executor.submit<BrowserProfile> { extractor.extract(apk) }
                .get(10, TimeUnit.SECONDS)
            val pid = waitForPids(pidFile, 1).single()

            assertProcessDead(pid)
            assertNoReaderThreads(readerPrefix)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancel은 process tree와 pipe reader를 모두 종료한다`() {
        val analyzer = Files.createTempFile("cancel-tree-analyzer-", "")
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val pidFile = Files.createTempFile("cancel-tree-", ".pid")
        Files.delete(pidFile)
        val readerPrefix = "cancel-tool-reader-${System.nanoTime()}"
        val executor = Executors.newSingleThreadExecutor()
        val extractor = BrowserProfileExtractor.testing(
            analyzerCandidates = { listOf(analyzer) },
            signerCandidates = { emptyList() },
            startProcess = { fixtureProcess("tree", pidFile.toString()) },
            maxToolOutputBytes = 512 * 1024,
            toolTimeoutMillis = 30_000,
            cleanupTimeoutMillis = 2_000,
            readerThreadNamePrefix = readerPrefix,
        )

        try {
            val future = executor.submit<BrowserProfile> { extractor.extract(apk) }
            val pids = waitForPids(pidFile, 2)

            future.cancel(true)

            pids.forEach(::assertProcessDead)
            assertNoReaderThreads(readerPrefix)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `waitFor 성공 뒤에도 살아 있는 process는 도구 성공으로 처리하지 않는다`() {
        val analyzer = Files.createTempFile("stubborn-analyzer-", "")
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val started = AtomicInteger()
        val processes = mutableListOf<StubbornSuccessfulProcess>()
        val extractor = BrowserProfileExtractor.testing(
            analyzerCandidates = { listOf(analyzer) },
            signerCandidates = { emptyList() },
            startProcess = { command ->
                started.incrementAndGet()
                StubbornSuccessfulProcess(
                    if ("version-name" in command) "31.0.0.1" else "1300000000",
                ).also(processes::add)
            },
            toolTimeoutMillis = 1_000,
            cleanupTimeoutMillis = 50,
        )

        val profile = extractor.extract(apk)

        assertEquals("unknown", profile.versionName)
        assertEquals(1, started.get())
        assertTrue(processes.single().destroyCalls.get() > 0)
    }

    @Test
    fun `외부 분석기 대기 thread가 interrupt되면 process를 강제 종료한다`() {
        val analyzer = Files.createTempFile("fake-apkanalyzer-", "")
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val process = BlockingTestProcess()
        val executor = Executors.newSingleThreadExecutor()
        val extractor = BrowserProfileExtractor.testing(
            analyzerCandidates = { listOf(analyzer) },
            signerCandidates = { emptyList() },
            startProcess = { process },
        )

        try {
            val future = executor.submit<BrowserProfile> { extractor.extract(apk) }
            assertTrue(process.entered.await(5, TimeUnit.SECONDS))

            future.cancel(true)

            assertTrue(process.destroyed.await(5, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    @Test
    fun `APK SHA 읽기 중 cancellation checkpoint를 호출한다`() {
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val extractor = BrowserProfileExtractor.testing(
            checkCancelled = {
                if (Thread.currentThread().stackTrace.any { it.methodName == "sha256" }) {
                    throw CancellationException("sha 취소")
                }
            },
            analyzerCandidates = { emptyList() },
            signerCandidates = { emptyList() },
        )

        assertFailsWith<CancellationException> { extractor.extract(apk) }
    }

    @Test
    fun `APK evidence scan에 cancellation checkpoint를 전달한다`() {
        val apk = browserApk(dex = "extension-id", terrace = "storage")
        val extractor = BrowserProfileExtractor.testing(
            checkCancelled = {
                if (
                    Thread.currentThread().stackTrace.any {
                        it.className == BrowserArchiveEvidenceScanner::class.java.name
                    }
                ) {
                    throw CancellationException("scan 취소")
                }
            },
            analyzerCandidates = { emptyList() },
            signerCandidates = { emptyList() },
        )

        assertFailsWith<CancellationException> { extractor.extract(apk) }
    }

    @Test
    fun `records option page accessibility only with the complete Samsung contract`() {
        val complete = browserApk(
            dex = """
                six://ex extension-id .contentprovider dev_ext
                getExtensionOptionPage six_add_on_settings updateGoAddonSettings
            """.trimIndent(),
            terrace = """{"namespace":"action"} options_ui options_page""",
        )
        val partial = browserApk(
            dex = "getExtensionOptionPage six_add_on_settings",
            terrace = "options_ui",
        )
        val absent = browserApk(
            dex = "six://ex extension-id .contentprovider dev_ext",
            terrace = """{"namespace":"action"}""",
        )
        val swapped = browserApk(
            dex = "options_ui options_page",
            terrace = "getExtensionOptionPage six_add_on_settings updateGoAddonSettings",
        )

        assertTrue(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(complete).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(partial).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(absent).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(swapped).supportedFeatures,
        )
    }

    @Test
    fun `requires one ABI runtime library to contain every option page marker`() {
        val dex = "getExtensionOptionPage six_add_on_settings updateGoAddonSettings"
        val splitAcrossAbis = browserApk(
            dex = dex,
            terraceByAbi = mapOf(
                "arm64-v8a" to "options_ui",
                "armeabi-v7a" to "options_page",
            ),
        )
        val completeInOneAbi = browserApk(
            dex = dex,
            terraceByAbi = mapOf(
                "arm64-v8a" to "options_ui options_page",
                "armeabi-v7a" to "options_ui",
            ),
        )

        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(splitAcrossAbis).supportedFeatures,
        )
        assertTrue(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(completeInOneAbi).supportedFeatures,
        )
    }

    @Test
    fun `extracts the Samsung extension contract and supported namespaces`() {
        val directory = Files.createTempDirectory("browser-profile")
        val apk = directory.resolve(
            "sbrowser_30.0.2.61-1300261502_minAPI30.apk",
        )
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                """
                six://ex extension-id .contentprovider dev_ext
                unrelated 10.200004.10.1 versionCode 999999999
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write(
                """
                {"namespace":"storage"}{"namespace":"tabs"}{"namespace":"offscreen"}
                {"namespace":"debugger"}{"namespace":"downloads"}
                {"namespace":"nativeMessaging"}{"namespace":"webstorePrivate"}
                """.trimIndent()
                    .toByteArray(),
            )
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(30, profile.majorVersion)
        assertEquals(setOf("offscreen", "storage", "tabs"), profile.supportedApis)
        assertTrue(profile.wrapperContract.discoveryUri)
        assertTrue(profile.wrapperContract.extensionMetadata)
        assertTrue(profile.wrapperContract.contentProvider)
        assertTrue(profile.wrapperContract.devExtensionPath)
        assertFalse(profile.isSupportedBaseline)
        assertEquals(ProfileSupport.UNKNOWN, profile.support)
        val encoded = BrowserProfileJson.encode(profile)
        assertTrue("\"detectedApis\"" in encoded)
        assertTrue("\"debugger\"" in encoded)
        assertTrue("\"webstorePrivate\"" in encoded)
    }

    @Test
    fun `marks an incomplete or new contract unknown`() {
        val apk = Files.createTempFile("browser", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("versionName 31.0.0.1 extension-id".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertFalse(profile.isSupportedBaseline)
        assertEquals(ProfileSupport.UNKNOWN, profile.support)
    }

    @Test
    fun `marks a changed 30 x API profile unknown until reviewed`() {
        val apk = Files.createTempFile("browser-30.1.0.1-", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                "six://ex extension-id .contentprovider dev_ext versionName 30.1.0.1"
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write("""{"namespace":"storage"}""".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(ProfileSupport.UNKNOWN, profile.support)
        assertTrue(profile.profileDifferences.any { it.startsWith("missing_api:") })
    }

    private fun browserApk(dex: String, terrace: String): Path {
        return browserApk(dex, mapOf("arm64-v8a" to terrace))
    }

    private fun browserApk(dex: String, terraceByAbi: Map<String, String>): Path {
        val apk = Files.createTempFile("six-browser-profile-", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex.toByteArray())
            zip.closeEntry()
            terraceByAbi.forEach { (abi, terrace) ->
                zip.putNextEntry(ZipEntry("lib/$abi/libterrace.so"))
                zip.write(terrace.toByteArray())
                zip.closeEntry()
            }
        }
        return apk
    }

    private fun fixtureProcess(vararg arguments: String): Process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        BrowserProfileExtractorProcessFixture::class.java.name,
        *arguments,
    ).start()

    private fun waitForPids(path: Path, count: Int): List<Long> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val pids = runCatching {
                Files.readAllLines(path).filter(String::isNotBlank).map(String::toLong)
            }.getOrDefault(emptyList())
            if (pids.size >= count) return pids.take(count)
            Thread.sleep(10)
        }
        error("fixture PID를 확인하지 못했습니다.")
    }

    private fun assertProcessDead(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)) return
            Thread.sleep(10)
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }

    private fun assertNoReaderThreads(prefix: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith(prefix) }) {
                return
            }
            Thread.sleep(10)
        }
        assertTrue(
            Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith(prefix) },
        )
    }

    private class BlockingTestProcess : Process() {
        val entered = CountDownLatch(1)
        val destroyed = CountDownLatch(1)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            entered.countDown()
            destroyed.await()
            return -1
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            entered.countDown()
            return destroyed.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (destroyed.count != 0L) throw IllegalThreadStateException()
            return -1
        }

        override fun destroy() {
            destroyed.countDown()
        }

        override fun destroyForcibly(): Process {
            destroyed.countDown()
            return this
        }

        override fun isAlive(): Boolean = destroyed.count != 0L
    }

    private class StubbornSuccessfulProcess(output: String) : Process() {
        val destroyCalls = AtomicInteger()
        private val stdout = ByteArrayInputStream(output.encodeToByteArray())
        private val stderr = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyCalls.incrementAndGet()
        }
        override fun destroyForcibly(): Process {
            destroyCalls.incrementAndGet()
            return this
        }
        override fun isAlive(): Boolean = true
    }
}

object BrowserProfileExtractorProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        when (arguments.first()) {
            "large" -> {
                val bytes = ByteArray(256 * 1024) { 'x'.code.toByte() }
                System.out.write(bytes)
                System.out.flush()
                System.err.write(bytes)
                System.err.flush()
            }
            "overflow" -> {
                writePid(Path.of(arguments[1]), ProcessHandle.current().pid())
                val bytes = ByteArray(8 * 1024) { 'o'.code.toByte() }
                while (true) {
                    System.out.write(bytes)
                    System.out.flush()
                }
            }
            "tree" -> {
                val descendant = ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    BrowserProfileExtractorProcessFixture::class.java.name,
                    "descendant",
                ).start()
                val pidFile = Path.of(arguments[1])
                writePid(pidFile, ProcessHandle.current().pid())
                writePid(pidFile, descendant.pid())
                val bytes = ByteArray(4 * 1024) { 'c'.code.toByte() }
                while (true) {
                    System.out.write(bytes)
                    System.out.flush()
                    System.err.write(bytes)
                    System.err.flush()
                    Thread.sleep(5)
                }
            }
            "descendant" -> while (true) Thread.sleep(1_000)
            else -> error("unknown fixture mode")
        }
    }

    private fun writePid(path: Path, pid: Long) {
        Files.writeString(path, "$pid\n", CREATE, APPEND)
    }
}
