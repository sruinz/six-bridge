package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.app.toReview
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.GeneratedBundleStoreSource
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.packager.OnDevicePackagerTestHooks
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.profile.AndroidSamsungBrowserProfileProvider
import dev.sixbridge.manager.profile.SamsungBrowserProfileResult
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.PackageManagerInstalledWrapperRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceConversionFlowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val evidenceRoot = File(context.filesDir, EVIDENCE_DIRECTORY)

    @Test
    fun generateInitialBundleFromSmartContextZip() {
        val result = runFullConversion("initial")

        assertTrue(result.versionCode >= 1)
        writeEvidence("initial-result.json", result)
    }

    @Test
    fun generateUpdateWithSameIdentityAndHigherVersion() {
        val initial = readEvidence("initial-result.json")
        val result = runFullConversion("update")

        assertEquals(initial.extensionId, result.extensionId)
        assertEquals(initial.packageName, result.packageName)
        assertEquals(initial.signerSha256, result.signerSha256)
        assertTrue(result.versionCode > initial.versionCode)
        writeEvidence("update-result.json", result)
    }

    @Test
    fun markerlessCreateNewReachesBundleReadyWithOneActiveTarget() = runBlocking {
        val fixture = MarkerlessFlowFixture(markerlessSmartContextZip())
        try {
            val analysis = fixture.begin()
            assertTrue(
                (analysis.identityRequirement as IdentityRequirement.UserSelectionRequired)
                    .existingTargets.isEmpty(),
            )

            val ready = fixture.build(
                analysis,
                InstallationIdentitySelection.CreateNew,
            )
            fixture.assertConvertedZipBundle(ready)

            val active = fixture.onlyIdentity()
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
            assertNull(active.provisionOwnerToken)
            assertEquals(active.lastReservedVersionCode, active.lastSuccessfulVersionCode)
            assertEquals(ready.review.wrapperVersionCode, active.lastSuccessfulVersionCode)
            assertNull(fixture.identityDao.getLeaseByOwnerToken(analysis.token))
            assertFalse(fixture.packager.outputFile(analysis.token).exists())
            assertEquals(setOf(requireNotNull(active.crxKeyAlias)), fixture.newCrxAliases())

            assertTrue(fixture.discardCurrent())
            val next = fixture.begin()
            val targets =
                (next.identityRequirement as IdentityRequirement.UserSelectionRequired)
                    .existingTargets
            assertEquals(1, targets.size)
            assertEquals(active.identitySha256, targets.single().identitySha256)
            assertEquals(active.extensionId, targets.single().extensionId)
            assertEquals(active.packageName, targets.single().packageName)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun packageFailureRetryKeepsOneIdentityAndOneTarget() = runBlocking {
        val fixture = MarkerlessFlowFixture(
            sourceZip = markerlessSmartContextZip(),
            failFirstPackage = true,
        )
        try {
            val analysis = fixture.begin()
            val failed = fixture.coordinator.build(
                token = analysis.token,
                approvedAdapterIds = analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = InstallationIdentitySelection.CreateNew,
            )
            check(failed is ConversionBuildResult.Failure)
            assertEquals(ConversionFailureCode.PACKAGE, failed.code)

            val provisional = fixture.onlyIdentity()
            assertEquals(
                ConversionIdentityLifecycleState.PROVISIONAL.name,
                provisional.lifecycleState,
            )
            assertEquals(analysis.token, provisional.provisionOwnerToken)
            assertEquals(1, provisional.lastReservedVersionCode)
            assertNull(fixture.identityDao.getLeaseByOwnerToken(analysis.token))
            assertEquals(
                setOf(requireNotNull(provisional.crxKeyAlias)),
                fixture.newCrxAliases(),
            )

            val ready = fixture.build(
                analysis,
                InstallationIdentitySelection.CreateNew,
            )
            fixture.assertConvertedZipBundle(ready)

            val active = fixture.onlyIdentity()
            assertEquals(provisional.identitySha256, active.identitySha256)
            assertEquals(provisional.crxKeyAlias, active.crxKeyAlias)
            assertTrue(active.lastReservedVersionCode > provisional.lastReservedVersionCode)
            assertEquals(ready.review.wrapperVersionCode, active.lastSuccessfulVersionCode)
            assertEquals(
                setOf(requireNotNull(active.crxKeyAlias)),
                fixture.newCrxAliases(),
            )
            assertNull(fixture.identityDao.getLeaseByOwnerToken(analysis.token))
            assertFalse(fixture.packager.outputFile(analysis.token).exists())

            assertTrue(fixture.discardCurrent())
            val next = fixture.begin()
            val targets =
                (next.identityRequirement as IdentityRequirement.UserSelectionRequired)
                    .existingTargets
            assertEquals(1, targets.size)
            assertEquals(active.identitySha256, targets.single().identitySha256)
        } finally {
            fixture.close()
        }
    }

    private fun runFullConversion(phase: String): FlowEvidence = runBlocking {
        evidenceRoot.mkdirs()
        check(evidenceRoot.isDirectory)
        val sourceZip = smartContextZip()
        val database = Room.databaseBuilder(
            context,
            ManagerDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            ManagerDatabase.MIGRATION_1_2,
            ManagerDatabase.MIGRATION_2_3,
            ManagerDatabase.MIGRATION_3_4,
            ManagerDatabase.MIGRATION_4_5,
        ).allowMainThreadQueries().build()
        val inputRoot = File(context.filesDir, INPUT_DIRECTORY)
        val workRoot = File(context.filesDir, WORK_DIRECTORY)
        val importRoot = File(context.cacheDir, IMPORT_DIRECTORY)
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val verifier = AndroidWrapperVerifier(
            AndroidArchiveInspector(context.packageManager),
            File(context.cacheDir, VERIFIER_DIRECTORY),
        )
        val generatedStore = GeneratedBundleStore(context)
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
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
            cacheDirectory = importRoot,
        )
        val inputService = OnDeviceInputService(
            privateRoot = inputRoot,
            safInputOpener = SafInputOpener { ByteArrayInputStream(sourceZip) },
        )
        val installed = PackageManagerInstalledWrapperRepository(context.packageManager)
        val profile = when (val loaded = AndroidSamsungBrowserProfileProvider(context).load()) {
            is SamsungBrowserProfileResult.Supported -> loaded.profile
            SamsungBrowserProfileResult.Missing -> error("삼성 인터넷 프로필이 없습니다.")
            is SamsungBrowserProfileResult.Unknown ->
                error("삼성 인터넷 프로필이 SUPPORTED가 아닙니다: ${loaded.reasons}")
        }
        val coordinator = OnDeviceConversionCoordinator(
            input = inputService,
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(profile)
            },
            runtime = DefaultConversionRuntime(),
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort(installed::find),
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
                    RestoredConversion.InterruptedDiscarded -> return@let
                    is RestoredConversion.InterruptedCleanupPending -> restored.token
                }
                check(coordinator.discard(staleToken)) {
                    "이전 Task 11 conversion work를 정리하지 못했습니다."
                }
            }
            val analysis = coordinator.begin(
                OnDeviceInputRequest.Saf(android.net.Uri.EMPTY),
            )
            check(analysis is ConversionBeginResult.AnalysisReady) {
                "스마트 컨텍스트 ZIP 분석에 실패했습니다: $analysis"
            }
            token = analysis.token
            val built = coordinator.build(
                token = analysis.token,
                approvedAdapterIds = analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = null,
            )
            check(built is ConversionBuildResult.BundleReady) {
                "스마트 컨텍스트 bundle 생성에 실패했습니다: $built"
            }
            createEvidence(phase, built.review, built.generated)
        } finally {
            token?.let { runCatching { coordinator.discard(it) } }
            database.close()
            importRoot.deleteRecursively()
        }
    }

    private fun createEvidence(
        phase: String,
        review: ReviewBundle,
        generated: GeneratedBundle,
    ): FlowEvidence {
        val sixFile = File(evidenceRoot, "$phase.six")
        Files.copy(
            generated.file.toPath(),
            sixFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val contents = sixFile.inputStream().use(SixBundleContract::read)
        val wrapperFile = File(evidenceRoot, "$phase-wrapper.apk")
        wrapperFile.writeBytes(contents.wrapperApk)
        val wrapperFacts = AndroidArchiveInspector(context.packageManager).inspect(wrapperFile)
        val extension = contents.extension
        val compatibility = contents.compatibility

        assertEquals(4, compatibility.schemaVersion)
        assertEquals(extension.extensionId, review.extensionId)
        assertEquals(extension.wrapperPackage, review.packageName)
        assertEquals(extension.signerSha256, review.signerSha256)
        assertEquals(extension.wrapperVersionCode, review.wrapperVersionCode)
        assertEquals(extension.crxSha256, sha256(wrapperFacts.crxBytes))
        assertEquals(extension.wrapperSha256, sha256(wrapperFile.readBytes()))
        assertEquals(generated.sha256, sha256(sixFile.readBytes()))

        return FlowEvidence(
            phase = phase,
            sourceSha256 = requireNotNull(compatibility.originalSourceSha256),
            resultSha256 = requireNotNull(compatibility.sourceSha256),
            planSha256 = requireNotNull(compatibility.planSha256),
            profileSha256 = requireNotNull(compatibility.browserProfileSha256),
            extensionId = extension.extensionId,
            packageName = extension.wrapperPackage,
            signerSha256 = extension.signerSha256,
            versionCode = extension.wrapperVersionCode,
            sixFile = sixFile.name,
            sixSha256 = generated.sha256,
            wrapperFile = wrapperFile.name,
            wrapperSha256 = extension.wrapperSha256,
            crxSha256 = extension.crxSha256,
        )
    }

    private inner class MarkerlessFlowFixture(
        sourceZip: ByteArray,
        failFirstPackage: Boolean = false,
    ) {
        private val inputRoot = File(
            context.filesDir,
            "markerless-input/${java.util.UUID.randomUUID()}",
        )
        private val workRoot = File(
            context.filesDir,
            "markerless-work/${java.util.UUID.randomUUID()}",
        )
        private val importRoot = File(
            context.cacheDir,
            "markerless-import/${java.util.UUID.randomUUID()}",
        )
        private val initialCrxAliases = productionCrxAliases()
        private var packageFailuresRemaining = if (failFirstPackage) 1 else 0
        private var currentToken: String? = null

        val database = Room.inMemoryDatabaseBuilder(
            context,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val identityDao = database.conversionIdentityDao()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                afterRequestValidated = {
                    if (packageFailuresRemaining > 0) {
                        packageFailuresRemaining -= 1
                        throw IllegalArgumentException("injected package failure")
                    }
                },
            ),
        )
        private val ledger = ConversionIdentityLedger(identityDao)
        private val generatedStore = GeneratedBundleStore(context)
        private val verifierRoot = File(context.cacheDir, "markerless-verifier").apply {
            mkdirs()
        }
        private val verifier = AndroidWrapperVerifier(
            AndroidArchiveInspector(context.packageManager),
            File(verifierRoot, java.util.UUID.randomUUID().toString()),
        )
        private val importer = BundleImportService(
            source = GeneratedBundleStoreSource(generatedStore),
            verifier = verifier,
            cacheDirectory = importRoot,
        )
        private val inputService = OnDeviceInputService(
            privateRoot = inputRoot,
            safInputOpener = SafInputOpener { ByteArrayInputStream(sourceZip) },
        )
        private val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { input ->
                runCatching {
                    SixBundleContract.read(input)
                    true
                }.getOrDefault(false)
            },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val coordinator = OnDeviceConversionCoordinator(
            input = inputService,
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(testProfile())
            },
            runtime = DefaultConversionRuntime(),
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort { null },
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(generatedStore),
            imports = DefaultConversionGeneratedImportPort(
                ConversionGeneratedImportPort { generated ->
                    when (val imported = importer.importGenerated(generated)) {
                        is ImportResult.Failure -> null
                        is ImportResult.Success -> imported.value.toReview()
                    }
                },
            ),
        )

        suspend fun begin(): ConversionBeginResult.AnalysisReady {
            val result = coordinator.begin(
                OnDeviceInputRequest.Saf(android.net.Uri.EMPTY),
            )
            check(result is ConversionBeginResult.AnalysisReady) {
                "markerless ZIP 분석에 실패했습니다: $result"
            }
            currentToken = result.token
            return result
        }

        suspend fun build(
            analysis: ConversionBeginResult.AnalysisReady,
            selection: InstallationIdentitySelection,
        ): ConversionBuildResult.BundleReady {
            val result = coordinator.build(
                token = analysis.token,
                approvedAdapterIds = analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = selection,
            )
            check(result is ConversionBuildResult.BundleReady) {
                "markerless ZIP bundle 생성에 실패했습니다: $result"
            }
            return result
        }

        suspend fun onlyIdentity(): ConversionIdentityEntity {
            val rows = identityDao.listByModeAndProfile(
                InstallationIdentityMode.CONVERTED.name,
                testProfile().majorVersion,
            )
            assertEquals(1, rows.size)
            return rows.single()
        }

        fun newCrxAliases(): Set<String> = productionCrxAliases() - initialCrxAliases

        fun assertConvertedZipBundle(ready: ConversionBuildResult.BundleReady) {
            val contents = ready.generated.file.inputStream().use(SixBundleContract::read)
            assertEquals("ZIP", contents.extension.sourceKind)
            assertEquals("converted", contents.compatibility.conversionState)
            assertEquals(ready.review.extensionId, contents.extension.extensionId)
            assertEquals(ready.review.wrapperVersionCode, contents.extension.wrapperVersionCode)
        }

        suspend fun discardCurrent(): Boolean {
            val token = currentToken ?: return true
            val discarded = coordinator.discard(token)
            if (discarded) currentToken = null
            return discarded
        }

        suspend fun close() {
            runCatching { discardCurrent() }
            val rows = runCatching {
                identityDao.listByModeAndProfile(
                    InstallationIdentityMode.CONVERTED.name,
                    testProfile().majorVersion,
                )
            }.getOrDefault(emptyList())
            rows.forEach { row ->
                val alias = row.crxKeyAlias ?: return@forEach
                runCatching {
                    packager.deleteUnreservedIdentity(
                        identitySha256 = row.identitySha256,
                        expectedAlias = alias,
                        expectedExtensionId = row.extensionId,
                        expectedPublicKeySha256 = row.crxPublicKeySha256,
                    )
                }
            }
            database.close()
            inputRoot.deleteRecursively()
            workRoot.deleteRecursively()
            importRoot.deleteRecursively()
            assertEquals(initialCrxAliases, productionCrxAliases())
        }
    }

    private fun markerlessSmartContextZip(): ByteArray {
        val sourceRoot = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "markerless-smart-context-",
        )
        return try {
            listOf("manifest.json", "content.js").forEach { name ->
                testContext.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                    Files.copy(input, sourceRoot.resolve(name))
                }
            }
            ExtensionZip.createBytes(sourceRoot, includeProjectMarker = false)
        } finally {
            sourceRoot.toFile().deleteRecursively()
        }
    }

    private fun productionCrxAliases(): Set<String> {
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases()
        return buildSet {
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (alias.startsWith("sixbridge.crx.rsa.v1.")) add(alias)
            }
        }
    }

    private fun testProfile() = BrowserProfile(
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

    private fun smartContextZip(): ByteArray {
        val sourceRoot = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "task11-smart-context-",
        )
        return try {
            listOf("manifest.json", "content.js").forEach { name ->
                testContext.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                    Files.copy(input, sourceRoot.resolve(name))
                }
            }
            Files.write(
                sourceRoot.resolve(".sixbridge-project-id"),
                "sixbridge-project-v1:$PROJECT_ID\n".encodeToByteArray(),
            )
            ExtensionZip.createBytes(sourceRoot, includeProjectMarker = true)
        } finally {
            sourceRoot.toFile().deleteRecursively()
        }
    }

    private fun writeEvidence(name: String, evidence: FlowEvidence) {
        File(evidenceRoot, name).writeText(
            buildJsonObject {
                put("schemaVersion", 1)
                put("phase", evidence.phase)
                put("sourceSha256", evidence.sourceSha256)
                put("resultSha256", evidence.resultSha256)
                put("planSha256", evidence.planSha256)
                put("profileSha256", evidence.profileSha256)
                put("extensionId", evidence.extensionId)
                put("packageName", evidence.packageName)
                put("signerSha256", evidence.signerSha256)
                put("versionCode", evidence.versionCode)
                put("sixFile", evidence.sixFile)
                put("sixSha256", evidence.sixSha256)
                put("wrapperFile", evidence.wrapperFile)
                put("wrapperSha256", evidence.wrapperSha256)
                put("crxSha256", evidence.crxSha256)
            }.toString(),
        )
    }

    private fun readEvidence(name: String): FlowEvidence {
        val value = Json.parseToJsonElement(
            File(evidenceRoot, name).readText(),
        ).jsonObject
        return FlowEvidence(
            phase = value.getValue("phase").jsonPrimitive.content,
            sourceSha256 = value.getValue("sourceSha256").jsonPrimitive.content,
            resultSha256 = value.getValue("resultSha256").jsonPrimitive.content,
            planSha256 = value.getValue("planSha256").jsonPrimitive.content,
            profileSha256 = value.getValue("profileSha256").jsonPrimitive.content,
            extensionId = value.getValue("extensionId").jsonPrimitive.content,
            packageName = value.getValue("packageName").jsonPrimitive.content,
            signerSha256 = value.getValue("signerSha256").jsonPrimitive.content,
            versionCode = value.getValue("versionCode").jsonPrimitive.int,
            sixFile = value.getValue("sixFile").jsonPrimitive.content,
            sixSha256 = value.getValue("sixSha256").jsonPrimitive.content,
            wrapperFile = value.getValue("wrapperFile").jsonPrimitive.content,
            wrapperSha256 = value.getValue("wrapperSha256").jsonPrimitive.content,
            crxSha256 = value.getValue("crxSha256").jsonPrimitive.content,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class FlowEvidence(
        val phase: String,
        val sourceSha256: String,
        val resultSha256: String,
        val planSha256: String,
        val profileSha256: String,
        val extensionId: String,
        val packageName: String,
        val signerSha256: String,
        val versionCode: Int,
        val sixFile: String,
        val sixSha256: String,
        val wrapperFile: String,
        val wrapperSha256: String,
        val crxSha256: String,
    )

    private companion object {
        const val EVIDENCE_DIRECTORY = "ondevice-conversion-flow"
        const val DATABASE_NAME = "task11-ondevice-conversion-flow.db"
        const val INPUT_DIRECTORY = "task11-ondevice-conversion-input"
        const val WORK_DIRECTORY = "task11-ondevice-conversion-work"
        const val IMPORT_DIRECTORY = "task11-ondevice-conversion-import"
        const val VERIFIER_DIRECTORY = "task11-ondevice-conversion-verifier"
        const val ASSET_DIRECTORY = "ondevice-conversion/smart-context"
        const val PROJECT_ID = "736d617274636f6e746578746d656e75"
    }
}
