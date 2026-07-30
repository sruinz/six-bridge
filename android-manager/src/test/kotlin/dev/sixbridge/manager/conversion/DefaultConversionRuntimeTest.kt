package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.KeyPairCrx3SigningKey
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
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
