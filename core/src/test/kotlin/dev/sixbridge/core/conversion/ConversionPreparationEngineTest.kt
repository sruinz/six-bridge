package dev.sixbridge.core.conversion

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.bundle.toContractV4
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversionPreparationEngineTest {
    @Test
    fun `prepare 전체 분석 단계를 단조 순서로 한 번씩 전달한다`() {
        val stages = mutableListOf<AnalysisStage>()
        val control = AnalysisControl(onStage = stages::add)
        val operation = PreparationAnalysisOperation(control)

        repeat(2) {
            operation.pass { emitFinalizingStage ->
                assertFalse(emitFinalizingStage)
                control.stage(AnalysisStage.ARCHIVE_INVENTORY)
                control.stage(AnalysisStage.RUNTIME_CLOSURE)
                control.stage(AnalysisStage.API_SCAN)
                control.stage(AnalysisStage.MOBILE_PATTERNS)
            }
        }
        operation.complete(Unit)

        assertEquals(
            listOf(
                AnalysisStage.ARCHIVE_INVENTORY,
                AnalysisStage.RUNTIME_CLOSURE,
                AnalysisStage.API_SCAN,
                AnalysisStage.MOBILE_PATTERNS,
                AnalysisStage.FINALIZING,
            ),
            stages,
        )
    }

    @Test
    fun `준비 결과는 플랫폼 CRX 키와 무관하다`() {
        val input = PortableExtensionInput.fromZip(
            kind = ExtensionInputKind.ZIP,
            zipBytes = selectionFixtureZip(),
        )

        val first = ConversionPreparationEngine().use {
            it.prepare(input, profile(), emptySet())
        }
        val second = ConversionPreparationEngine().use {
            it.prepare(input, profile(), emptySet())
        }

        assertContentEquals(first.transformedSourceZip, second.transformedSourceZip)
        assertEquals(first.planSha256, second.planSha256)
        assertEquals(first.transformedSourceSha256, second.transformedSourceSha256)
        assertEquals(first.originalSourceSha256, second.originalSourceSha256)
    }

    @Test
    fun `준비 report hash는 원본 hash와 plan hash를 포함한 schema v4 계약이다`() {
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    selectionFixtureZip(),
                ),
                profile(),
                emptySet(),
            )
        }
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(
                BundleJson.encodeCompatibility(
                    prepared.compatibilityAfter.toContractV4(
                        originalSourceSha256 = prepared.originalSourceSha256,
                        planSha256 = prepared.planSha256,
                    ),
                ).toByteArray(),
            )
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, prepared.reportSha256)
    }

    @Test
    fun `portable input은 ZIP과 CRX 바이트를 방어적으로 복사한다`() {
        val zip = plainFixtureZip()
        val zipExpected = zip.copyOf()
        val zipInput = PortableExtensionInput.fromZip(ExtensionInputKind.ZIP, zip)
        zip.fill(0)
        val firstZip = zipInput.canonicalOriginalZip
        firstZip.fill(1)

        assertContentEquals(zipExpected, zipInput.canonicalOriginalZip)

        val packed = packedCrx()
        val crxExpected = packed.bytes.copyOf()
        val crxInput = PortableExtensionInput.fromCrx(
            ExtensionInputKind.CRX3,
            packed.bytes,
            packed.extensionId,
        )
        packed.bytes.fill(0)
        val firstCrx = requireNotNull(crxInput.originalCrx)
        firstCrx.fill(1)

        assertContentEquals(crxExpected, crxInput.originalCrx)
    }

    @Test
    fun `CRX expected ID가 서명 proof ID와 다르면 거부한다`() {
        val packed = packedCrx()

        assertFailsWith<IllegalArgumentException> {
            PortableExtensionInput.fromCrx(
                kind = ExtensionInputKind.CRX3,
                crxBytes = packed.bytes,
                expectedExtensionId = "a".repeat(32),
            )
        }
    }

    @Test
    fun `논리 원본 신원은 store project manifest 순서의 안정적 증거만 사용한다`() {
        val packed = packedCrx()
        val store = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromCrx(
                    ExtensionInputKind.CHROME_WEB_STORE,
                    packed.bytes,
                    packed.extensionId,
                ),
                profile(),
                emptySet(),
            )
        }
        assertEquals(
            "store:${packed.extensionId}",
            assertIs<LogicalSourceIdentityResult.Stable>(
                store.logicalSourceIdentity,
            ).value,
        )

        val marker = "0123456789abcdef0123456789abcdef"
        val project = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    plainFixtureZip(projectMarker = marker, manifestKey = keyPair().public.encoded),
                ),
                profile(),
                emptySet(),
            )
        }
        assertEquals(
            "project:$marker",
            assertIs<LogicalSourceIdentityResult.Stable>(
                project.logicalSourceIdentity,
            ).value,
        )

        val publicKey = keyPair().public.encoded
        val manifest = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    plainFixtureZip(manifestKey = publicKey),
                ),
                profile(),
                emptySet(),
            )
        }
        assertEquals(
            "manifest:${Crx3.extensionId(publicKey)}",
            assertIs<LogicalSourceIdentityResult.Stable>(
                manifest.logicalSourceIdentity,
            ).value,
        )
    }

    @Test
    fun `분석 전용 결과는 prepare 없이 동일한 논리 원본 신원을 반환한다`() {
        val packed = packedCrx()
        val storeInput = PortableExtensionInput.fromCrx(
            ExtensionInputKind.CHROME_WEB_STORE,
            packed.bytes,
            packed.extensionId,
        )
        val marker = "0123456789abcdef0123456789abcdef"
        val projectInput = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            plainFixtureZip(projectMarker = marker, manifestKey = keyPair().public.encoded),
        )
        val publicKey = keyPair().public.encoded
        val manifestInput = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            plainFixtureZip(manifestKey = publicKey),
        )

        ConversionPreparationEngine().use { engine ->
            val store = engine.analyzeWithIdentity(storeInput, profile(), AnalysisControl())
            val project = engine.analyzeWithIdentity(projectInput, profile(), AnalysisControl())
            val manifest = engine.analyzeWithIdentity(manifestInput, profile(), AnalysisControl())

            assertEquals(
                "store:${packed.extensionId}",
                assertIs<LogicalSourceIdentityResult.Stable>(store.logicalSourceIdentity).value,
            )
            assertEquals(
                "project:$marker",
                assertIs<LogicalSourceIdentityResult.Stable>(project.logicalSourceIdentity).value,
            )
            assertEquals(
                "manifest:${Crx3.extensionId(publicKey)}",
                assertIs<LogicalSourceIdentityResult.Stable>(manifest.logicalSourceIdentity).value,
            )
        }
    }

    @Test
    fun `CRX proof ID를 analyzeWithIdentity 보고서에 보존한다`() {
        val validCrx = packedCrx()
        val input = PortableExtensionInput.fromCrx(
            ExtensionInputKind.CRX3,
            validCrx.bytes,
            validCrx.extensionId,
        )

        val result = ConversionPreparationEngine().use {
            it.analyzeWithIdentity(input, profile())
        }

        assertEquals(validCrx.extensionId, result.report.originalExtensionId)
    }

    @Test
    fun `안정적 표식이 없는 ZIP은 신원을 자동 추정하지 않는다`() {
        val analyzed = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    plainFixtureZip(name = "Suggestive Project Name"),
                ),
                profile(),
                emptySet(),
            )
        }

        assertIs<LogicalSourceIdentityResult.NeedsUserSelection>(
            analyzed.logicalSourceIdentity,
        )
    }

    @Test
    fun `서명 검증된 SixDraft source identity를 기본 논리 신원으로 보존한다`() {
        val sourceIdentity = "project:fedcba9876543210fedcba9876543210"
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    kind = ExtensionInputKind.ZIP,
                    zipBytes = plainFixtureZip(),
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile(),
                emptySet(),
            )
        }

        val logical = assertIs<LogicalSourceIdentityResult.Stable>(
            prepared.logicalSourceIdentity,
        )
        assertEquals(sourceIdentity, logical.value)
        assertEquals(LogicalIdentityEvidence.SIGNED_DRAFT_METADATA, logical.evidence)
    }

    @Test
    fun `bindIdentity는 공개 키와 같은 최종 확장 ID를 manifest에 결합한다`() {
        val prepared = ConversionPreparationEngine().use { engine ->
            val prepared = engine.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    selectionFixtureZip(),
                ),
                profile(),
                emptySet(),
            )
            val key = keyPair()
            val bound = engine.bindIdentity(prepared, profile(), key.public.encoded)
            val extracted = Files.createTempDirectory("six-bound-test")
            ExtensionZip.extract(bound.sourceZip, extracted)
            val manifest = Json.parseToJsonElement(
                extracted.resolve("manifest.json").readText(),
            ) as JsonObject
            val manifestKey = Base64.getDecoder().decode(
                (manifest["key"] as JsonPrimitive).content,
            )

            assertEquals(Crx3.extensionId(key.public.encoded), bound.extensionId)
            assertEquals(bound.extensionId, Crx3.extensionId(manifestKey))
            assertEquals(bound.sourceSha256, bound.compatibility.sourceSha256)
            prepared
        }

        assertFalse(prepared.transformedSourceZip.isEmpty())
    }

    @Test
    fun `bindIdentity는 prepare와 다른 browser profile을 거부한다`() {
        val preparedProfile = profile()
        val bindingProfile = preparedProfile.copy(
            versionCode = preparedProfile.versionCode + 1,
        )

        ConversionPreparationEngine().use { engine ->
            val prepared = engine.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    plainFixtureZip(),
                ),
                preparedProfile,
                emptySet(),
            )

            assertFailsWith<IllegalArgumentException> {
                engine.bindIdentity(
                    prepared,
                    bindingProfile,
                    keyPair().public.encoded,
                )
            }
        }
    }

    @Test
    fun `passthrough는 원본 CRX 바이트 ZIP과 ID를 그대로 반환한다`() {
        val packed = packedCrx()
        ConversionPreparationEngine().use { engine ->
            val prepared = engine.prepare(
                PortableExtensionInput.fromCrx(
                    ExtensionInputKind.CRX3,
                    packed.bytes,
                    packed.extensionId,
                ),
                profile(),
                emptySet(),
            )
            val bound = engine.bindIdentity(prepared, profile(), null)

            assertContentEquals(packed.bytes, prepared.passthroughCrx)
            assertContentEquals(packed.zipBytes, bound.sourceZip)
            assertContentEquals(packed.bytes, bound.passthroughCrx)
            assertEquals(packed.extensionId, bound.extensionId)
        }
    }

    @Test
    fun `변환 CRX는 원본 서명 신원을 재사용하지 않는다`() {
        val source = Files.createTempDirectory("six-converted-crx").also { root ->
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  const value = getSelection()?.toString();
                  if (value) console.log(value);
                });
                """.trimIndent(),
            )
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Converted CRX",
                  "version": "1",
                  "content_scripts": [{
                    "matches": ["https://example.com/*"],
                    "js": ["content.js"]
                  }]
                }
                """.trimIndent(),
            )
        }
        val original = Crx3.pack(source, keyPair())

        ConversionPreparationEngine().use { engine ->
            val prepared = engine.prepare(
                PortableExtensionInput.fromCrx(
                    ExtensionInputKind.CRX3,
                    original.bytes,
                    original.extensionId,
                ),
                profile(),
                emptySet(),
            )
            val newKey = keyPair()
            val bound = engine.bindIdentity(prepared, profile(), newKey.public.encoded)

            assertEquals(null, prepared.passthroughCrx)
            assertNotEquals(original.extensionId, bound.extensionId)
            assertEquals(Crx3.extensionId(newKey.public.encoded), bound.extensionId)
        }
    }

    private fun selectionFixtureZip(): ByteArray {
        val root = Files.createTempDirectory("six-neutral-selection")
        root.resolve("content.js").writeText(
            """
            document.addEventListener("mouseup", (event) => {
              const value = getSelection()?.toString();
              if (value) console.log(value);
            });
            """.trimIndent(),
        )
        root.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Neutral",
              "version": "1",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )
        return ExtensionZip.createBytes(root, includeProjectMarker = true)
    }

    private fun plainFixtureZip(
        name: String = "Plain",
        projectMarker: String? = null,
        manifestKey: ByteArray? = null,
    ): ByteArray {
        val root = Files.createTempDirectory("six-neutral-plain")
        if (projectMarker != null) {
            root.resolve(".sixbridge-project-id").writeText(
                "sixbridge-project-v1:$projectMarker",
            )
        }
        val key = manifestKey?.let {
            ""","key":"${Base64.getEncoder().encodeToString(it)}""""
        }.orEmpty()
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"$name","version":"1"$key}""",
        )
        return ExtensionZip.createBytes(root, includeProjectMarker = true)
    }

    private fun packedCrx() = run {
        val root = Files.createTempDirectory("six-neutral-crx")
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"CRX","version":"1"}""",
        )
        Crx3.pack(root, keyPair())
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun profile() = BrowserProfile(
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
}
