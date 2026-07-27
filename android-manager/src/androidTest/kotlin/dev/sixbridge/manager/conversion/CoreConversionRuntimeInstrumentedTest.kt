package dev.sixbridge.manager.conversion

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.DraftSelectedAdapter
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreConversionRuntimeInstrumentedTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val targetContext =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun prepareIsDeterministicOnAndroid() {
        val input = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            assetZip("ondevice-conversion"),
        )
        val first = ConversionPreparationEngine().use {
            it.prepare(input, profile(), emptySet())
        }
        val second = ConversionPreparationEngine().use {
            it.prepare(input, profile(), emptySet())
        }

        assertArrayEquals(first.transformedSourceZip, second.transformedSourceZip)
        assertEquals(first.transformedSourceSha256, second.transformedSourceSha256)
        assertEquals(first.planSha256, second.planSha256)
        assertEquals(
            setOf("desktop-selection-event-bridge"),
            first.appliedAdapterIds,
        )
        assertEquals(first.appliedAdapterIds, second.appliedAdapterIds)
        assertTrue("runtime" in first.compatibilityBefore.usedApis)

        reportMarker(
            "SIXBRIDGE_CORE_PREPARE_OK " +
                "sourceSha256=${first.transformedSourceSha256} " +
                "planSha256=${first.planSha256} " +
                "adapter=${first.appliedAdapterIds.single()}",
        )
    }

    @Test
    fun schemaV2Ed25519DraftReadAndReplayOnAndroid() {
        val sourceIdentity = "project:android-core-conversion-gate"
        val source = canonicalAssetZip("ondevice-conversion")
        val profile = profile()
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    kind = ExtensionInputKind.ZIP,
                    zipBytes = source,
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                emptySet(),
            )
        }
        assertEquals(sha256(source), prepared.originalSourceSha256)
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "android-runtime-gate",
                adapterVersions = selected.associate { it.id to it.version },
                sourceIdentity = sourceIdentity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = prepared.originalSourceSha256,
                browserProfileSha256 = prepared.browserProfileSha256,
                originalExtensionId = prepared.originalExtensionId,
                originalExtensionVersion = requireNotNull(
                    prepared.compatibilityBefore.extensionVersion,
                ),
                sourceIdentity = sourceIdentity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(
                prepared.compatibilityBefore,
                selected,
            ),
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        val signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val verified = SixDraft.read(SixDraft.createBytes(payload, signingKey))
        val replay = SixDraftReplay().replay(
            draft = verified,
            profile = profile,
            approvedAdapterIds = emptySet(),
        )
        assertTrue(replay is DraftReplayResult.Ready)
        val replayed = (replay as DraftReplayResult.Ready).prepared

        assertEquals(2, verified.payload.draft.schemaVersion)
        assertArrayEquals(signingKey.public.encoded, verified.signerPublicKey)
        assertEquals(sha256(signingKey.public.encoded), verified.signerFingerprint)
        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            replayed.transformedSourceSha256,
        )
        assertEquals(verified.payload.expected.planSha256, replayed.planSha256)
        assertEquals(
            setOf("desktop-selection-event-bridge"),
            replayed.appliedAdapterIds,
        )

        reportMarker(
            "SIXBRIDGE_DRAFT_REPLAY_OK " +
                "schemaVersion=${verified.payload.draft.schemaVersion} " +
                "signerFingerprint=${verified.signerFingerprint} " +
                "sourceSha256=${replayed.transformedSourceSha256} " +
                "planSha256=${replayed.planSha256} " +
                "adapter=${replayed.appliedAdapterIds.single()}",
        )
    }

    private fun assetZip(directory: String): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf("content.js", "manifest.json").forEach { name ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    testContext.assets.open("$directory/$name").use {
                        it.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun canonicalAssetZip(directory: String): ByteArray {
        val cacheRoot = targetContext.cacheDir.toPath()
        Files.createDirectories(cacheRoot)
        val root = Files.createTempDirectory(
            cacheRoot,
            "sixbridge-draft-source-",
        )
        return try {
            listOf("content.js", "manifest.json").forEach { name ->
                testContext.assets.open("$directory/$name").use { input ->
                    Files.copy(input, root.resolve(name))
                }
            }
            ExtensionZip.createBytes(root, includeProjectMarker = true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun reportMarker(marker: String) {
        println(marker)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", "$marker\n") },
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
}
