package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.CompatibilityForceScope
import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import dev.sixbridge.core.device.BuildArtifactVerifier
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.core.wrapper.AndroidToolchain
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
import java.util.zip.ZipFile
import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SixBridgeServiceTest {
    @Test
    fun `승인된 dynamic UNKNOWN build는 v6 provenance와 서명된 binding을 보존한다`() {
        val toolchain = requireNotNull(AndroidToolchain.locateOrNull())
        val workspace = Files.createTempDirectory("six-build-dynamic-approved-workspace")
        val source = Files.createDirectory(workspace.resolve("source"))
        source.resolve("content.js").writeText("chrome[namespace].sync.get('value');")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Dynamic approved","version":"1","content_scripts":[{"matches":["<all_urls>"],"js":["content.js"]}]}""",
        )
        val result = SixBridgeService(workspace) { toolchain }.build(
            source.toString(), supportedProfile(), outputBundle = workspace.resolve("approved.six"),
            authorization = ConversionAuthorization.forceUnknownApiUsage(),
        )
        val bundle = SixBundle.read(result.bundle)
        assertEquals(CompatibilityStatus.UNKNOWN, bundle.compatibility.status)
        assertEquals(6, bundle.compatibilitySchemaVersion)
        assertEquals(CompatibilityForceScope.UNKNOWN_API_USAGE, bundle.forcedCompatibility?.scope)
        ZipFile(result.wrapperApk.toFile()).use { apk ->
            assertEquals(
                bundle.compatibilityCanonicalSha256,
                apk.getInputStream(apk.getEntry(CompatibilityApkBinding.ENTRY_NAME)).readBytes().decodeToString(),
            )
        }
        assertEquals(CompatibilityStatus.UNKNOWN, result.forcedCompatibility?.originalStatus)
        assertEquals(listOf("api_usage_dynamic"), result.forcedCompatibility?.issueCodes)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(result.forcedCompatibility).issueCodes as MutableList<String>) += "mutated"
        }

        val tampered = workspace.resolve("tampered.six")
        ZipFile(result.bundle.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(tampered)).use { output ->
                zip.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    output.write(
                        if (entry.name == "compatibility.json") {
                            bytes.decodeToString().replace(
                                Regex("\\\"sourceSha256\\\":\\\"[0-9a-f]{64}\\\""),
                                "\"sourceSha256\":\"f" + "0".repeat(63) + "\"",
                            ).encodeToByteArray()
                        } else bytes,
                    )
                    output.closeEntry()
                }
            }
        }
        assertEquals(6, SixBundle.read(tampered).compatibilitySchemaVersion)
        val tamperError = assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(tampered, result.crx, result.wrapperApk)
        }
        assertTrue("compatibility digest" in tamperError.message.orEmpty())
    }

    @Test
    fun `승인 없는 dynamic UNKNOWN build는 어떤 산출물도 만들지 않는다`() {
        val workspace = Files.createTempDirectory("six-build-dynamic-deny-workspace")
        val source = Files.createDirectory(workspace.resolve("source"))
        val dist = Files.createDirectory(workspace.resolve("dist"))
        source.resolve("content.js").writeText("chrome[namespace].sync.get('value');")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Dynamic deny","version":"1","content_scripts":[{"matches":["<all_urls>"],"js":["content.js"]}]}""",
        )
        val service = SixBridgeService(workspace) {
            error("강제 승인 거부 뒤 toolchain을 조회하면 안 됩니다.")
        }

        val output = dist.resolve("dynamic.six")
        val failure = assertFailsWith<dev.sixbridge.core.conversion.ConversionUnavailableException> {
            service.build(source.toString(), supportedProfile(), output)
        }
        assertTrue(failure.message.orEmpty().isNotBlank())

        assertTrue(!Files.exists(dist.resolve("dynamic.crx")))
        assertTrue(!Files.exists(dist.resolve("dynamic-wrapper.apk")))
        assertTrue(!Files.exists(output))
    }

    @Test
    fun `draft 단계 경계는 작업 전후에 취소를 확인한다`() {
        var checkpoints = 0
        var operations = 0

        val value = runControlledDraftStage(
            AnalysisControl(AnalysisSafetyPolicy.desktop) { checkpoints++ },
        ) {
            operations++
            "done"
        }

        assertEquals("done", value)
        assertEquals(1, operations)
        assertEquals(2, checkpoints)

        operations = 0
        checkpoints = 0
        assertFailsWith<CancellationException> {
            runControlledDraftStage(
                AnalysisControl(AnalysisSafetyPolicy.desktop) {
                    checkpoints++
                    if (checkpoints == 2) throw CancellationException("after")
                },
            ) {
                operations++
            }
        }
        assertEquals(1, operations)
        assertEquals(2, checkpoints)
    }

    @Test
    fun `signer provider 경계는 전후 취소 확인과 주입 identity를 보존한다`() {
        val workspace = Files.createTempDirectory("six-draft-signer-boundary")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        var providerCalls = 0
        var checkpoints = 0
        val service = SixBridgeService(
            workspaceRoot = workspace,
            draftSigningIdentityProvider = DraftSigningIdentityProvider {
                providerCalls++
                pair
            },
        )

        val actual = service.signingIdentityForDraft(
            AnalysisControl(AnalysisSafetyPolicy.desktop) { checkpoints++ },
        )

        assertEquals(1, providerCalls)
        assertEquals(2, checkpoints)
        assertContentEquals(pair.public.encoded, actual.public.encoded)
        assertFalse(Files.exists(workspace.resolve(".local/keystore/draft-transfer")))

        providerCalls = 0
        checkpoints = 0
        assertFailsWith<CancellationException> {
            service.signingIdentityForDraft(
                AnalysisControl(AnalysisSafetyPolicy.desktop) {
                    checkpoints++
                    if (checkpoints == 2) throw CancellationException("after signer")
                },
            )
        }
        assertEquals(1, providerCalls)
        assertEquals(2, checkpoints)
        assertFalse(Files.exists(workspace.resolve(".local/keystore/draft-transfer")))
    }

    @Test
    fun `draft는 주입된 signer를 사용하고 별도 key store를 만들지 않는다`() {
        val workspace = Files.createTempDirectory("six-draft-signer-workspace")
        val source = source("six-draft-signer-source", "Injected signer")
        val output = Files.createDirectory(workspace.resolve("drafts"))
            .resolve("injected.sixdraft")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = SixBridgeService(
            workspaceRoot = workspace,
            draftSigningIdentityProvider = DraftSigningIdentityProvider { pair },
        )

        val result = service.draft(
            input = source.toString(),
            profile = supportedProfile(),
            outputDraft = output,
            projectIdentity = "a".repeat(32),
        )

        assertContentEquals(pair.public.encoded, SixDraft.read(result.draft).signerPublicKey)
        assertFalse(Files.exists(workspace.resolve(".local/keystore/draft-transfer")))
    }

    @Test
    fun `생성 SixDraft는 v0_1_1 core version을 기록한다`() {
        val workspace = Files.createTempDirectory("six-draft-core-version-workspace")
        val source = source("six-draft-core-version-source", "Core version")
        val output = Files.createDirectory(workspace.resolve("drafts"))
            .resolve("core-version.sixdraft")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val service = SixBridgeService(
            workspaceRoot = workspace,
            draftSigningIdentityProvider = DraftSigningIdentityProvider { pair },
        )

        val result = service.draft(
            input = source.toString(),
            profile = supportedProfile(),
            outputDraft = output,
            projectIdentity = "c".repeat(32),
        )

        assertEquals("0.1.1-SNAPSHOT", SixDraft.read(result.draft).payload.draft.coreVersion)
    }

    @Test
    fun `draft 분석은 logical identity 요구를 보고한다`() {
        val workspace = Files.createTempDirectory("six-draft-analysis-workspace")
        val source = source("six-draft-analysis-source", "Markerless draft")
        val markerless = workspace.resolve("markerless.zip")
        markerlessZip(source, markerless)

        val analysis = SixBridgeService(workspace).analyzeDraft(
            markerless.toString(),
            supportedProfile(),
        )

        assertIs<dev.sixbridge.core.conversion.LogicalSourceIdentityResult.NeedsUserSelection>(
            analysis.logicalSourceIdentity,
        )
    }

    @Test
    fun `draft 분석과 생성은 취소 신호를 전파한다`() {
        val workspace = Files.createTempDirectory("six-draft-cancel-workspace")
        val source = source("six-draft-cancel-source", "Cancelled draft")
        val output = Files.createDirectory(workspace.resolve("drafts"))
            .resolve("cancelled.sixdraft")
        val service = SixBridgeService(workspace)
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = source.toString(),
            sourceRoot = source,
        )
        val cancelled = AnalysisControl(AnalysisSafetyPolicy.desktop) {
            throw CancellationException("cancel")
        }

        assertFailsWith<CancellationException> {
            service.analyzeDraft(source.toString(), supportedProfile(), cancelled)
        }
        assertFailsWith<CancellationException> {
            service.draft(
                source.toString(),
                supportedProfile(),
                output,
                projectIdentity = "b".repeat(32),
                control = cancelled,
            )
        }
        assertFailsWith<CancellationException> {
            service.analyzeResolvedDraft(resolved, supportedProfile(), cancelled)
        }
        assertFailsWith<CancellationException> {
            service.draftResolved(
                resolved,
                supportedProfile(),
                output,
                projectIdentity = "b".repeat(32),
                control = cancelled,
            )
        }
    }

    @Test
    fun `build preserves output directories for every reserved internal bundle name`() {
        val toolchain = requireNotNull(AndroidToolchain.locateOrNull()) {
            "Android SDK 도구가 없어 공개 build 예약 이름 검증을 실행할 수 없습니다."
        }
        val workspace = Files.createTempDirectory("six-service-reserved-workspace")
        val source = source("six-service-reserved-source", "Reserved output")
        val service = SixBridgeService(workspace) { toolchain }
        val reservedNames = listOf(
            ".SIXBRIDGE-build-publisher.lock",
            ".SixBridge-publish-journal.v1",
            ".SIXbridge-publish-journal.tmp-${UUID.randomUUID()}",
            ".sixBRIDGE-publish-${UUID.randomUUID()}",
            ".SIXBRIDGE-publish-intent-${UUID.randomUUID()}",
            ".SixBridge-local-stage-${UUID.randomUUID()}",
            ".SIXbridge-local-backup-${UUID.randomUUID()}",
            ".sixBRIDGE-stage-owner-${UUID.randomUUID()}",
        )

        reservedNames.forEachIndexed { index, name ->
            val outputDirectory = Files.createTempDirectory(
                "six-service-reserved-output-$index",
            )
            val reserved = outputDirectory.resolve(name)
            val sentinel = Files.createDirectories(outputDirectory.resolve("user-directory"))
                .resolve("keep.txt")
            val reservedBytes = "reserved-$index".toByteArray()
            Files.write(reserved, reservedBytes)
            sentinel.writeText("keep")
            val reservedIdentity = fileIdentity(reserved)
            val directoryIdentity = fileIdentity(outputDirectory)
            val beforeNames = Files.list(outputDirectory).use { paths ->
                paths.map { it.fileName.toString() }.sorted().toList()
            }

            assertFailsWith<IllegalArgumentException>(name) {
                service.build(
                    input = source.toString(),
                    profile = supportedProfile(),
                    outputBundle = reserved,
                )
            }

            assertContentEquals(reservedBytes, Files.readAllBytes(reserved), name)
            assertEquals(reservedIdentity, fileIdentity(reserved), name)
            assertEquals(directoryIdentity, fileIdentity(outputDirectory), name)
            assertEquals("keep", Files.readString(sentinel), name)
            assertEquals(
                beforeNames,
                Files.list(outputDirectory).use { paths ->
                    paths.map { it.fileName.toString() }.sorted().toList()
                },
                name,
            )
        }
    }

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
        val outputParent = workspace.resolve("dist").also(Files::createDirectory)
        val source = source("six-build-no-output-source", "Missing approval")
        source.resolve("content.js").writeText("console.log('content');")
        source.resolve("popup.html").writeText(
            "<html><head></head><body><p>popup</p></body></html>",
        )
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

        assertTrue(
            Files.list(outputParent).use { paths -> paths.findAny().isEmpty },
        )
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
        Files.createDirectory(workspace.resolve("dist"))
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
        ExtensionKeyManager(
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
        supportedFeatures = setOf("extension.options-page.user-accessible"),
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

    private fun fileIdentity(path: java.nio.file.Path): Pair<Long, Long> {
        val attributes = Files.readAttributes(path, "unix:dev,ino")
        return (attributes.getValue("dev") as Number).toLong() to
            (attributes.getValue("ino") as Number).toLong()
    }
}
