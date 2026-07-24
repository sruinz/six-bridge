package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SixBridgeServiceTest {
    @Test
    fun `gives same-name sources distinct IDs and preserves identity across move and rename`() {
        val workspace = Files.createTempDirectory("six-identity-workspace")
        val first = source("six-identity-first", "__MSG_appName__")
        val second = source("six-identity-second", "__MSG_appName__")
        val service = SixBridgeService(workspace)

        val firstId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                first.toString(),
                first,
            ),
        )
        val secondId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                second.toString(),
                second,
            ),
        )
        assertNotEquals(firstId, secondId)

        val marker = first.resolve(".sixbridge-project-id")
        assertTrue(Files.isRegularFile(marker))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(marker),
        )
        val moved = first.parent.resolve("six-identity-moved-${UUID.randomUUID()}")
        Files.move(first, moved, StandardCopyOption.ATOMIC_MOVE)
        moved.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Renamed project","version":"2"}""",
        )
        val movedId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                moved.toString(),
                moved,
            ),
        )

        val firstZip = workspace.resolve("first-name.zip")
        ExtensionZip.create(moved, firstZip)
        val secondZip = workspace.resolve("renamed.zip")
        Files.move(firstZip, secondZip)
        val resolver = ExtensionInputResolver()
        val renamedZipId = prepare(service, resolver.resolve(secondZip.toString()))

        assertEquals(firstId, movedId)
        assertEquals(firstId, renamedZipId)
        val packed = Crx3.parse(
            packedBytes(
                service,
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    moved.toString(),
                    moved,
                ),
            ),
        )
        ZipInputStream(packed.zipBytes.inputStream()).use { zip ->
            val names = generateSequence { zip.nextEntry }.map(ZipEntry::getName).toSet()
            assertTrue(".sixbridge-project-id" !in names)
        }
    }

    @Test
    fun `keeps markerless zip identity by fingerprint and accepts explicit identity for updates`() {
        val workspace = Files.createTempDirectory("six-zip-identity-workspace")
        val source = source("six-markerless-zip", "ZIP identity")
        val firstZip = workspace.resolve("first.zip")
        markerlessZip(source, firstZip)
        val renamedZip = workspace.resolve("renamed.zip")
        Files.copy(firstZip, renamedZip)
        val service = SixBridgeService(workspace)
        val resolver = ExtensionInputResolver()

        val firstId = prepare(service, resolver.resolve(firstZip.toString()))
        val renamedId = prepare(service, resolver.resolve(renamedZip.toString()))
        val identity = Files.walk(workspace.resolve(".local/source-identities/fingerprints"))
            .use { paths ->
                paths.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow()
                    .toFile()
                    .readText()
                    .trim()
            }
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"ZIP identity update","version":"2"}""",
        )
        val updateZip = workspace.resolve("update.zip")
        markerlessZip(source, updateZip)
        val updatedId = prepare(
            service,
            resolver.resolve(updateZip.toString()).copy(projectIdentity = identity),
        )

        assertEquals(firstId, renamedId)
        assertEquals(firstId, updatedId)
    }

    @Test
    fun `rejects malformed duplicate and fingerprint-conflicting project identities`() {
        val workspace = Files.createTempDirectory("six-invalid-identity-workspace")
        val service = SixBridgeService(workspace)
        val malformed = source("six-malformed-identity", "Malformed")
        malformed.resolve(".sixbridge-project-id").writeText("not-an-identity")

        assertFailsWith<IllegalArgumentException> {
            prepare(
                service,
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    malformed.toString(),
                    malformed,
                ),
            )
        }

        val writable = source("six-writable-identity", "Writable")
        prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                writable.toString(),
                writable,
            ),
        )
        val writableMarker = writable.resolve(".sixbridge-project-id")
        Files.setPosixFilePermissions(
            writableMarker,
            Files.getPosixFilePermissions(writableMarker) +
                PosixFilePermission.GROUP_WRITE,
        )
        assertFailsWith<IllegalArgumentException> {
            prepare(
                service,
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    writable.toString(),
                    writable,
                ),
            )
        }

        val first = source("six-duplicate-identity", "Duplicate")
        prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                first.toString(),
                first,
            ),
        )
        val duplicate = Files.createTempDirectory("six-duplicate-copy")
        Files.walk(first).use { paths ->
            paths.forEach { path ->
                val target = duplicate.resolve(first.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            prepare(
                service,
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    duplicate.toString(),
                    duplicate,
                ),
            )
        }

        val markerless = source("six-fingerprint-conflict", "Fingerprint conflict")
        val zip = workspace.resolve("markerless.zip")
        markerlessZip(markerless, zip)
        val resolver = ExtensionInputResolver()
        prepare(service, resolver.resolve(zip.toString()))
        markerless.resolve(".sixbridge-project-id").writeText(
            "sixbridge-project-v1:${"a".repeat(32)}",
        )
        val conflictingZip = workspace.resolve("conflicting.zip")
        zipIncludingAll(markerless, conflictingZip)
        assertFailsWith<IllegalArgumentException> {
            prepare(service, resolver.resolve(conflictingZip.toString()))
        }
    }

    @Test
    fun `rejects a manifest key when its private key does not exist`() {
        val workspace = Files.createTempDirectory("six-keyless-workspace")
        val publicKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public
            .encoded
        val source = keyedSource("six-keyless-source", publicKey)

        assertFailsWith<IllegalArgumentException> {
            prepare(
                SixBridgeService(workspace),
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    source.toString(),
                    source,
                ),
            )
        }
    }

    @Test
    fun `uses the existing private key only when manifest key and packed id match`() {
        val workspace = Files.createTempDirectory("six-keyed-workspace")
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val extensionId = Crx3.extensionId(key.public.encoded)
        ExtensionKeyManager(workspace.resolve(".local/keystore/crx"))
            .persist(extensionId, key)
        val source = keyedSource("six-keyed-source", key.public.encoded)

        val packedId = prepare(
            SixBridgeService(workspace),
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                source.toString(),
                source,
            ),
        )

        assertEquals(extensionId, packedId)
    }

    private fun source(prefix: String, name: String) =
        Files.createTempDirectory(prefix).also { source ->
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"$name","version":"1"}""",
            )
        }

    private fun keyedSource(prefix: String, publicKey: ByteArray) =
        Files.createTempDirectory(prefix).also { source ->
            source.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Keyed",
                  "version": "1",
                  "key": "${Base64.getEncoder().encodeToString(publicKey)}"
                }
                """.trimIndent(),
            )
        }

    private fun prepare(
        service: SixBridgeService,
        resolved: ResolvedExtensionInput,
    ): String {
        val packed = invokePrepare(service, resolved)
        val field = packed.javaClass.getDeclaredField("extensionId")
            .apply { isAccessible = true }
        return field.get(packed) as String
    }

    private fun packedBytes(
        service: SixBridgeService,
        resolved: ResolvedExtensionInput,
    ): ByteArray {
        val packed = invokePrepare(service, resolved)
        val field = packed.javaClass.getDeclaredField("bytes")
            .apply { isAccessible = true }
        return field.get(packed) as ByteArray
    }

    private fun invokePrepare(
        service: SixBridgeService,
        resolved: ResolvedExtensionInput,
    ): Any {
        val method = SixBridgeService::class.java.getDeclaredMethod(
            "prepareCrx",
            ResolvedExtensionInput::class.java,
            java.nio.file.Path::class.java,
        ).apply { isAccessible = true }
        val packed = try {
            method.invoke(service, resolved, resolved.sourceRoot)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        return packed
    }

    private fun markerlessZip(source: java.nio.file.Path, output: java.nio.file.Path) {
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            Files.walk(source).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { it.fileName.toString() != ".sixbridge-project-id" }
                    .forEach { path ->
                        zip.putNextEntry(
                            ZipEntry(source.relativize(path).toString()).apply { time = 0L },
                        )
                        Files.copy(path, zip)
                        zip.closeEntry()
                    }
            }
        }
    }

    private fun zipIncludingAll(source: java.nio.file.Path, output: java.nio.file.Path) {
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            Files.walk(source).use { paths ->
                paths.filter(Files::isRegularFile).forEach { path ->
                    zip.putNextEntry(
                        ZipEntry(source.relativize(path).toString()).apply { time = 0L },
                    )
                    Files.copy(path, zip)
                    zip.closeEntry()
                }
            }
        }
    }
}
