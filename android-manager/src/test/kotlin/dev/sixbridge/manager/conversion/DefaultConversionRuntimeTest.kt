package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.crx.KeyPairCrx3SigningKey
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultConversionRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Android runtime은 모바일 예산 초과를 UNKNOWN snapshot으로 보존한다`() {
        val input = crxFileWithScriptBytes(2 * 1024 * 1024 + 1)

        val snapshot = DefaultConversionRuntime().analyze(
            input = input,
            kind = OnDeviceInputKind.CRX3,
            profile = profile(),
            control = AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        assertEquals(CompatibilityStatus.UNKNOWN, snapshot.report.status)
        assertEquals("analysis_budget_exceeded", snapshot.report.issues.single().code)
    }

    @Test
    fun `Android runtime은 초과 CRX payload를 readBytes 전에 거부한다`() {
        val input = crxFileWithScriptBytes(2 * 1024 * 1024 + 1)
        var payloadReads = 0
        val runtime = DefaultConversionRuntime {
            payloadReads += 1
            it.readBytes()
        }

        val snapshot = runtime.analyze(
            input = input,
            kind = OnDeviceInputKind.CRX3,
            profile = profile(),
            control = AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        assertEquals(CompatibilityStatus.UNKNOWN, snapshot.report.status)
        assertEquals("analysis_budget_exceeded", snapshot.report.issues.single().code)
        assertEquals(0, payloadReads)
    }

    @Test
    fun `schema v3 prepared draft는 analyzer replay 없이 signed CRX를 bind한다`() {
        val input = preparedDraftFile()
        val runtime = DefaultConversionRuntime()
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile)

        val analysis = runtime.analyze(
            input,
            OnDeviceInputKind.SIX_DRAFT,
            profile(),
            control,
        )
        val prepared = runtime.prepare(
            input,
            OnDeviceInputKind.SIX_DRAFT,
            profile(),
            emptySet(),
            control,
        )
        val proof = assertNotNull(prepared.preparedCrx).proof
        val bound = runtime.bind(
            prepared,
            profile(),
            OnDevicePackageIdentity(
                extensionId = proof.extensionId,
                packageName = "dev.sixbridge.ext.${proof.extensionId}",
                wrapperSignerSha256 = "a".repeat(64),
                crxKeyAlias = null,
                crxPublicKeyDer = null,
            ),
        )

        assertEquals(InstallationIdentityMode.PREPARED, analysis.identityMode)
        assertEquals(InstallationIdentityMode.PREPARED, prepared.identityMode)
        assertEquals(proof.extensionId, bound.extensionId)
        assertEquals(proof.zipSha256, bound.compatibility.sourceSha256)
        assertNotNull(bound.preparedCrx)
        assertEquals(0, prepared.transformedSourceZip.size)
    }

    @Test
    fun `SixDraft 분석은 검증된 signer fingerprint를 UI snapshot까지 유지한다`() {
        val input = preparedDraftFile()
        val expected = SixDraft.read(input.toPath()).signerFingerprint
        val runtime = DefaultConversionRuntime()
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile)

        val analysis = runtime.analyze(
            input,
            OnDeviceInputKind.SIX_DRAFT,
            profile(),
            control,
        )
        assertEquals(expected, analysis.draftSignerFingerprint)
        assertEquals(64, analysis.draftSignerFingerprint?.length)
    }

    private fun crxFileWithScriptBytes(scriptBytes: Int): File {
        val zip = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { output ->
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(
                    """{"manifest_version":3,"name":"Large","version":"1"}"""
                        .encodeToByteArray(),
                )
                output.closeEntry()
                output.putNextEntry(ZipEntry("bundle.js"))
                output.write(ByteArray(scriptBytes).also(Random(1)::nextBytes))
                output.closeEntry()
            }
            bytes.toByteArray()
        }
        val keyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        return temporaryFolder.newFile("large.crx")
            .apply {
                writeBytes(
                    Crx3Writer.create(zip, KeyPairCrx3SigningKey(keyPair)).bytes,
                )
            }
    }

    private fun preparedDraftFile(): File {
        val sourceZip = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { output ->
                stored(
                    output,
                    "manifest.json",
                    """{"manifest_version":3,"name":"Prepared","version":"1"}"""
                        .encodeToByteArray(),
                )
                stored(output, "large.js", ByteArray(3 * 1024 * 1024))
            }
            bytes.toByteArray()
        }
        val rsa = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val crx = Crx3Writer.create(
            sourceZip,
            KeyPairCrx3SigningKey(rsa),
        ).bytes
        val proof = dev.sixbridge.contract.crx.Crx3Verifier.verify(crx)
        val profileSha = profile().canonicalSha256()
        val originalSha = "8".repeat(64)
        val before = compatibility(originalSha, profileSha, null)
        val after = compatibility(proof.zipSha256, profileSha, proof.extensionId)
        val identity = "project:0123456789abcdef0123456789abcdef"
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV3("test", emptyMap(), identity),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = originalSha,
                browserProfileSha256 = profileSha,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = identity,
            ),
            sourceKind = DraftSourceKind.PREPARED_CRX,
            source = crx,
            plan = DraftConversionPlan.create(before, emptyList()),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
        val transferKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return temporaryFolder.newFile("prepared.six").apply {
            writeBytes(SixDraft.createBytes(payload, transferKey))
        }
    }

    private fun compatibility(
        sourceSha: String,
        profileSha: String,
        convertedId: String?,
    ) = CompatibilityReport(
        status = CompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "Prepared",
        extensionVersion = "1",
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = emptyList(),
        conversionState = ConversionState.PASSTHROUGH,
        originalExtensionId = null,
        convertedExtensionId = convertedId,
        sourceSha256 = sourceSha,
        browserProfileSha256 = profileSha,
    )

    private fun stored(output: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        output.putNextEntry(
            ZipEntry(name).apply {
                time = 0L
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                this.crc = crc
            },
        )
        output.write(bytes)
        output.closeEntry()
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
    )
}
