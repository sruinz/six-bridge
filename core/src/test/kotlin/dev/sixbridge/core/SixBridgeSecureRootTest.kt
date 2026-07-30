package dev.sixbridge.core

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SixBridgeSecureRootTest {
    @Test
    fun `service 분석과 prepare의 모든 top level temp는 주입 provider를 사용한다`() {
        SecureFixture().use { fixture ->
            val service = SixBridgeService(
                workspaceRoot = fixture.workspace,
                secureConversionRoot = SecureConversionRoot.verify(fixture.root),
            )

            val analysis = service.analyzeResolvedDraft(fixture.resolved, profile)
            val prepared = preflight(service, fixture.resolved)

            assertEquals(CompatibilityStatus.COMPATIBLE, analysis.report.status)
            assertTrue(prepared is LogicalSourceIdentityResult.NeedsUserSelection)
            assertTrue(Files.list(fixture.root).use { it.findAny().isEmpty })
        }
    }

    private class SecureFixture : AutoCloseable {
        private val fileSystem = Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build(),
        )
        val root = Files.createDirectory(fileSystem.getPath("/secure-root"))
        val workspace = Files.createTempDirectory("six-secure-root-workspace")
        private val source = Files.createTempDirectory("six-secure-root-source").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Secure root","version":"1"}""",
            )
        }
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = source.toString(),
            sourceRoot = source,
        )
        override fun close() {
            fileSystem.close()
        }
    }

    private companion object {
        val profile = BrowserProfile(
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

        fun preflight(
            service: SixBridgeService,
            resolved: ResolvedExtensionInput,
        ): LogicalSourceIdentityResult {
            val method = SixBridgeService::class.java.getDeclaredMethod(
                "preflightAnalyze",
                ResolvedExtensionInput::class.java,
                BrowserProfile::class.java,
                Iterable::class.java,
                dev.sixbridge.core.compat.AnalysisControl::class.java,
            ).apply { isAccessible = true }
            return try {
                method.invoke(service, resolved, profile, emptyList<String>(),
                    dev.sixbridge.core.compat.AnalysisControl(),
                ) as LogicalSourceIdentityResult
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }
}
