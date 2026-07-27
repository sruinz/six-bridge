package dev.sixbridge.core

import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SixDraftCrxProvenanceTest {
    @Test
    fun `noncanonical passthrough CRX keeps signed payload provenance through draft replay`() {
        val fixture = noncanonicalCrx(converted = false)
        val verified = draft(fixture)
        val replayed = SixDraftReplay().replay(
            verified,
            profile(),
            emptySet(),
        ) as DraftReplayResult.Ready

        assertEquals(ConversionState.PASSTHROUGH, verified.payload.compatibilityAfter.conversionState)
        assertContentEquals(fixture.crxBytes, verified.payload.source)
        assertEquals(DraftSourceKind.CRX, verified.payload.sourceKind)
        assertEquals(fixture.extensionId, verified.payload.input.originalExtensionId)
        assertEquals(fixture.rawPayloadSha256, verified.payload.expected.originalSourceSha256)
        assertEquals(fixture.rawPayloadSha256, verified.payload.expected.transformedSourceSha256)
        assertEquals(fixture.rawPayloadSha256, replayed.prepared.originalSourceSha256)
        assertEquals(fixture.rawPayloadSha256, replayed.prepared.transformedSourceSha256)
        assertEquals(
            replayed.prepared.transformedSourceSha256,
            sha256(replayed.prepared.transformedSourceZip),
        )
        assertContentEquals(
            Crx3.parse(fixture.crxBytes).zipBytes,
            replayed.prepared.transformedSourceZip,
        )
        assertContentEquals(fixture.crxBytes, replayed.prepared.passthroughCrx)
    }

    @Test
    fun `noncanonical converted CRX keeps original signed payload provenance through draft replay`() {
        val fixture = noncanonicalCrx(converted = true)
        val verified = draft(fixture)
        val replayed = SixDraftReplay().replay(
            verified,
            profile(),
            emptySet(),
        ) as DraftReplayResult.Ready

        assertEquals(ConversionState.CONVERTED, verified.payload.compatibilityAfter.conversionState)
        assertContentEquals(fixture.crxBytes, verified.payload.source)
        assertEquals(DraftSourceKind.CRX, verified.payload.sourceKind)
        assertEquals(fixture.extensionId, verified.payload.input.originalExtensionId)
        assertEquals(fixture.rawPayloadSha256, verified.payload.expected.originalSourceSha256)
        assertEquals(fixture.rawPayloadSha256, replayed.prepared.originalSourceSha256)
        assertNotEquals(
            fixture.rawPayloadSha256,
            verified.payload.expected.transformedSourceSha256,
        )
        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            replayed.prepared.transformedSourceSha256,
        )
        assertTrue(replayed.prepared.passthroughCrx == null)
    }

    private fun draft(fixture: CrxFixture) =
        Files.createTempDirectory("six-draft-crx-provenance-workspace").let { workspace ->
            val input = workspace.resolve("input.crx")
            val output = workspace.resolve("transfer.sixdraft")
            Files.write(input, fixture.crxBytes)

            SixBridgeService(workspace).draft(
                input = input.toString(),
                profile = profile(),
                outputDraft = output,
            )

            SixDraft.read(output)
        }

    private fun noncanonicalCrx(converted: Boolean): CrxFixture {
        val manifest = """
            {
              "manifest_version": 3,
              "name": "Noncanonical CRX",
              "version": "1"${if (converted) """,
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]""" else ""}
            }
        """.trimIndent().toByteArray()
        val entries = buildList {
            if (converted) {
                add(
                    "content.js" to
                        """
                        document.addEventListener("mouseup", (event) => {
                          const value = getSelection()?.toString();
                          if (value) console.log(value);
                        });
                        """.trimIndent().toByteArray(),
                )
            }
            add("manifest.json" to manifest)
        }
        val rawZip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 1_700_000_000_000L
                            method = ZipEntry.DEFLATED
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val packed = Crx3.packCanonicalZip(rawZip, key)
        val parsed = Crx3.parse(packed.bytes)

        assertContentEquals(rawZip, parsed.zipBytes)
        assertTrue(parsed.signaturesVerified)
        return CrxFixture(
            crxBytes = packed.bytes,
            extensionId = packed.extensionId,
            rawPayloadSha256 = Crx3.verifiedPayloadSha256(packed.bytes),
        )
    }

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class CrxFixture(
        val crxBytes: ByteArray,
        val extensionId: String,
        val rawPayloadSha256: String,
    )
}
