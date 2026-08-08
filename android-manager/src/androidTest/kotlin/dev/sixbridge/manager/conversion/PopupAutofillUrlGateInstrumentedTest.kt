package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.manager.app.toReview
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.GeneratedBundleStoreSource
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.PackageManagerInstalledWrapperRepository
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PopupAutofillUrlGateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun convertFixturesThroughProductionCoordinator() = runBlocking {
        val sessionId = requireNotNull(
            InstrumentationRegistry.getArguments().getString("sixbridgeSession"),
        ) {
            "sixbridgeSession instrumentation 인수가 필요합니다."
        }
        require(SESSION_ID.matches(sessionId)) {
            "sixbridgeSession 형식이 올바르지 않습니다."
        }
        val sessionsRoot = File(context.filesDir, "popup-autofill-url-gate/sessions")
            .canonicalFile
        val sessionRoot = File(sessionsRoot, sessionId).canonicalFile
        require(sessionRoot.parentFile == sessionsRoot && sessionRoot.isDirectory) {
            "popup/autofill URL gate session 경계가 올바르지 않습니다."
        }
        require(!Files.isSymbolicLink(sessionRoot.toPath())) {
            "popup/autofill URL gate session은 심볼릭 링크일 수 없습니다."
        }
        val inputRoot = File(sessionRoot, "input")
        val outputRoot = File(sessionRoot, "output")
        require(outputRoot.mkdir()) { "gate output을 새로 만들 수 없습니다." }
        val profileFile = requireInputFile(File(inputRoot, "profile.json"))
        require(profileFile.length() <= MAX_PROFILE_BYTES) {
            "profile 크기가 제한을 초과했습니다."
        }
        val profile = BrowserProfileJson.decode(profileFile.readText()).also {
            it.validate()
            it.canonicalSha256()
        }
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            FIXTURES.forEach { fixture ->
                convertFixture(
                    fixture = fixture,
                    source = requireInputFile(File(inputRoot, "${fixture.slug}.zip")),
                    outputRoot = outputRoot,
                    sessionRoot = sessionRoot,
                    database = database,
                    profile = profile,
                )
            }
        } finally {
            database.close()
        }
    }

    private suspend fun convertFixture(
        fixture: Fixture,
        source: File,
        outputRoot: File,
        sessionRoot: File,
        database: ManagerDatabase,
        profile: BrowserProfile,
    ) {
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val fixtureRoot = File(sessionRoot, "work/${fixture.slug}")
        require(fixtureRoot.mkdirs()) { "${fixture.slug} work root를 만들 수 없습니다." }
        val verifier = AndroidWrapperVerifier(
            AndroidArchiveInspector(context.packageManager),
            File(fixtureRoot, "verifier"),
        )
        val generatedStore = GeneratedBundleStore(
            filesDir = fixtureRoot,
            wrapperVerifier = GeneratedWrapperVerifier { wrapper, descriptor, report ->
                verifier.verify(wrapper, descriptor, report) is WrapperVerificationResult.Success
            },
        )
        val workStore = ConversionWorkStore(
            privateRoot = File(fixtureRoot, "conversion-work"),
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { input ->
                runCatching {
                    SixBundleContract.read(input)
                    true
                }.getOrDefault(false)
            },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val importer = BundleImportService(
            source = GeneratedBundleStoreSource(generatedStore),
            verifier = verifier,
            cacheDirectory = File(fixtureRoot, "import"),
        )
        val runtime = DefaultConversionRuntime()
        val coordinator = OnDeviceConversionCoordinator(
            input = OnDeviceInputService(
                privateRoot = File(fixtureRoot, "conversion-input"),
                safInputOpener = SafInputOpener { source.inputStream() },
            ),
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort { SamsungProfileSnapshot.Supported(profile) },
            runtime = runtime,
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort(
                PackageManagerInstalledWrapperRepository(context.packageManager)::find,
            ),
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(generatedStore),
            imports = ConversionGeneratedImportPort { generated ->
                when (val imported = importer.importGenerated(generated)) {
                    is ImportResult.Failure -> null
                    is ImportResult.Success -> imported.value.toReview()
                }
            },
        )
        var token: String? = null
        try {
            val analysis = coordinator.begin(OnDeviceInputRequest.Saf(android.net.Uri.EMPTY))
            token = when (analysis) {
                is ConversionBeginResult.AnalysisReady -> analysis.token
                is ConversionBeginResult.Failure -> analysis.token
            }
            check(analysis is ConversionBeginResult.AnalysisReady) {
                "${fixture.slug} ZIP 분석에 실패했습니다: $analysis"
            }
            assertTrue(
                "${fixture.slug} 분석에서 adapter 결정을 찾지 못했습니다.",
                analysis.report.adapterDecisions.any { it.id == fixture.adapterId },
            )
            val approvedAdapters = analysis.requiredApprovals + analysis.optionalApprovals
            assertTrue(
                "${fixture.slug} adapter가 승인 가능한 선택에 없습니다.",
                fixture.adapterId in approvedAdapters ||
                    fixture.adapterId in analysis.selectedAutomaticAdapters,
            )
            val built = coordinator.build(
                token = analysis.token,
                approvedAdapterIds = approvedAdapters,
                partialConfirmed = true,
                identitySelection = null,
            )
            check(built is ConversionBuildResult.BundleReady) {
                "${fixture.slug} on-device 변환에 실패했습니다: $built"
            }
            publishResult(fixture, built.generated, outputRoot, profile)
            check(coordinator.discard(analysis.token)) {
                "${fixture.slug} 변환 상태를 정리하지 못했습니다."
            }
            token = null
        } finally {
            token?.let { runCatching { coordinator.discard(it) } }
        }
    }

    private fun publishResult(
        fixture: Fixture,
        generated: GeneratedBundle,
        outputRoot: File,
        profile: BrowserProfile,
    ) {
        val sixPath = outputRoot.toPath().resolve("${fixture.slug}.six")
        Files.copy(generated.file.toPath(), sixPath)
        val sixBytes = Files.readAllBytes(sixPath)
        val contents = SixBundleContract.read(sixBytes.inputStream())
        val wrapperPath = outputRoot.toPath().resolve("${fixture.slug}-wrapper.apk")
        Files.write(wrapperPath, contents.wrapperApk, CREATE_NEW)
        val compatibility = contents.compatibility
        val decision = compatibility.adapterDecisions.singleOrNull {
            it.id == fixture.adapterId
        }
        assertTrue("${fixture.slug} adapter가 적용되지 않았습니다.", decision?.applied == true)
        assertEquals("converted", compatibility.conversionState)
        assertEquals(profile.canonicalSha256(), compatibility.browserProfileSha256)
        val resultPath = outputRoot.toPath().resolve("${fixture.slug}-result.json")
        Files.write(
            resultPath,
            buildJsonObject {
                put("schemaVersion", 1)
                put("slug", fixture.slug)
                put("adapterId", fixture.adapterId)
                put("adapterApplied", true)
                put("conversionState", compatibility.conversionState)
                put("profileSha256", requireNotNull(compatibility.browserProfileSha256))
                put("sourceSha256", requireNotNull(compatibility.sourceSha256))
                put("originalSourceSha256", requireNotNull(compatibility.originalSourceSha256))
                put("planSha256", requireNotNull(compatibility.planSha256))
                put("sixFile", sixPath.fileName.toString())
                put("sixSha256", sha256(sixBytes))
                put("wrapperFile", wrapperPath.fileName.toString())
                put("wrapperSha256", sha256(contents.wrapperApk))
                put("crxSha256", contents.extension.crxSha256)
                put("extensionId", contents.extension.extensionId)
                put("packageName", contents.extension.wrapperPackage)
                put("signerSha256", contents.extension.signerSha256)
                put("versionCode", contents.extension.wrapperVersionCode)
                put("appliedAdapterIds", buildJsonArray {
                    compatibility.adapterDecisions
                        .filter { it.applied }
                        .map { it.id }
                        .sorted()
                        .forEach(::add)
                })
            }.toString().encodeToByteArray(),
            CREATE_NEW,
        )
    }

    private fun requireInputFile(file: File): File {
        val canonical = file.canonicalFile
        require(canonical == file.absoluteFile && canonical.isFile) {
            "입력 파일이 올바르지 않습니다: ${file.name}"
        }
        require(!Files.isSymbolicLink(canonical.toPath())) {
            "입력 파일은 심볼릭 링크일 수 없습니다."
        }
        val attributes = Files.readAttributes(
            canonical.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile) { "입력은 일반 파일이어야 합니다." }
        return canonical
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val slug: String,
        val adapterId: String,
    )

    private companion object {
        const val MAX_PROFILE_BYTES = 8L * 1024 * 1024
        val SESSION_ID = Regex("[0-9]{8}-[0-9]{6}-[0-9]+")
        val FIXTURES = listOf(
            Fixture("popup-autofill", "popup-native-autofill-guard"),
            Fixture("browser-internal-url", "browser-internal-url-mapper"),
        )
    }
}
