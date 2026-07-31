package dev.sixbridge.core.compat

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.READ
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

class AnalysisInputMaterializerTest {
    @Test
    fun `주입된 snapshot root에서만 live source snapshot을 만든다`() {
        val source = Files.createTempDirectory("six-input-provider-source")
        Files.writeString(source.resolve("manifest.json"), validManifest)
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val snapshot = Files.createDirectory(fileSystem.getPath("/snapshot"))

            AnalysisInputMaterializer.snapshotDirectory(
                root = source,
                control = AnalysisControl(AnalysisSafetyPolicy.mobile),
                snapshot = snapshot,
                cleanupSnapshot = {},
            ) { captured ->
                assertSame(fileSystem.provider(), captured.fileSystem.provider())
            }
        }
    }

    @Test
    fun `archive FIFO는 channel open 전에 거부한다`() {
        val directory = Files.createTempDirectory("six-input-fifo-")
        val fifo = directory.resolve("input.zip")
        assertEquals(
            0,
            ProcessBuilder("mkfifo", fifo.toString()).start().waitFor(),
        )
        val executor = Executors.newSingleThreadExecutor()
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val writer = executor.submit {
            writerStarted.countDown()
            try {
                Files.newOutputStream(fifo).use { it.write(1) }
            } finally {
                writerFinished.countDown()
            }
        }
        assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
        var payloadReads = 0

        try {
            assertFailsWith<IllegalArgumentException> {
                AnalysisInputMaterializer.readArchive(
                    fifo,
                    AnalysisArchiveKind.ZIP,
                    AnalysisControl(AnalysisSafetyPolicy.mobile),
                ) {
                    payloadReads += 1
                    Files.readAllBytes(it)
                }
            }
            assertFalse(
                writerFinished.await(1, TimeUnit.SECONDS),
                "FIFO writer 완료는 archive channel이 열렸음을 뜻합니다.",
            )
        } finally {
            if (!writer.isDone) {
                FileChannel.open(fifo, READ).use { channel ->
                    channel.read(ByteBuffer.allocate(1))
                }
            }
            writer.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }

        assertEquals(0, payloadReads)
    }

    @Test
    fun `빈 디렉터리 breadth도 traversal entry 예산을 넘으면 snapshotter 전에 거부한다`() {
        val root = Files.createTempDirectory("six-input-empty-directories-")
        repeat(5) { index ->
            Files.createDirectory(root.resolve("empty-$index"))
        }
        val policy = AnalysisSafetyPolicy.mobile.copy(maxScriptFiles = 2)
        var snapshots = 0

        val error = assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.snapshotDirectory(
                root,
                AnalysisControl(policy),
            ) {
                snapshots += 1
                Unit
            }
        }

        assertEquals(5, error.exceeded.actual)
        assertEquals(4, error.exceeded.limit)
        assertEquals(0, snapshots)
    }

    @Test
    fun `source directory 예산 초과는 snapshot 전에 거부한다`() {
        val root = Files.createTempDirectory("six-input-directory-")
        Files.writeString(root.resolve("manifest.json"), validManifest)
        Files.write(
            root.resolve("large.js"),
            ByteArray(AnalysisSafetyPolicy.mobile.maxSingleScriptBytes.toInt() + 1),
        )
        var snapshots = 0

        val error = assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.snapshotDirectory(
                root,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                snapshots += 1
                Unit
            }
        }

        assertEquals("analysis_budget_exceeded", error.code)
        assertEquals(0, snapshots)
    }

    @Test
    fun `ZIP declared entry 예산 초과는 payload reader 전에 거부한다`() {
        val input = oversizedZip()
        var payloadReads = 0

        assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.readArchive(
                input,
                AnalysisArchiveKind.ZIP,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                payloadReads += 1
                Files.readAllBytes(it)
            }
        }

        assertEquals(0, payloadReads)
    }

    @Test
    fun `CRX header 뒤 ZIP declared entry 예산 초과는 payload reader 전에 거부한다`() {
        val zip = oversizedZip()
        val input = Files.createTempFile("six-input-", ".crx")
        Files.newOutputStream(input).use { output ->
            output.write("Cr24".encodeToByteArray())
            output.write(byteArrayOf(3, 0, 0, 0))
            output.write(byteArrayOf(0, 0, 0, 0))
            Files.copy(zip, output)
        }
        var payloadReads = 0

        assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.readArchive(
                input,
                AnalysisArchiveKind.CRX3,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                payloadReads += 1
                Files.readAllBytes(it)
            }
        }

        assertEquals(0, payloadReads)
    }

    @Test
    fun `directory snapshotter는 검사한 live root가 아닌 app-owned snapshot을 받는다`() {
        val root = Files.createTempDirectory("six-input-live-")
        Files.writeString(root.resolve("manifest.json"), validManifest)
        var snapshotRoot: Path? = null

        val captured = AnalysisInputMaterializer.snapshotDirectory(
            root,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        ) { stable ->
            snapshotRoot = stable
            Files.writeString(root.resolve("manifest.json"), """{"name":"replaced"}""")
            Files.readString(stable.resolve("manifest.json"))
        }

        assertEquals(validManifest, captured)
        assertNotEquals(root.toAbsolutePath().normalize(), snapshotRoot)
    }

    @Test
    fun `archive path 교체는 preflight한 descriptor의 payload를 바꾸지 못한다`() {
        val original = zipWithScript("original.js", "console.log('original')")
        val replacement = zipWithScript("replacement.js", "console.log('replacement')")
        val expected = Files.readAllBytes(original)

        val captured = AnalysisInputMaterializer.readArchive(
            original,
            AnalysisArchiveKind.ZIP,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        ) { opened ->
            Files.move(
                replacement,
                original,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.readAllBytes(opened)
        }

        assertContentEquals(expected, captured)
    }

    @Test
    fun `directory cleanup 실패는 primary cancellation에 suppressed로 남는다`() {
        val root = Files.createTempDirectory("six-input-cleanup-directory-")
        Files.writeString(root.resolve("manifest.json"), validManifest)
        val cancellation = CancellationException("cancel directory snapshot")
        val cleanup = IOException("directory cleanup failed")

        val thrown = assertFailsWith<CancellationException> {
            AnalysisInputMaterializer.snapshotDirectory(
                root = root,
                control = AnalysisControl(AnalysisSafetyPolicy.mobile),
                cleanupSnapshot = { throw cleanup },
                snapshotter = { throw cancellation },
            )
        }

        assertTrue(thrown === cancellation)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test
    fun `archive cleanup 실패는 primary cancellation에 suppressed로 남는다`() {
        val input = zipWithScript("worker.js", "console.log('worker')")
        val cancellation = CancellationException("cancel archive reader")
        val cleanup = IOException("archive cleanup failed")

        val thrown = assertFailsWith<CancellationException> {
            AnalysisInputMaterializer.readArchive(
                path = input,
                kind = AnalysisArchiveKind.ZIP,
                control = AnalysisControl(AnalysisSafetyPolicy.mobile),
                cleanupSnapshot = { throw cleanup },
                payloadReader = { throw cancellation },
            )
        }

        assertTrue(thrown === cancellation)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
    }

    @Test
    fun `primary가 없으면 snapshot cleanup 실패를 직접 전파한다`() {
        val input = zipWithScript("worker.js", "console.log('worker')")
        val cleanup = IOException("archive cleanup failed")

        val thrown = assertFailsWith<IOException> {
            AnalysisInputMaterializer.readArchive(
                path = input,
                kind = AnalysisArchiveKind.ZIP,
                control = AnalysisControl(AnalysisSafetyPolicy.mobile),
                cleanupSnapshot = { throw cleanup },
                payloadReader = Files::readAllBytes,
            )
        }

        assertTrue(thrown === cleanup)
    }

    @Test
    fun `traversal entry는 payload reader 전에 거부한다`() {
        val input = zipWithScript("../escape.js", "console.log('escape')")
        var payloadReads = 0

        assertFailsWith<IllegalArgumentException> {
            AnalysisInputMaterializer.readArchive(
                input,
                AnalysisArchiveKind.ZIP,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                payloadReads += 1
                Files.readAllBytes(it)
            }
        }

        assertEquals(0, payloadReads)
    }

    private fun zipWithScript(name: String, script: String): Path =
        Files.createTempFile("six-input-stable-", ".zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(validManifest.encodeToByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry(name))
                output.write(script.encodeToByteArray())
                output.closeEntry()
            }
        }

    private fun oversizedZip(): Path =
        Files.createTempFile("six-input-", ".zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(validManifest.encodeToByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("large.js"))
                repeat(
                    AnalysisSafetyPolicy.mobile.maxSingleScriptBytes.toInt() + 1,
                ) {
                    output.write(0)
                }
                output.closeEntry()
            }
        }

    private val validManifest =
        """{"manifest_version":3,"name":"Budget","version":"1"}"""
}
