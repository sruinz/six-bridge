package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SixBridgeServiceTest {
    @Test
    fun `analyze delegates to mobile conversion analysis`() {
        val workspace = Files.createTempDirectory("six-analyze-pipeline-workspace")
        val source = source("six-analyze-pipeline-source", "Mobile analysis")
        source.resolve("content.js").writeText(
            """
            document.addEventListener("mouseup", (event) => {
              toolbar.button.disabled = false;
              if (getSelection()?.toString()) console.log("selected");
            });
            """.trimIndent(),
        )
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Mobile analysis",
              "version": "1",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )

        val report = SixBridgeService(workspace)
            .analyze(source.toString(), supportedProfile())

        assertEquals(ConversionState.AUTOMATIC, report.conversionState)
        assertEquals(
            setOf("desktop-selection-event-bridge"),
            report.adapterDecisions.mapTo(mutableSetOf()) { it.id },
        )
    }

    @Test
    fun `build publishes no artifacts when required approval is missing`() {
        val workspace = Files.createTempDirectory("six-build-no-output-workspace")
        val source = source("six-build-no-output-source", "Missing approval")
        source.resolve("content.js").writeText("console.log('content');")
        source.resolve("popup.html").writeText("<p>popup</p>")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Missing approval",
              "version": "1",
              "action": {"default_popup": "popup.html"},
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )
        val service = SixBridgeService(workspace) {
            error("승인 검증 실패 뒤 toolchain을 조회하면 안 됩니다.")
        }

        assertFailsWith<dev.sixbridge.core.conversion.MissingAdapterApprovalException> {
            service.build(source.toString(), supportedProfile())
        }

        assertFalse(Files.exists(workspace.resolve("dist")))
    }

    @Test
    fun `analyze uses a validated snapshot instead of reading an unsafe source directly`() {
        val workspace = Files.createTempDirectory("six-analyze-snapshot-workspace")
        val source = source("six-analyze-snapshot-source", "Analyze snapshot")
        Files.createSymbolicLink(
            source.resolve("linked-manifest.json"),
            source.resolve("manifest.json"),
        )

        assertFailsWith<IllegalArgumentException> {
            SixBridgeService(workspace).analyze(source.toString(), supportedProfile())
        }
    }

    @Test
    fun `build rejects a group-writable source marker before snapshot permissions are lost`() {
        val workspace = Files.createTempDirectory("six-build-marker-permission-workspace")
        val source = source("six-build-marker-permission-source", "Unsafe marker")
        val marker = source.resolve(".sixbridge-project-id")
        marker.writeText("sixbridge-project-v1:${"d".repeat(32)}")
        Files.setPosixFilePermissions(
            marker,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_WRITE,
            ),
        )
        val service = SixBridgeService(workspace) {
            error("marker 검증 전에 toolchain을 조회하면 안 됩니다.")
        }

        val error = assertFailsWith<IllegalArgumentException> {
            service.build(source.toString(), supportedProfile())
        }

        assertTrue(error.message.orEmpty().contains("owner만 수정"))
    }

    @Test
    fun `uses sidecars without changing sources and preserves identity for known path or fingerprint`() {
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
        assertFalse(Files.exists(first.resolve(".sixbridge-project-id")))
        assertFalse(Files.exists(second.resolve(".sixbridge-project-id")))

        first.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Changed in place","version":"2"}""",
        )
        val changedInPlaceId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                first.toString(),
                first,
            ),
        )
        assertEquals(firstId, changedInPlaceId)

        val moved = first.parent.resolve("six-identity-moved-${UUID.randomUUID()}")
        Files.move(first, moved, StandardCopyOption.ATOMIC_MOVE)
        val movedId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                moved.toString(),
                moved,
            ),
        )
        assertEquals(firstId, movedId)

        val projectIdentity = pathIdentity(workspace, moved)
        val movedAndChanged = moved.parent.resolve("six-identity-moved-changed-${UUID.randomUUID()}")
        Files.move(moved, movedAndChanged, StandardCopyOption.ATOMIC_MOVE)
        movedAndChanged.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Moved and changed","version":"3"}""",
        )
        assertFailsWith<IllegalArgumentException> {
            prepare(
                service,
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    movedAndChanged.toString(),
                    movedAndChanged,
                ),
            )
        }
        val movedAndChangedId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                movedAndChanged.toString(),
                movedAndChanged,
                projectIdentity = projectIdentity,
            ),
        )
        assertEquals(firstId, movedAndChangedId)

        val firstZip = workspace.resolve("first-name.zip")
        ExtensionZip.create(movedAndChanged, firstZip)
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
                    movedAndChanged.toString(),
                    movedAndChanged,
                ),
            ),
        )
        ZipInputStream(packed.zipBytes.inputStream()).use { zip ->
            val names = generateSequence { zip.nextEntry }.map(ZipEntry::getName).toSet()
            assertTrue(".sixbridge-project-id" !in names)
        }
    }

    @Test
    fun `reads an existing marker without rewriting the source`() {
        val workspace = Files.createTempDirectory("six-legacy-marker-workspace")
        val source = source("six-legacy-marker-source", "Legacy marker")
        val marker = source.resolve(".sixbridge-project-id")
        val identity = "a".repeat(32)
        marker.writeText("sixbridge-project-v1:$identity")
        val before = Files.readAllBytes(marker)

        prepare(
            SixBridgeService(workspace),
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                source.toString(),
                source,
            ),
        )

        assertContentEquals(before, Files.readAllBytes(marker))
        assertEquals(identity, pathIdentity(workspace, source))
    }

    @Test
    fun `preserves the active-source conflict check for legacy path sidecars`() {
        val workspace = Files.createTempDirectory("six-legacy-sidecar-workspace")
        val activeSource = source("six-legacy-active-source", "Active legacy source")
        val duplicate = source("six-legacy-duplicate-source", "Legacy duplicate")
        val identity = "c".repeat(32)
        duplicate.resolve(".sixbridge-project-id")
            .writeText("sixbridge-project-v1:$identity")
        val legacyBinding = workspace.resolve(".local/source-identities/paths/$identity.txt")
        Files.createDirectories(legacyBinding.parent)
        Files.writeString(legacyBinding, activeSource.toRealPath().toString())
        Files.setPosixFilePermissions(
            legacyBinding,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )

        assertFailsWith<IllegalArgumentException> {
            prepare(
                SixBridgeService(workspace),
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    duplicate.toString(),
                    duplicate,
                ),
            )
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
    fun `requires explicit identity when a markerless zip moves and changes`() {
        val workspace = Files.createTempDirectory("six-zip-move-change-workspace")
        val source = source("six-zip-move-change-source", "ZIP before move")
        val originalZip = workspace.resolve("original.zip")
        markerlessZip(source, originalZip)
        val service = SixBridgeService(workspace)
        val resolver = ExtensionInputResolver()

        val originalId = prepare(service, resolver.resolve(originalZip.toString()))
        val projectIdentity = pathIdentity(workspace, originalZip)
        val movedZip = workspace.resolve("moved.zip")
        Files.move(originalZip, movedZip)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"ZIP after move","version":"2"}""",
        )
        markerlessZip(source, movedZip)

        val error = assertFailsWith<IllegalArgumentException> {
            prepare(service, resolver.resolve(movedZip.toString()))
        }
        assertTrue(error.message.orEmpty().contains("--identity"))

        val movedId = prepare(
            service,
            resolver.resolve(movedZip.toString()).copy(projectIdentity = projectIdentity),
        )
        assertEquals(originalId, movedId)
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
        val writableMarker = writable.resolve(".sixbridge-project-id")
        writableMarker.writeText("sixbridge-project-v1:${"b".repeat(32)}")
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
        duplicate.resolve(".sixbridge-project-id").writeText(
            "sixbridge-project-v1:${pathIdentity(workspace, first)}",
        )
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
        ExtensionKeyManager.create(
            trustedRoot = workspace,
            keyRoot = workspace.resolve(".local/keystore/crx"),
        )
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

    private fun supportedProfile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = BrowserProfile.BASELINE_VERSION,
        versionCode = 1_300_261_502,
        majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

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

    private fun pathIdentity(workspace: java.nio.file.Path, source: java.nio.file.Path): String {
        val canonical = source.toRealPath().toString()
        val key = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Files.readString(
            workspace.resolve(".local/source-identities/paths/$key.txt"),
        ).trim()
    }
}
