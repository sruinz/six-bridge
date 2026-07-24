package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversionPipelineTest {
    @Test
    fun `same snapshot profile and approvals produce deterministic source plan and report hashes`() {
        val localRoot = Files.createTempDirectory("six-pipeline-local")
        val source = convertibleSource("six-pipeline-deterministic", includePopup = true)
        val resolved = resolvedSource(source)

        val first = ConversionPipeline(localRoot).use {
            it.prepare(
                resolved,
                profile(),
                listOf(
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                ),
            )
        }
        val second = ConversionPipeline(localRoot).use {
            it.prepare(
                resolved,
                profile(),
                linkedSetOf(
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                ),
            )
        }
        val independent = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-independent-local"),
        ).use {
            it.prepare(
                resolved,
                profile(),
                listOf(
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                ),
            )
        }

        assertContentEquals(first.sourceZip, second.sourceZip)
        assertFalse(first.sourceZip.contentEquals(independent.sourceZip))
        assertEquals(first.planSha256, second.planSha256)
        assertEquals(first.planSha256, independent.planSha256)
        assertEquals(first.reportSha256, second.reportSha256)
        assertNotEquals(first.extensionId, independent.extensionId)
        assertNotEquals(first.reportSha256, independent.reportSha256)
        assertEquals(first.extensionId, second.extensionId)
        assertEquals(ConversionState.CONVERTED, first.compatibilityAfter.conversionState)
        assertTrue(first.compatibilityAfter.adapterDecisions.all(AdapterDecision::applied))
    }

    @Test
    fun `verified passthrough CRX preserves original bytes proof and id`() {
        val localRoot = Files.createTempDirectory("six-pipeline-passthrough")
        val source = plainSource("six-pipeline-passthrough-source")
        val original = Crx3.pack(source, keyPair())
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "verified.crx",
            sourceRoot = source,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertContentEquals(original.bytes, result.crxBytes)
        assertEquals(original.extensionId, result.extensionId)
        assertEquals(original.extensionId, result.compatibilityAfter.originalExtensionId)
        assertEquals(original.extensionId, result.compatibilityAfter.convertedExtensionId)
        assertEquals(null, result.logicalIdentity)
        assertTrue(result.passthrough)
    }

    @Test
    fun `converted CRX gets stable profile identity new key and matching manifest key`() {
        val localRoot = Files.createTempDirectory("six-pipeline-store-key")
        val source = convertibleSource("six-pipeline-store-source")
        val original = Crx3.pack(source, keyPair())
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "store.crx",
            sourceRoot = source,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        val first = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }
        val second = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertEquals("store:${original.extensionId}:profile-30", first.logicalIdentity)
        assertNotEquals(original.extensionId, first.extensionId)
        assertEquals(first.extensionId, second.extensionId)
        val parsed = Crx3.parse(first.crxBytes)
        assertEquals(first.extensionId, parsed.extensionId)
        assertContentEquals(first.sourceZip, parsed.zipBytes)
        val extracted = Files.createTempDirectory("six-pipeline-packed")
        ExtensionZip.extract(parsed.zipBytes, extracted)
        val manifest = Json.parseToJsonElement(
            extracted.resolve("manifest.json").readText(),
        ) as JsonObject
        val publicKey = Base64.getDecoder().decode(
            (manifest["key"] as JsonPrimitive).content,
        )
        assertEquals(first.extensionId, Crx3.extensionId(publicKey))
        assertEquals(original.extensionId, first.compatibilityAfter.originalExtensionId)
        assertEquals(first.extensionId, first.compatibilityAfter.convertedExtensionId)
    }

    @Test
    fun `rejects a CRX whose verified payload does not match the supplied source snapshot`() {
        val localRoot = Files.createTempDirectory("six-pipeline-crx-binding")
        val firstSource = plainSource("six-pipeline-crx-first")
        val secondSource = convertibleSource("six-pipeline-crx-second")
        val original = Crx3.pack(firstSource, keyPair())
        val mismatched = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "mismatched.crx",
            sourceRoot = secondSource,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(localRoot).use {
                it.analyze(mismatched, profile())
            }
        }
        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(localRoot).use {
                it.prepare(mismatched, profile(), emptyList())
            }
        }
    }

    @Test
    fun `normal resolver CRX binds its verified payload to the canonical snapshot`() {
        val localRoot = Files.createTempDirectory("six-pipeline-crx-resolver")
        val source = plainSource("six-pipeline-crx-resolver-source")
        val packed = Crx3.pack(source, keyPair())
        val input = localRoot.resolve("normal.crx")
        Files.write(input, packed.bytes)
        val resolved = dev.sixbridge.core.input.ExtensionInputResolver()
            .resolve(input.toString())

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertContentEquals(packed.bytes, result.crxBytes)
        assertEquals(packed.extensionId, result.extensionId)
    }

    @Test
    fun `storage conversion report carries sealed applied proof`() {
        val source = Files.createTempDirectory("six-pipeline-storage").also { root ->
            root.resolve("worker.js").writeText(
                """chrome.storage.sync.get("theme").then(console.log);""",
            )
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Storage",
                  "version": "1",
                  "permissions": ["storage"],
                  "background": {"service_worker": "worker.js"}
                }
                """.trimIndent(),
            )
        }
        val storageProfile = profile().copy(
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
        )

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-storage-local"),
        ).use {
            it.prepare(resolvedSource(source), storageProfile, emptyList())
        }

        val decision = result.compatibilityAfter.adapterDecisions.single {
            it.id == MobilePatternAnalyzer.STORAGE_FALLBACK
        }
        assertTrue(decision.applied)
    }

    @Test
    fun `report hash is SHA-256 of final canonical compatibility bytes`() {
        val localRoot = Files.createTempDirectory("six-pipeline-report-hash")
        val result = ConversionPipeline(localRoot).use {
            it.prepare(
                resolvedSource(convertibleSource("six-pipeline-report-source")),
                profile(),
                emptyList(),
            )
        }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                dev.sixbridge.core.bundle.SixBundle
                    .encodeCompatibility(result.compatibilityAfter)
                    .toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, result.reportSha256)
    }

    @Test
    fun `missing unknown and inapplicable approvals have distinct failures`() {
        val localRoot = Files.createTempDirectory("six-pipeline-approval")
        val popup = resolvedSource(
            convertibleSource("six-pipeline-missing", includePopup = true),
        )
        val plain = resolvedSource(plainSource("six-pipeline-inapplicable"))

        val missing = assertFailsWith<MissingAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(popup, profile(), emptyList())
            }
        }
        val unknown = assertFailsWith<UnknownAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(popup, profile(), listOf("not-an-adapter"))
            }
        }
        val inapplicable = assertFailsWith<InapplicableAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(
                    plain,
                    profile(),
                    listOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
                )
            }
        }

        assertEquals(setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY), missing.adapterIds)
        assertEquals(setOf("not-an-adapter"), unknown.adapterIds)
        assertEquals(setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY), inapplicable.adapterIds)
    }

    @Test
    fun `converted report is reanalyzed without compatibility regression`() {
        val source = convertibleSource("six-pipeline-reanalysis")

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-reanalysis-local"),
        ).use {
            it.prepare(resolvedSource(source), profile(), emptyList())
        }

        assertEquals(CompatibilityStatus.COMPATIBLE, result.compatibilityBefore.status)
        assertEquals(CompatibilityStatus.COMPATIBLE, result.compatibilityAfter.status)
        assertTrue(result.compatibilityAfter.issues.none { it.blocking })
        val extracted = Files.createTempDirectory("six-pipeline-source-zip")
        ExtensionZip.extract(result.sourceZip, extracted)
        assertTrue(
            "_sixbridge/desktop-selection-event-bridge.v1.js" in
                extracted.resolve("manifest.json").readText(),
        )
    }

    private fun plainSource(prefix: String) =
        Files.createTempDirectory(prefix).also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Plain","version":"1"}""",
            )
        }

    private fun convertibleSource(prefix: String, includePopup: Boolean = false) =
        Files.createTempDirectory(prefix).also { root ->
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  toolbar.button.disabled = false;
                  const value = getSelection()?.toString();
                  if (value) console.log(value);
                });
                """.trimIndent(),
            )
            if (includePopup) root.resolve("popup.html").writeText("<p>popup</p>")
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Convertible",
                  "version": "1",
                  ${if (includePopup) "\"action\":{\"default_popup\":\"popup.html\"}," else ""}
                  "content_scripts": [{
                    "matches": ["https://example.com/*"],
                    "js": ["content.js"]
                  }]
                }
                """.trimIndent(),
            )
        }

    private fun resolvedSource(source: java.nio.file.Path) = ResolvedExtensionInput(
        kind = ExtensionInputKind.SOURCE_DIRECTORY,
        originalInput = source.toString(),
        sourceRoot = source,
    )

    private fun keyPair() = KeyPairGenerator.getInstance("RSA")
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
    )
}
