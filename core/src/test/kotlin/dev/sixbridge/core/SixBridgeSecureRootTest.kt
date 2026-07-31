package dev.sixbridge.core

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.conversion.ConversionPipeline
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ChromeCrxDownloader
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SixBridgeSecureRootTest {
    @Test
    fun `명시적으로 주입한 resolver는 secure root 설정으로 교체하지 않는다`() {
        SecureFixture().use { fixture ->
            val secureRoot = SecureConversionRoot.verify(fixture.root)
            val packed = fixture.packedCrx()
            val resolver = ExtensionInputResolver(
                chromeDownloader = ChromeCrxDownloader { packed.bytes },
                extractionRootFactory = fixture.extractionRootFactory(secureRoot),
            )
            val service = SixBridgeService(
                workspaceRoot = fixture.workspace,
                inputResolver = resolver,
                secureConversionRoot = secureRoot,
            )

            assertEquals(
                CompatibilityStatus.COMPATIBLE,
                service.analyze(
                    "https://chromewebstore.google.com/detail/name/${packed.extensionId}",
                    profile,
                ).status,
            )
        }
    }

    @Test
    fun `service 기본 resolver는 secure root provider에서 ZIP과 CRX를 추출한다`() {
        SecureFixture().use { fixture ->
            val zip = fixture.archive("secure-root.zip")
            val crx = fixture.crx("secure-root.crx")
            val service = SixBridgeService(
                workspaceRoot = fixture.workspace,
                secureConversionRoot = SecureConversionRoot.verify(fixture.root),
            )

            assertEquals(CompatibilityStatus.COMPATIBLE, service.analyze(zip.toString(), profile).status)
            val zipRoots = fixture.secureRootChildren()
            assertEquals(1, zipRoots.size)
            assertTrue(Files.isRegularFile(zipRoots.single().resolve("manifest.json")))
            assertEquals(CompatibilityStatus.COMPATIBLE, service.analyze(crx.toString(), profile).status)
            val allRoots = fixture.secureRootChildren()
            assertEquals(2, allRoots.size)
            assertTrue(allRoots.all { root ->
                root.fileSystem.provider() === fixture.root.fileSystem.provider() &&
                    Files.isRegularFile(root.resolve("manifest.json"))
            })

            allRoots.forEach(fixture::deleteSecureTree)
            assertTrue(fixture.secureRootChildren().isEmpty())
        }
    }

    @Test
    fun `service 분석과 prepare의 모든 top level temp는 주입 provider를 사용한다`() {
        SecureFixture().use { fixture ->
            val observed = mutableListOf<java.nio.file.Path>()
            val secureRoot = SecureConversionRoot.verify(fixture.root)
            val service = SixBridgeService(
                workspaceRoot = fixture.workspace,
                secureConversionRoot = secureRoot,
            )

            val analysis = service.analyzeResolvedDraft(fixture.resolved, profile)
            val prepared = preflight(service, fixture.resolved)
            val crxInput = fixture.resolveCrx(secureRoot)
            ConversionPipeline.create(
                localRoot = fixture.workspace.resolve(".local"),
                compatibilityAnalyzer = CompatibilityAnalyzer(),
                tempTreeFactory = ConversionTempTreeFactory(secureRoot, observed::add),
            ).use { pipeline ->
                assertEquals(
                    CompatibilityStatus.COMPATIBLE,
                    pipeline.prepare(crxInput, profile, emptyList()).compatibilityAfter.status,
                )
            }

            assertEquals(CompatibilityStatus.COMPATIBLE, analysis.report.status)
            assertTrue(prepared is LogicalSourceIdentityResult.NeedsUserSelection)
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all {
                it.fileSystem.provider() === fixture.root.fileSystem.provider()
            })
            assertTrue(observed.none(Files::exists))
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

        fun archive(name: String) = workspace.resolve(name).also {
            ExtensionZip.create(source, it)
        }

        fun crx(name: String) = workspace.resolve(name).also {
            Files.write(
                it,
                packedCrx().bytes,
            )
        }

        fun packedCrx() = dev.sixbridge.core.crx.Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
        )

        fun extractionRootFactory(
            secureRoot: SecureConversionRoot,
        ): () -> java.nio.file.Path = {
            Files.createDirectory(
                secureRoot.path.resolve("resolved-${UUID.randomUUID()}"),
            )
        }

        fun resolveCrx(secureRoot: SecureConversionRoot): ResolvedExtensionInput {
            val resolver = ExtensionInputResolver(
                extractionRootFactory = extractionRootFactory(secureRoot),
            )
            return resolver.resolve(crx("prepare.crx").toString())
        }

        fun secureRootChildren(): List<java.nio.file.Path> =
            Files.list(root).use { children -> children.sorted().toList() }

        fun deleteSecureTree(path: java.nio.file.Path) {
            Files.walk(path).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }

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
