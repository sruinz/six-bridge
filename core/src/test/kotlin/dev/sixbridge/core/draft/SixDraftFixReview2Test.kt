package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.WorkspacePermissionPolicy
import dev.sixbridge.core.input.ExtensionInputKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SixDraftFixReview2Test {
    @Test
    fun `passthrough binds before and after to the same actual source`() {
        val fixture = passthroughFixture()
        val staleSource = "1".repeat(64)
        val staleBefore = fixture.before.copy(sourceSha256 = staleSource)
        val staleInput = fixture.input.copy(originalSourceSha256 = staleSource)

        assertFailsWith<InvalidSixDraftException> {
            SixDraftPayload.create(
                draft = fixture.draft,
                input = staleInput,
                sourceKind = DraftSourceKind.ZIP,
                source = fixture.source,
                plan = DraftConversionPlan.create(staleBefore, emptyList()),
                compatibilityBefore = staleBefore,
                compatibilityAfter = fixture.after,
            )
        }
    }

    @Test
    fun `passthrough rejects report version permission API and issue drift`() {
        val fixture = passthroughFixture()
        val mutations = listOf<(CompatibilityReport) -> CompatibilityReport>(
            { it.copy(extensionVersion = "2.0.0") },
            { it.copy(declaredPermissions = it.declaredPermissions + "tabs") },
            { it.copy(usedApis = it.usedApis + "tabs.query") },
            {
                it.copy(
                    issues = it.issues +
                        CompatibilityIssue("new-limit", "새 제한", false),
                )
            },
            { it.copy(manifestVersion = 2) },
        )

        mutations.forEach { mutate ->
            assertFailsWith<InvalidSixDraftException> {
                SixDraftPayload.create(
                    fixture.draft,
                    fixture.input,
                    DraftSourceKind.ZIP,
                    fixture.source,
                    fixture.plan,
                    fixture.before,
                    mutate(fixture.after),
                )
            }
        }

        val alreadyConvertedBefore = fixture.before.copy(
            convertedExtensionId = "c".repeat(32),
        )
        assertFailsWith<InvalidSixDraftException> {
            SixDraftPayload.create(
                fixture.draft,
                fixture.input,
                DraftSourceKind.ZIP,
                fixture.source,
                DraftConversionPlan.create(alreadyConvertedBefore, emptyList()),
                alreadyConvertedBefore,
                fixture.after,
            )
        }
    }

    @Test
    fun `raw JSON depth and token limits fail before recursive parsing`() {
        val deep = buildString {
            append("{\"x\":")
            repeat(65) { append('[') }
            append('0')
            repeat(65) { append(']') }
            append('}')
        }.encodeToByteArray()
        val tooManyTokens = buildString {
            append("{\"x\":[")
            repeat(100_001) { index ->
                if (index > 0) append(',')
                append('0')
            }
            append("]}")
        }.encodeToByteArray()

        val depthError = assertFailsWith<IllegalArgumentException> {
            SixDraftJson.validateCanonicalDocument("deep.json", deep)
        }
        assertTrue(depthError.message.orEmpty().contains("depth"))
        val tokenError = assertFailsWith<IllegalArgumentException> {
            SixDraftJson.validateCanonicalDocument("tokens.json", tooManyTokens)
        }
        assertTrue(tokenError.message.orEmpty().contains("token"))
    }

    @Test
    fun `source entries must be STORED while bounded metadata may be DEFLATED`() {
        val fixture = passthroughFixture(
            source = java.util.Random(7).let { random ->
                ByteArray(8 * 1024).also(random::nextBytes)
            },
        )
        val entries = readEntries(
            SixDraft.createBytes(fixture.payload, fixture.signingKey),
        )

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(archive(entries, setOf("source.zip")))
        }
        val metadataDeflated = SixDraft.read(
            archive(entries, setOf("draft.json")),
        )
        assertContentEquals(fixture.source, metadataDeflated.payload.source)
    }

    @Test
    fun `typed writer rejects values that strict reader cannot decode`() {
        val fixture = passthroughFixture()
        val invalidPairs = listOf(
            fixture.before.copy(extensionName = "") to
                fixture.after.copy(extensionName = ""),
            fixture.before.copy(
                issues = listOf(CompatibilityIssue("", "설명", false)),
            ) to fixture.after.copy(
                issues = listOf(CompatibilityIssue("", "설명", false)),
            ),
            fixture.before.copy(
                issues = listOf(CompatibilityIssue("code", "", false)),
            ) to fixture.after.copy(
                issues = listOf(CompatibilityIssue("code", "", false)),
            ),
            fixture.before.copy(
                manifestKeyExtensionId = "z".repeat(32),
            ) to fixture.after.copy(
                manifestKeyExtensionId = "z".repeat(32),
            ),
        )

        invalidPairs.forEach { (before, after) ->
            assertFailsWith<IllegalArgumentException> {
                SixDraftPayload.create(
                    fixture.draft,
                    fixture.input,
                    DraftSourceKind.ZIP,
                    fixture.source,
                    DraftConversionPlan.create(before, emptyList()),
                    before,
                    after,
                )
            }
        }
    }

    @Test
    fun `typed writer output always roundtrips through strict reader`() {
        repeat(16) { index ->
            val fixture = passthroughFixture(
                extensionName = "확장-$index",
                extensionVersion = "1.$index.0",
            )
            val verified = SixDraft.read(
                SixDraft.createBytes(fixture.payload, fixture.signingKey),
            )
            assertEquals("확장-$index", verified.payload.compatibilityAfter.extensionName)
            assertEquals(
                "1.$index.0",
                verified.payload.compatibilityAfter.extensionVersion,
            )
        }
    }

    @Test
    fun `existing key root without lock is broken and remains unchanged`() {
        val trusted = Files.createTempDirectory("six-draft-existing-no-lock")
        val root = trusted.resolve("keys")
        Files.createDirectory(root)
        Files.setPosixFilePermissions(root, DIRECTORY_PERMISSIONS)
        val before = root.listDirectoryEntries()

        assertFailsWith<IllegalStateException> {
            DraftSigningKeyStore(trusted, root).getOrCreate()
        }

        assertEquals(before, root.listDirectoryEntries())
    }

    @Test
    fun `missing lock from a complete key store is not recreated`() {
        val trusted = Files.createTempDirectory("six-draft-missing-lock")
        val root = trusted.resolve("keys")
        val store = DraftSigningKeyStore(trusted, root)
        store.getOrCreate()
        val lock = root.resolve(".sixbridge-draft-key.lock")
        Files.delete(lock)
        val before = snapshot(root)

        assertFailsWith<IllegalStateException> {
            store.getOrCreate()
        }

        assertEquals(before.keys, snapshot(root).keys)
        assertFalse(Files.exists(lock))
    }

    @Test
    fun `crash partial state with an existing lock fails without mutation`() {
        val trusted = Files.createTempDirectory("six-draft-crash-partial")
        val root = trusted.resolve("keys")
        val store = DraftSigningKeyStore(trusted, root)
        store.getOrCreate()
        Files.delete(root.resolve("draft-transfer.identity"))
        val before = snapshot(root)

        assertFailsWith<IllegalStateException> {
            store.getOrCreate()
        }

        val after = snapshot(root)
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) ->
            assertContentEquals(bytes, after.getValue(name))
        }
    }

    @Test
    fun `partial producer is evaluated only after its existing OS lock`() {
        val trusted = Files.createTempDirectory("six-draft-producer-lock")
        val root = trusted.resolve("keys")
        val store = DraftSigningKeyStore(trusted, root)
        val expected = store.getOrCreate()
        val identity = root.resolve("draft-transfer.identity")
        val identityBytes = Files.readAllBytes(identity)
        Files.delete(identity)
        val lockPath = root.resolve(".sixbridge-draft-key.lock")
        val executor = Executors.newSingleThreadExecutor()
        val probeRoot = Files.createTempDirectory("six-draft-key-lock-probe")
        val ready = probeRoot.resolve("ready")
        val release = probeRoot.resolve("release")
        val process = startLockProbe(probeRoot, lockPath, ready, release)
        try {
            repeat(200) {
                if (!Files.exists(ready)) Thread.sleep(10)
            }
            assertTrue(Files.exists(ready))
            val future = executor.submit<KeyPair> { store.getOrCreate() }
            Thread.sleep(200)
            assertFalse(future.isDone)
            Files.write(identity, identityBytes)
            Files.setPosixFilePermissions(identity, FILE_PERMISSIONS)
            Files.writeString(release, "release")
            assertEquals(0, process.waitFor())
            val actual = future.get(10, TimeUnit.SECONDS)
            assertContentEquals(expected.public.encoded, actual.public.encoded)
        } finally {
            Files.writeString(release, "release")
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }

    @Test
    fun `access snapshots include owner permissions and ACL state`() {
        val trusted = Files.createTempDirectory("six-draft-access-state")
        val file = trusted.resolve("private.bin")
        Files.write(file, byteArrayOf(1))
        Files.setPosixFilePermissions(file, FILE_PERMISSIONS)

        val before = WorkspacePermissionPolicy.capturePrivateState(file, trusted)
        assertEquals(
            before,
            WorkspacePermissionPolicy.capturePrivateState(file, trusted),
        )

        val view = Files.getFileAttributeView(
            file,
            AclFileAttributeView::class.java,
        ) ?: return
        val owner = view.owner
        val principal = listOf("nobody", "daemon", "everyone")
            .asSequence()
            .mapNotNull {
                runCatching {
                    file.fileSystem.userPrincipalLookupService
                        .lookupPrincipalByName(it)
                }.getOrNull()
            }
            .firstOrNull { it != owner } ?: return
        val allow = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(AclEntryPermission.READ_DATA)
            .build()
        if (runCatching { view.acl = view.acl + allow }.isFailure) return

        assertTrue(before != WorkspacePermissionPolicy.captureState(file))
        assertFailsWith<IllegalStateException> {
            WorkspacePermissionPolicy.capturePrivateState(file, trusted)
        }
    }

    @Test
    fun `second pass rejects B bytes after file metadata is restored`() {
        val first = passthroughFixture(source = "stable-source-A".encodeToByteArray())
        val second = passthroughFixture(source = "stable-source-B".encodeToByteArray())
        val firstArchive = SixDraft.createBytes(first.payload, first.signingKey)
        val secondArchive = SixDraft.createBytes(second.payload, second.signingKey)
        assertEquals(firstArchive.size, secondArchive.size)
        val path = Files.createTempFile("six-draft-stable-path", ".sixdraft")
        Files.write(path, firstArchive)
        val modified = Files.getLastModifiedTime(path)
        val pathStateMethod = SixDraft::class.java.getDeclaredMethod(
            "pathState",
            Path::class.java,
        ).apply { isAccessible = true }
        val expectedState = pathStateMethod.invoke(SixDraft, path)
        val verifyMethod = SixDraft::class.java.declaredMethods.single {
            it.name == "verifyStablePathHash" && it.parameterCount == 3
        }.apply { isAccessible = true }

        Files.write(path, secondArchive)
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified.toMillis()))

        val failure = assertFailsWith<InvocationTargetException> {
            verifyMethod.invoke(
                SixDraft,
                path,
                expectedState,
                MessageDigest.getInstance("SHA-256").digest(firstArchive),
            )
        }
        assertTrue(failure.cause is IllegalArgumentException)
    }

    @Test
    fun `actual B path swap is rejected and A can be restored`() {
        val first = passthroughFixture(source = "swapped-source-A".encodeToByteArray())
        val second = passthroughFixture(source = "swapped-source-B".encodeToByteArray())
        val firstArchive = SixDraft.createBytes(first.payload, first.signingKey)
        val secondArchive = SixDraft.createBytes(second.payload, second.signingKey)
        assertEquals(firstArchive.size, secondArchive.size)
        val directory = Files.createTempDirectory("six-draft-path-swap")
        val path = directory.resolve("draft.sixdraft")
        val replacement = directory.resolve("replacement.sixdraft")
        val parked = directory.resolve("parked.sixdraft")
        Files.write(path, firstArchive)
        Files.write(replacement, secondArchive)
        val pathStateMethod = SixDraft::class.java.getDeclaredMethod(
            "pathState",
            Path::class.java,
        ).apply { isAccessible = true }
        val expectedState = pathStateMethod.invoke(SixDraft, path)
        val verifyMethod = SixDraft::class.java.declaredMethods.single {
            it.name == "verifyStablePathHash" && it.parameterCount == 3
        }.apply { isAccessible = true }

        Files.move(path, parked, ATOMIC_MOVE)
        Files.move(replacement, path, ATOMIC_MOVE)
        try {
            val failure = assertFailsWith<InvocationTargetException> {
                verifyMethod.invoke(
                    SixDraft,
                    path,
                    expectedState,
                    MessageDigest.getInstance("SHA-256").digest(firstArchive),
                )
            }
            assertTrue(failure.cause is IllegalArgumentException)
        } finally {
            Files.move(path, replacement, ATOMIC_MOVE)
            Files.move(parked, path, ATOMIC_MOVE)
        }
        assertContentEquals(first.source, SixDraft.read(path).payload.source)
    }

    private fun startLockProbe(
        root: Path,
        lock: Path,
        ready: Path,
        release: Path,
    ): Process {
        val source = root.resolve("LockProbe.java")
        Files.writeString(
            source,
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.*;
            import static java.nio.file.StandardOpenOption.WRITE;
            public class LockProbe {
              public static void main(String[] args) throws Exception {
                Path lock = Path.of(args[0]);
                Path ready = Path.of(args[1]);
                Path release = Path.of(args[2]);
                try (FileChannel channel = FileChannel.open(lock, WRITE);
                     var ignored = channel.lock()) {
                  Files.writeString(ready, "ready");
                  while (!Files.exists(release)) Thread.sleep(10);
                }
              }
            }
            """.trimIndent(),
        )
        val javaHome = Path.of(System.getProperty("java.home"))
        assertEquals(
            0,
            ProcessBuilder(javaHome.resolve("bin/javac").toString(), source.toString())
                .inheritIO()
                .start()
                .waitFor(),
        )
        return ProcessBuilder(
            javaHome.resolve("bin/java").toString(),
            "-cp",
            root.toString(),
            "LockProbe",
            lock.toString(),
            ready.toString(),
            release.toString(),
        ).inheritIO().start()
    }

    private fun passthroughFixture(
        source: ByteArray = "passthrough-source".encodeToByteArray(),
        extensionName: String = "Passthrough",
        extensionVersion: String = "1.0.0",
    ): Fixture {
        val sourceHash = sha256(source)
        val profileHash = "2".repeat(64)
        val extensionId = "b".repeat(32)
        val before = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = extensionName,
            extensionVersion = extensionVersion,
            manifestKeyPresent = true,
            manifestKeyExtensionId = extensionId,
            declaredPermissions = setOf("storage"),
            usedApis = setOf("storage.local"),
            unsupportedApis = emptySet(),
            issues = listOf(
                CompatibilityIssue("mobile-note", "모바일 검증 완료", false),
            ),
            conversionState = ConversionState.PASSTHROUGH,
            originalExtensionId = extensionId,
            sourceSha256 = sourceHash,
            browserProfileSha256 = profileHash,
        )
        val after = before.copy(convertedExtensionId = extensionId)
        val identity = "passthrough-fixture"
        val draft = DraftMetadata.create("0.2.0", emptyMap(), identity)
        val input = DraftInputMetadata(
            ExtensionInputKind.ZIP,
            sourceHash,
            profileHash,
            extensionId,
            extensionVersion,
            identity,
        )
        val plan = DraftConversionPlan.create(before, emptyList())
        val payload = SixDraftPayload.create(
            draft,
            input,
            DraftSourceKind.ZIP,
            source,
            plan,
            before,
            after,
        )
        return Fixture(
            source,
            draft,
            input,
            plan,
            before,
            after,
            payload,
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        )
    }

    private fun readEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun archive(
        entries: Map<String, ByteArray>,
        deflatedNames: Set<String>,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.toSortedMap().forEach { (name, bytes) ->
                val crc = CRC32().apply { update(bytes) }
                val deflated = name in deflatedNames
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        time = 0L
                        method = if (deflated) ZipEntry.DEFLATED else ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = if (deflated) {
                            rawDeflate(bytes).size.toLong()
                        } else {
                            size
                        }
                        this.crc = crc.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            ByteArrayOutputStream().also { output ->
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    output.write(buffer, 0, deflater.deflate(buffer))
                }
            }.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun snapshot(root: java.nio.file.Path): Map<String, ByteArray> =
        root.listDirectoryEntries().associate {
            it.fileName.toString() to Files.readAllBytes(it)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val source: ByteArray,
        val draft: DraftMetadata,
        val input: DraftInputMetadata,
        val plan: DraftConversionPlan,
        val before: CompatibilityReport,
        val after: CompatibilityReport,
        val payload: SixDraftPayload,
        val signingKey: KeyPair,
    )

    private companion object {
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val DIRECTORY_PERMISSIONS =
            FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
    }
}
