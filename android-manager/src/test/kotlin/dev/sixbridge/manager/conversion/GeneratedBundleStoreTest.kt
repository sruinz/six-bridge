package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.packager.OnDeviceWrapperResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class GeneratedBundleStoreTest {
    private val filesDir = Files.createTempDirectory("six-generated-bundle-store").toFile()
    private val token = "11111111-1111-4111-8111-111111111111"

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `게시한 bundle을 다시 열면 descriptor report wrapper가 동일하다`() {
        val result = result()
        var verifiedWrapper: ByteArray? = null
        val store = store(
            verifier = GeneratedWrapperVerifier { wrapper, descriptor, report ->
                verifiedWrapper = wrapper.readBytes()
                descriptor == result.descriptor &&
                    BundleJson.encodeCompatibility(report) ==
                    BundleJson.encodeCompatibility(result.compatibility)
            },
        )

        val generated = store.publish(token, result)
        val reopened = store.open(token).use(SixBundleContract::read)

        assertContentEquals(result.outputApk.readBytes(), verifiedWrapper)
        assertContentEquals(result.outputApk.readBytes(), reopened.wrapperApk)
        assertEquals(result.descriptor, reopened.extension)
        assertEquals(result.compatibility, reopened.compatibility)
        assertEquals(sha256(generated.file), generated.sha256)
        assertEquals(File(filesDir, "generated-bundles/$token.six"), generated.file)
    }

    @Test
    fun `store proof와 다른 generated request 파일이나 hash는 열지 않는다`() {
        val store = store()
        val generated = store.publish(token, result())

        assertFailsWith<IllegalArgumentException> {
            store.open(generated.copy(file = File(filesDir, "outside.six")))
        }
        assertFailsWith<IllegalArgumentException> {
            store.open(generated.copy(sha256 = "0".repeat(64)))
        }
    }

    @Test
    fun `손상되거나 부분 기록된 owned bundle도 recover 없이 discard한다`() {
        val root = File(filesDir, "generated-bundles").apply { mkdirs() }
        val corrupt = File(root, "$token.six").apply {
            writeText("partial bundle")
        }
        val store = store()

        assertFailsWith<GeneratedBundleStoreException> {
            store.recover(token)
        }
        assertTrue(store.discard(token))
        assertFalse(corrupt.exists())
        assertTrue(store.discard(token))
    }

    @Test
    fun `비정렬 v4 report는 package publish restart에서 canonical JSON identity를 유지한다`() {
        val original = result()
        val unsorted = original.compatibility.copy(
            declaredPermissions = listOf("z.permission", "a.permission"),
            usedApis = listOf("chrome.z", "chrome.a"),
        )
        val expectedJson = BundleJson.encodeCompatibility(unsorted)
        val first = store(
            verifier = GeneratedWrapperVerifier { _, _, report ->
                BundleJson.encodeCompatibility(report) == expectedJson
            },
        )

        first.publish(token, original.copy(compatibility = unsorted))
        val reopened = store().open(token).use(SixBundleContract::read)

        assertEquals(expectedJson, BundleJson.encodeCompatibility(reopened.compatibility))
        assertEquals(listOf("a.permission", "z.permission"), reopened.compatibility.declaredPermissions)
        assertEquals(listOf("chrome.a", "chrome.z"), reopened.compatibility.usedApis)
    }

    @Test
    fun `고압축 near-limit metadata는 publish restart에서도 STORED로 왕복한다`() {
        val original = result()
        val nearLimit = original.copy(
            descriptor = original.descriptor.copy(name = "E".repeat(900_000)),
            compatibility = original.compatibility.copy(
                extensionName = null,
                issues = listOf(
                    ContractIssue(
                        code = "near-limit",
                        message = "R".repeat(900_000),
                        blocking = false,
                    ),
                ),
            ),
        )

        val generated = store().publish(token, nearLimit)
        val reopened = store().open(token).use(SixBundleContract::read)

        assertEquals(nearLimit.descriptor, reopened.extension)
        assertEquals(nearLimit.compatibility, reopened.compatibility)
        ZipFile(generated.file).use { archive ->
            listOf(
                SixBundleContract.WRAPPER_ENTRY,
                SixBundleContract.EXTENSION_ENTRY,
                SixBundleContract.COMPATIBILITY_ENTRY,
            ).forEach { name ->
                val entry = requireNotNull(archive.getEntry(name))
                assertEquals(ZipEntry.STORED, entry.method, name)
                assertEquals(entry.size, entry.compressedSize, name)
            }
        }
    }

    @Test
    fun `copyTo는 검증된 전체 bundle을 bounded streaming하고 같은 hash를 반환한다`() {
        val store = store()
        val generated = store.publish(token, result())
        val output = ByteArrayOutputStream()

        val copiedSha256 = store.copyTo(token, output)

        assertEquals(generated.sha256, copiedSha256)
        assertContentEquals(generated.file.readBytes(), output.toByteArray())
    }

    @Test
    fun `게시 중 temporary partial write와 최종 symlink 교체를 거부한다`() {
        val partialStore = store(
            hooks = GeneratedBundleStoreHooks(
                afterBundleWritten = { file ->
                    file.outputStream().use { it.write(byteArrayOf(1, 2, 3)) }
                },
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            partialStore.publish(token, result())
        }
        assertFalse(bundleFile().exists())

        val target = File(filesDir, "symlink-target.six")
        val symlinkStore = store(
            hooks = GeneratedBundleStoreHooks(
                beforeFinalPublish = { output ->
                    Files.createSymbolicLink(output.toPath(), target.toPath())
                },
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            symlinkStore.publish(token, result())
        }
        assertFalse(target.exists())
    }

    @Test
    fun `검증 뒤 같은 inode size mtime 복원 변조를 publish하지 않는다`() {
        var originalTime: FileTime? = null
        val store = store(
            hooks = GeneratedBundleStoreHooks(
                afterBundleWritten = { originalTime = Files.getLastModifiedTime(it.toPath()) },
                afterBundleVerified = { file ->
                    val bytes = file.readBytes()
                    bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    file.writeBytes(bytes)
                    Files.setLastModifiedTime(file.toPath(), requireNotNull(originalTime))
                },
            ),
        )

        assertFailsWith<GeneratedBundleStoreException> {
            store.publish(token, result())
        }
        assertFalse(bundleFile().exists())
    }

    @Test
    fun `wrapper symlink와 hardlink를 publish source로 받지 않는다`() {
        val original = result()
        val symlink = File(filesDir, "wrapper-link.apk")
        Files.createSymbolicLink(symlink.toPath(), original.outputApk.toPath())
        assertFailsWith<IllegalArgumentException> {
            store().publish(token, original.copy(outputApk = symlink))
        }

        val hardlink = File(filesDir, "wrapper-hardlink.apk")
        Files.createLink(hardlink.toPath(), original.outputApk.toPath())
        assertFailsWith<IllegalArgumentException> {
            store().publish(UUID.randomUUID().toString(), original.copy(outputApk = hardlink))
        }
    }

    @Test
    fun `copy 중 published file 교체를 감지하고 hash를 반환하지 않는다`() {
        lateinit var published: File
        val store = store(
            hooks = GeneratedBundleStoreHooks(
                afterCopyChunk = {
                    val replacement = File(published.parentFile, ".replacement").apply {
                        writeBytes("replaced".encodeToByteArray())
                    }
                    Files.move(
                        replacement.toPath(),
                        published.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                },
            ),
        )
        published = store.publish(token, result()).file

        assertFailsWith<IllegalArgumentException> {
            store.copyTo(token, ByteArrayOutputStream())
        }
    }

    @Test
    fun `copy output cancellation은 store failure로 감싸지 않고 재전파한다`() {
        val store = store()
        store.publish(token, result())
        val cancellingOutput = object : java.io.OutputStream() {
            override fun write(value: Int) {
                throw CancellationException("cancel copy")
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                throw CancellationException("cancel copy")
            }
        }

        assertFailsWith<CancellationException> {
            store.copyTo(token, cancellingOutput)
        }
    }

    @Test
    fun `open은 게시 뒤 변조된 bundle hash를 거부한다`() {
        val store = store()
        val generated = store.publish(token, result())
        generated.file.setWritable(true)
        generated.file.writeText("tampered")

        assertFailsWith<IllegalArgumentException> {
            store.open(token)
        }
    }

    @Test
    fun `store 재생성 뒤 디스크 bundle을 재검증해 open copy discard한다`() {
        val first = store()
        val generated = first.publish(token, result())
        val discardToken = UUID.randomUUID().toString()
        val discardedAfterRestart = first.publish(discardToken, result("discard-after-restart.apk"))
        val recreated = store()
        val output = ByteArrayOutputStream()

        assertTrue(recreated.discard(discardToken))
        assertFalse(discardedAfterRestart.file.exists())
        val opened = recreated.open(token).use { it.readBytes() }
        val copiedSha256 = recreated.copyTo(token, output)

        assertContentEquals(generated.file.readBytes(), opened)
        assertContentEquals(generated.file.readBytes(), output.toByteArray())
        assertEquals(generated.sha256, copiedSha256)
        assertTrue(recreated.discard(token))
        assertFalse(generated.file.exists())
    }

    @Test
    fun `open 뒤 같은 inode size mtime 변조는 EOF와 close에서 hash로 감지한다`() {
        val store = store()
        val generated = store.publish(token, result())
        val originalTime = Files.getLastModifiedTime(generated.file.toPath())
        val stream = store.open(token)
        generated.file.setWritable(true)
        val bytes = generated.file.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        generated.file.writeBytes(bytes)
        Files.setLastModifiedTime(generated.file.toPath(), originalTime)

        assertFailsWith<IOException> {
            stream.use { it.readBytes() }
        }

        generated.file.writeBytes(bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() })
        Files.setLastModifiedTime(generated.file.toPath(), FileTime.fromMillis(originalTime.toMillis()))
        val partial = store.open(token)
        partial.read()
        generated.file.setWritable(true)
        val second = generated.file.readBytes()
        second[1] = (second[1].toInt() xor 1).toByte()
        generated.file.writeBytes(second)
        Files.setLastModifiedTime(generated.file.toPath(), originalTime)
        assertFailsWith<IOException> {
            partial.close()
        }
    }

    @Test
    fun `재생성 시 소유한 orphan temporary를 정리한다`() {
        store()
        val orphan = File(
            filesDir,
            "generated-bundles/.$token.interrupted.tmp",
        ).apply { writeText("partial") }
        val wrapperOrphan = File(
            filesDir,
            "generated-bundles/.wrapper.${UUID.randomUUID()}.apk",
        ).apply { writeText("partial wrapper") }

        store()

        assertFalse(orphan.exists())
        assertFalse(wrapperOrphan.exists())
    }

    @Test
    fun `압축 가능한 128 MiB wrapper를 STORED bundle publish restart discard까지 bounded heap으로 처리한다`() {
        val wrapper = File(filesDir, "large-wrapper.apk")
        wrapper.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            repeat(128 * 1024 * 1024 / buffer.size) {
                output.write(buffer)
            }
        }
        val largeResult = result(wrapper)
        System.gc()
        val baseline = heapUsed()
        var peak = baseline
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak = maxOf(peak, heapUsed())
                Thread.yield()
            }
        }.apply { start() }
        val largeToken = UUID.randomUUID().toString()

        val generated = try {
            val store = store()
            val published = store.publish(largeToken, largeResult)
            val restarted = store()
            val copied = restarted.copyTo(
                largeToken,
                object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                },
            )
            assertEquals(published.sha256, copied)
            assertTrue(restarted.discard(largeToken))
            published
        } finally {
            running.set(false)
            sampler.join()
        }

        assertFalse(generated.file.exists())
        assertTrue(peak - baseline < 96L * 1024 * 1024, "streaming heap 증가량: ${peak - baseline}")
    }

    @Test
    fun `discard는 private bundle을 제거하고 cleanup 실패를 성공으로 숨기지 않는다`() {
        val store = store()
        val generated = store.publish(token, result())

        assertTrue(store.discard(token))
        assertFalse(generated.file.exists())
        assertTrue(store.discard(token))
        assertFalse(store.discard("not-a-canonical-token"))

        val cleanupToken = UUID.randomUUID().toString()
        val failing = store(
            hooks = GeneratedBundleStoreHooks(
                deletePublishedFile = { false },
            ),
        )
        val retained = failing.publish(cleanupToken, result("retained.apk"))
        val error = assertFailsWith<GeneratedBundleStoreException> {
            failing.discard(cleanupToken)
        }
        assertEquals(GeneratedBundleStoreErrorCode.CLEANUP_FAILED, error.code)
        assertTrue(retained.file.exists())
    }

    @Test
    fun `startup은 24시간 지난 share snapshot만 bounded 정리한다`() {
        val root = File(filesDir, "generated-bundles").apply { mkdirs() }
        val shareRoot = File(root, "share-handoff").apply { mkdir() }
        val expired = List(34) {
            File(shareRoot, "share-${UUID.randomUUID()}.six").apply {
                writeText("expired-$it")
            }
        }
        val fresh = File(
            shareRoot,
            "share-11111111-1111-4111-8111-111111111113.six",
        ).apply { writeText("fresh") }
        val unrelated = File(shareRoot, "keep-expired.six").apply { writeText("keep") }
        val original = File(root, "$token.six").apply { writeText("original") }
        val expiredTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(30)
        expired.forEachIndexed { index, file ->
            Files.setLastModifiedTime(
                file.toPath(),
                FileTime.fromMillis(expiredTime + TimeUnit.MINUTES.toMillis(index.toLong())),
            )
        }
        listOf(unrelated, original).forEach {
            Files.setLastModifiedTime(it.toPath(), FileTime.fromMillis(expiredTime))
        }

        store()

        expired.take(32).forEach { assertFalse(it.exists()) }
        expired.takeLast(2).forEach { assertTrue(it.exists()) }
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        assertTrue(original.exists())
    }

    @Test
    fun `share snapshot은 verified original cleanup 뒤에도 exact bytes를 유지한다`() = runTest {
        val store = store()
        val generated = store.publish(token, result())

        val snapshot = requireNotNull(store.createShareSnapshot(token))
        val expected = generated.file.readBytes()
        assertEquals(
            File(filesDir, "generated-bundles/share-handoff").canonicalFile,
            requireNotNull(snapshot.parentFile).canonicalFile,
        )
        assertTrue(snapshot.name.startsWith("share-"))
        assertContentEquals(expected, snapshot.readBytes())

        assertTrue(store.discard(token))
        assertFalse(generated.file.exists())
        assertContentEquals(expected, snapshot.readBytes())
    }

    @Test
    fun `share snapshot final SHA 검증 실패는 temporary와 final을 모두 정리한다`() = runTest {
        val store = store(
            hooks = GeneratedBundleStoreHooks(
                afterShareSnapshotPublished = { snapshot ->
                    snapshot.writeText("tampered after atomic move")
                },
            ),
        )
        store.publish(token, result())

        assertEquals(null, store.createShareSnapshot(token))
        val shareRoot = File(filesDir, "generated-bundles/share-handoff")
        assertTrue(shareRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `discard는 손상된 exact owned token만 제거하고 symlink hardlink는 명시 오류로 거부한다`() {
        val corruptedStore = store()
        val corrupted = corruptedStore.publish(token, result()).file
        corrupted.setWritable(true)
        corrupted.writeText("corrupt")
        assertTrue(corruptedStore.discard(token))
        assertFalse(corrupted.exists())

        val symlinkToken = UUID.randomUUID().toString()
        val target = File(filesDir, "outside.six").apply { writeText("keep") }
        Files.createSymbolicLink(
            File(filesDir, "generated-bundles/$symlinkToken.six").toPath(),
            target.toPath(),
        )
        val symlinkError = assertFailsWith<GeneratedBundleStoreException> {
            store().discard(symlinkToken)
        }
        assertEquals(GeneratedBundleStoreErrorCode.INVALID_INPUT, symlinkError.code)
        assertEquals("keep", target.readText())

        val hardlinkToken = UUID.randomUUID().toString()
        Files.createLink(
            File(filesDir, "generated-bundles/$hardlinkToken.six").toPath(),
            target.toPath(),
        )
        val hardlinkError = assertFailsWith<GeneratedBundleStoreException> {
            store().discard(hardlinkToken)
        }
        assertEquals(GeneratedBundleStoreErrorCode.INVALID_INPUT, hardlinkError.code)
        assertEquals("keep", target.readText())
    }

    @Test
    fun `잘못된 token과 기존 output은 publish하지 않는다`() {
        val store = store()
        assertFailsWith<IllegalArgumentException> {
            store.publish("../escape", result())
        }
        requireNotNull(bundleFile().parentFile).mkdirs()
        bundleFile().writeText("keep")

        assertFailsWith<IllegalArgumentException> {
            store.publish(token, result("second.apk"))
        }
        assertEquals("keep", bundleFile().readText())
    }

    @Test
    fun `wrapper verifier 실패와 cleanup 실패를 모두 보고한다`() {
        val store = store(
            verifier = GeneratedWrapperVerifier { _, _, _ -> false },
            hooks = GeneratedBundleStoreHooks(deleteFile = { false }),
        )

        val error = assertFailsWith<GeneratedBundleStoreException> {
            store.publish(token, result())
        }
        assertEquals(GeneratedBundleStoreErrorCode.CLEANUP_FAILED, error.code)
    }

    private fun store(
        verifier: GeneratedWrapperVerifier = GeneratedWrapperVerifier { _, _, _ -> true },
        hooks: GeneratedBundleStoreHooks = GeneratedBundleStoreHooks(),
    ) = GeneratedBundleStore(
        filesDir = filesDir,
        wrapperVerifier = verifier,
        hooks = hooks,
    )

    private fun result(name: String = "wrapper.apk"): OnDeviceWrapperResult {
        val wrapper = File(filesDir, name).apply {
            writeBytes(byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3))
        }
        return result(wrapper)
    }

    private fun result(wrapper: File): OnDeviceWrapperResult {
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = extensionId,
            name = "SIX 테스트",
            version = "1.0",
            sourceKind = "ZIP",
            crxSha256 = "a".repeat(64),
            wrapperSha256 = sha256(wrapper),
            wrapperPackage = "dev.sixbridge.ext.$extensionId",
            wrapperVersionCode = 7,
            signerSha256 = "b".repeat(64),
        )
        val compatibility = ContractCompatibilityReport(
            schemaVersion = 4,
            status = ContractCompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = descriptor.name,
            extensionVersion = descriptor.version,
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = listOf("storage"),
            usedApis = listOf("chrome.storage.local"),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "converted",
            adapterDecisions = emptyList(),
            originalExtensionId = "bcdefghijklmnopabcdefghijklmnopa",
            convertedExtensionId = extensionId,
            sourceSha256 = "c".repeat(64),
            browserProfileSha256 = "d".repeat(64),
            originalSourceSha256 = "e".repeat(64),
            planSha256 = "f".repeat(64),
        )
        return OnDeviceWrapperResult(
            extensionId = extensionId,
            packageName = descriptor.wrapperPackage,
            versionCode = descriptor.wrapperVersionCode,
            signerSha256 = descriptor.signerSha256,
            crxSha256 = descriptor.crxSha256,
            wrapperSha256 = descriptor.wrapperSha256,
            descriptor = descriptor,
            compatibility = compatibility,
            outputApk = wrapper,
        )
    }

    private fun bundleFile() = File(filesDir, "generated-bundles/$token.six")

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun heapUsed(): Long =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
