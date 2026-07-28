package dev.sixbridge.manager.conversion

import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.app.RetainedImportStore
import dev.sixbridge.manager.app.toReview
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.GeneratedBundleStoreSource
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
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
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val sourceIdentity = "project:${"1".repeat(32)}"
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

        val draftFile = File(targetContext.cacheDir, "runtime-profile-change.six")
        draftFile.writeBytes(SixDraft.createBytes(payload, signingKey))
        try {
            val runtime = DefaultConversionRuntime()
            val same = runtime.analyze(
                draftFile,
                OnDeviceInputKind.SIX_DRAFT,
                profile,
            )
            assertFalse(same.profileChanged)
            assertEquals(InstallationIdentityMode.CONVERTED, same.identityMode)
            val samePrepared = runtime.prepare(
                draftFile,
                OnDeviceInputKind.SIX_DRAFT,
                profile,
                same.requiredApprovals,
            )
            assertEquals("ZIP", samePrepared.sourceKind)
            assertEquals(
                InstallationIdentityMode.CONVERTED,
                samePrepared.identityMode,
            )

            val changedProfile = profile.copy(versionCode = profile.versionCode + 1)
            val changed = runtime.analyze(
                draftFile,
                OnDeviceInputKind.SIX_DRAFT,
                changedProfile,
            )
            assertTrue(changed.profileChanged)
            assertEquals(InstallationIdentityMode.CONVERTED, changed.identityMode)
            assertEquals(
                changedProfile.canonicalSha256(),
                changed.report.browserProfileSha256,
            )
            val newlyApproved = runtime.prepare(
                draftFile,
                OnDeviceInputKind.SIX_DRAFT,
                changedProfile,
                changed.requiredApprovals,
            )
            assertEquals(
                changedProfile.canonicalSha256(),
                newlyApproved.reportBefore.browserProfileSha256,
            )
            assertEquals("ZIP", newlyApproved.sourceKind)
            assertEquals(
                InstallationIdentityMode.CONVERTED,
                newlyApproved.identityMode,
            )

            packageDraftThroughDefaultPorts(
                draftFile = draftFile,
                profile = profile,
            )
        } finally {
            draftFile.delete()
        }

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

    private fun packageDraftThroughDefaultPorts(
        draftFile: File,
        profile: BrowserProfile,
    ) {
        val database = Room.inMemoryDatabaseBuilder(
            targetContext,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val artifactToken = UUID.randomUUID().toString()
        val input = File(
            targetContext.filesDir,
            "draft-package-input/$artifactToken/input.bin",
        ).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(draftFile.readBytes())
        }
        val workRoot = File(
            targetContext.filesDir,
            "draft-package-work/${UUID.randomUUID()}",
        )
        val importCache = File(
            targetContext.cacheDir,
            "draft-package-import/${UUID.randomUUID()}",
        )
        val packager = OnDeviceWrapperPackager(targetContext)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val generatedStore = GeneratedBundleStore(targetContext)
        var failBeforeBundlePublish = true
        var failAfterBundleTransition = true
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { stream ->
                SixBundleContract.read(stream)
                true
            },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
            hooks = ConversionWorkStoreHooks(
                beforeBundleSourceOpen = {
                    if (failBeforeBundlePublish) {
                        failBeforeBundlePublish = false
                        error("simulated process death before bundle publish")
                    }
                },
                afterBundleTransition = {
                    if (failAfterBundleTransition) {
                        failAfterBundleTransition = false
                        error("simulated process death after BUNDLE_READY commit")
                    }
                },
            ),
        )
        val verifier = AndroidWrapperVerifier(
            AndroidArchiveInspector(targetContext.packageManager),
        )
        val importer = BundleImportService(
            source = GeneratedBundleStoreSource(generatedStore),
            verifier = verifier,
            cacheDirectory = importCache,
        )
        val retained = RetainedImportStore(File(importCache, "imports"), verifier)
        val artifact = OnDeviceInputArtifact(
            token = artifactToken,
            kind = OnDeviceInputKind.SIX_DRAFT,
            privateFile = input,
            sha256 = sha256(input.readBytes()),
            expectedExtensionId = null,
        )
        var importFailuresRemaining = 3
        fun coordinator() = OnDeviceConversionCoordinator(
            input = ConversionInputPort { artifact },
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(profile)
            },
            runtime = DefaultConversionRuntime(),
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort { null },
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(generatedStore),
            imports = ConversionGeneratedImportPort { generated ->
                if (importFailuresRemaining-- > 0) {
                    null
                } else {
                    when (val imported = importer.importGenerated(generated)) {
                        is ImportResult.Failure -> null
                        is ImportResult.Success -> {
                            if (retained.save(imported.value)) {
                                imported.value.toReview()
                            } else {
                                importer.discard(imported.value.token)
                                null
                            }
                        }
                    }
                }
            },
        )
        var conversionToken: String? = null
        try {
            kotlinx.coroutines.runBlocking {
                val firstCoordinator = coordinator()
                val analysis = firstCoordinator.begin(
                    OnDeviceInputRequest.Saf(
                        android.net.Uri.fromFile(draftFile),
                    ),
                )
                check(analysis is ConversionBeginResult.AnalysisReady)
                conversionToken = analysis.token
                val importFailed = firstCoordinator.build(
                    token = analysis.token,
                    approvedAdapterIds = analysis.requiredApprovals,
                    partialConfirmed = true,
                    identitySelection = null,
                )
                check(importFailed is ConversionBuildResult.Failure)
                assertEquals(ConversionFailureCode.BUNDLE_IO, importFailed.code)
                val building = requireNotNull(database.conversionJobDao().get())
                assertEquals(ConversionJobStage.BUILDING.name, building.stage)
                assertNotNull(
                    database.conversionIdentityDao().getLeaseByOwnerToken(analysis.token),
                )
                assertTrue(
                    File(
                        targetContext.filesDir,
                        "ondevice-packager-gate/${analysis.token}.apk",
                    ).isFile,
                )
                assertTrue(requireNotNull(importFailed.generated).file.isFile)

                val analyzed = firstCoordinator.analyze(analysis.token)
                check(analyzed is ConversionBeginResult.Failure)
                assertEquals(ConversionFailureCode.BUNDLE_IO, analyzed.code)
                assertEquals(
                    ConversionJobStage.BUILDING.name,
                    requireNotNull(database.conversionJobDao().get()).stage,
                )

                val rebuilt = firstCoordinator.build(
                    token = analysis.token,
                    approvedAdapterIds = analysis.requiredApprovals,
                    partialConfirmed = true,
                    identitySelection = null,
                )
                check(rebuilt is ConversionBuildResult.Failure)
                assertEquals(ConversionFailureCode.BUNDLE_IO, rebuilt.code)
                assertEquals(
                    ConversionJobStage.BUILDING.name,
                    requireNotNull(database.conversionJobDao().get()).stage,
                )

                assertTrue(
                    runCatching {
                        coordinator().build(
                            token = analysis.token,
                            approvedAdapterIds = analysis.requiredApprovals,
                            partialConfirmed = true,
                            identitySelection = null,
                        )
                    }.isFailure,
                )
                assertEquals(
                    ConversionJobStage.BUILDING.name,
                    requireNotNull(database.conversionJobDao().get()).stage,
                )
                assertNull(
                    database.conversionIdentityDao().getLeaseByOwnerToken(analysis.token),
                )

                assertTrue(
                    runCatching {
                        coordinator().build(
                            token = analysis.token,
                            approvedAdapterIds = analysis.requiredApprovals,
                            partialConfirmed = true,
                            identitySelection = null,
                        )
                    }.isFailure,
                )
                assertEquals(
                    ConversionJobStage.BUNDLE_READY.name,
                    requireNotNull(database.conversionJobDao().get()).stage,
                )
                val restored = coordinator().restore()
                check(restored is RestoredConversion.BundleReady)
                val ready = restored

                val row = requireNotNull(database.conversionJobDao().get())
                assertEquals(ConversionJobStage.BUNDLE_READY.name, row.stage)
                assertEquals(analysis.token, row.token)
                assertEquals(
                    ConversionJobStage.BUNDLE_READY,
                    requireNotNull(workStore.restorePreservingBuilding()).stage,
                )
                assertNull(
                    database.conversionIdentityDao().getLeaseByOwnerToken(analysis.token),
                )
                assertFalse(
                    File(
                        targetContext.filesDir,
                        "ondevice-packager-gate/${analysis.token}.apk",
                    ).exists(),
                )
                assertTrue(ready.generated.file.isFile)
                val contents =
                    ready.generated.file.inputStream().use(SixBundleContract::read)
                assertEquals("ZIP", contents.extension.sourceKind)
                assertTrue(
                    File(
                        workRoot,
                        "${analysis.token}/${requireNotNull(row.bundleFileName)}",
                    ).isFile,
                )
                val retainedReview = retained.load(ready.review.token)
                assertNotNull(retainedReview)
                assertTrue(requireNotNull(retainedReview).wrapperFile.isFile)
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                conversionToken?.let {
                    runCatching { ledger.abortByOwnerToken(it, packager) }
                    runCatching { coordinator().discard(it) }
                }
            }
            database.close()
            input.parentFile?.deleteRecursively()
            workRoot.deleteRecursively()
            importCache.deleteRecursively()
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
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
