package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.SixBundleContract
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiProbeGateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun convertPortableDraftsThroughProductionCoordinator() = runBlocking {
        val sessionId = requireNotNull(
            InstrumentationRegistry.getArguments().getString("sixbridgeSession"),
        ) {
            "sixbridgeSession instrumentation 인수가 필요합니다."
        }
        require(SESSION_ID.matches(sessionId)) { "sixbridgeSession 형식이 올바르지 않습니다." }
        val sessionsRoot = File(context.filesDir, "api-probe-gate/sessions").canonicalFile
        val sessionRoot = File(sessionsRoot, sessionId).canonicalFile
        require(sessionRoot.parentFile == sessionsRoot && sessionRoot.isDirectory) {
            "API probe session 경계가 올바르지 않습니다."
        }
        require(!Files.isSymbolicLink(sessionRoot.toPath())) {
            "API probe session은 심볼릭 링크일 수 없습니다."
        }
        val inputRoot = File(sessionRoot, "input")
        val outputRoot = File(sessionRoot, "output")
        require(outputRoot.mkdir()) { "API probe output을 새로 만들 수 없습니다." }
        val profileFile = requireInputFile(File(inputRoot, "base-profile.json"))
        require(profileFile.length() <= MAX_PROFILE_BYTES) { "profile 크기가 제한을 초과했습니다." }
        val profile = BrowserProfileJson.decode(profileFile.readText()).also {
            it.validate()
            it.canonicalSha256()
        }

        val database = Room.databaseBuilder(
            context,
            ManagerDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            ManagerDatabase.MIGRATION_1_2,
            ManagerDatabase.MIGRATION_2_3,
            ManagerDatabase.MIGRATION_3_4,
        ).allowMainThreadQueries().build()
        try {
            groups.forEach { group ->
                convertGroup(
                    group = group,
                    draft = requireInputFile(File(inputRoot, "$group.sixdraft")),
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

    private suspend fun convertGroup(
        group: String,
        draft: File,
        outputRoot: File,
        sessionRoot: File,
        database: ManagerDatabase,
        profile: dev.sixbridge.core.profile.BrowserProfile,
    ) {
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val gateRoot = File(context.filesDir, "api-probe-gate")
        val verifierRoot = File(gateRoot, "verifier")
        val verifier = AndroidWrapperVerifier(
            AndroidArchiveInspector(context.packageManager),
            verifierRoot,
        )
        val generatedStore = GeneratedBundleStore(
            filesDir = gateRoot,
            wrapperVerifier = GeneratedWrapperVerifier { wrapper, descriptor, report ->
                verifier.verify(wrapper, descriptor, report) is WrapperVerificationResult.Success
            },
        )
        val workStore = ConversionWorkStore(
            privateRoot = File(gateRoot, "conversion-work"),
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { input ->
                runCatching { SixBundleContract.read(input); true }.getOrDefault(false)
            },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val importer = BundleImportService(
            source = GeneratedBundleStoreSource(generatedStore),
            verifier = verifier,
            cacheDirectory = File(sessionRoot, "work/$group/import"),
        )
        val coordinator = OnDeviceConversionCoordinator(
            input = OnDeviceInputService(
                privateRoot = File(gateRoot, "conversion-input"),
                safInputOpener = SafInputOpener { draft.inputStream() },
            ),
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort { SamsungProfileSnapshot.Supported(profile) },
            runtime = DefaultConversionRuntime(),
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
            coordinator.restore(importReview = false)?.let { restored ->
                val staleToken = when (restored) {
                    is RestoredConversion.Analysis -> restored.value.token
                    is RestoredConversion.BundleReady -> restored.generated.token
                    is RestoredConversion.GeneratedPending -> restored.generated.token
                    RestoredConversion.InterruptedDiscarded -> null
                    is RestoredConversion.InterruptedCleanupPending -> restored.token
                }
                staleToken?.let { check(coordinator.discard(it)) }
            }
            val analysis = coordinator.begin(OnDeviceInputRequest.Saf(android.net.Uri.EMPTY))
            token = analysis.cleanupToken()
            check(analysis is ConversionBeginResult.AnalysisReady) {
                "$group SixDraft 분석에 실패했습니다: $analysis"
            }
            val built = coordinator.build(
                token = analysis.token,
                approvedAdapterIds = analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = null,
            )
            check(built is ConversionBuildResult.BundleReady) {
                "$group SixDraft 변환에 실패했습니다: $built"
            }
            publishResult(group, built.generated, outputRoot)
            check(coordinator.discard(analysis.token)) {
                "$group 변환 상태를 다음 그룹 전에 정리하지 못했습니다."
            }
            token = null
        } finally {
            token?.let { runCatching { coordinator.discard(it) } }
        }
    }

    private fun publishResult(group: String, generated: GeneratedBundle, outputRoot: File) {
        val sixPath = outputRoot.toPath().resolve("$group.six")
        Files.copy(generated.file.toPath(), sixPath)
        val sixBytes = Files.readAllBytes(sixPath)
        val contents = SixBundleContract.read(sixBytes.inputStream())
        val wrapperPath = outputRoot.toPath().resolve("$group-wrapper.apk")
        Files.write(wrapperPath, contents.wrapperApk, CREATE_NEW)
        val resultPath = outputRoot.toPath().resolve("$group-result.json")
        val result = buildJsonObject {
            put("schemaVersion", 1)
            put("group", group)
            put("sixFile", sixPath.fileName.toString())
            put("sixSha256", sha256(sixBytes))
            put("wrapperFile", wrapperPath.fileName.toString())
            put("wrapperSha256", sha256(contents.wrapperApk))
            put("crxEntry", EMBEDDED_CRX_ENTRY)
            put("crxSha256", contents.extension.crxSha256)
            put("extensionId", contents.extension.extensionId)
            put("packageName", contents.extension.wrapperPackage)
            put("signerSha256", contents.extension.signerSha256)
            put("versionCode", contents.extension.wrapperVersionCode)
        }.toString().encodeToByteArray()
        Files.write(resultPath, result, CREATE_NEW)
    }

    private fun requireInputFile(file: File): File {
        val canonical = file.canonicalFile
        require(canonical == file.absoluteFile && canonical.isFile) { "입력 파일이 올바르지 않습니다: ${file.name}" }
        require(!Files.isSymbolicLink(canonical.toPath())) { "입력 파일은 심볼릭 링크일 수 없습니다." }
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

    private companion object {
        const val DATABASE_NAME = "api-probe-gate.db"
        const val EMBEDDED_CRX_ENTRY = "assets/extension.crx"
        const val MAX_PROFILE_BYTES = 8L * 1024 * 1024
        val SESSION_ID = Regex("[0-9]{8}-[0-9]{6}")
        val groups = listOf(
            "advanced-runtime",
            "base-runtime",
            "identity-privacy",
            "network",
            "tab-page",
            "user-interface",
        )
    }
}

@RunWith(AndroidJUnit4::class)
class ApiProbeGateCleanupInstrumentedTest {
    @Test
    fun failureTokenIsRetainedForCleanup() {
        val token = "82973536-1de1-4c96-ba35-a73c8152a4a0"

        assertEquals(
            token,
            ConversionBeginResult.Failure(
                ConversionFailureCode.DRAFT_INVALID,
                token,
            ).cleanupToken(),
        )
    }
}

private fun ConversionBeginResult.cleanupToken(): String? = when (this) {
    is ConversionBeginResult.AnalysisReady -> token
    is ConversionBeginResult.Failure -> token
}
