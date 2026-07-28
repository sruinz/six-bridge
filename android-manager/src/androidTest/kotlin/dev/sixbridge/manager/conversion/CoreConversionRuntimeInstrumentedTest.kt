package dev.sixbridge.manager.conversion

import android.content.SharedPreferences
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
import dev.sixbridge.manager.install.FinalInstallReceipt
import dev.sixbridge.manager.install.FinalReceiptOrigin
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.RecoveryRecordSnapshot
import dev.sixbridge.manager.install.SharedPreferencesPendingInstallStore
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
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
    fun terminalImportTokenSchemaRoundTripsLegacyAndClear() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryPreferences = memorySharedPreferences()
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = File(testContext.packageCodePath, "unused-imports"),
        )
        val token = "11111111-1111-1111-1111-111111111111"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        assertTrue(
            pendingPreferences.edit()
                .putInt("session_id", 41)
                .putString("package_name", packageName)
                .putString("import_token", token)
                .putLong("started_at", 1)
                .commit(),
        )
        val pending = requireNotNull(store.load())
        assertTrue(
            store.recordTerminalResult(
                PackageInstallResult.Cancelled(41),
                terminalAtEpochMillis = 2,
            ),
        )

        val terminal = requireNotNull(store.loadTerminalResult())
        assertEquals(token, terminal.importToken)
        assertEquals(token, recoveryPreferences.getString("result_import_token", null))
        assertTrue(store.clearTerminalResult(terminal))
        assertFalse(recoveryPreferences.contains("result_import_token"))
        assertTrue(store.clear(pending))

        assertTrue(
            recoveryPreferences.edit()
                .putInt("result_session_id", 42)
                .putString("result_kind", "cancelled")
                .putString("result_package_name", packageName)
                .putLong("terminal_at", 3)
                .commit(),
        )
        val legacy = requireNotNull(store.loadTerminalResult())
        assertNull(legacy.importToken)
        assertTrue(store.clearTerminalResult(legacy))
        assertFalse(recoveryPreferences.contains("result_import_token"))
    }

    @Test
    fun finalReceiptAckFaultPreservesReceiptAndRetainedImportAcrossStoreRecreation() {
        val pendingControl = MemoryPreferenceControl()
        val pendingPreferences = memorySharedPreferences(pendingControl)
        val recoveryControl = MemoryPreferenceControl()
        val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val importRoot = Files.createTempDirectory("sixbridge-final-receipt").toFile()
        val token = "11111111-1111-1111-1111-111111111111"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        val retainedImport = File(importRoot, token).apply {
            mkdirs()
            File(this, "wrapper.apk").writeBytes(byteArrayOf(1, 2, 3))
        }
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            pendingPreferences.edit()
                .putInt("session_id", 41)
                .putString("package_name", packageName)
                .putString("import_token", token)
                .putLong("started_at", 1)
                .commit(),
        )
        val pending = requireNotNull(store.load())
        assertTrue(
            store.recordTerminalResult(
                PackageInstallResult.Success(41),
                terminalAtEpochMillis = 2,
            ),
        )
        val raw = requireNotNull(store.loadTerminalResult())
        val ready = FinalInstallReceipt(
            importToken = token,
            sessionId = 41,
            packageName = packageName,
            state = InstallState.Failed(
                "POST_INSTALL_VERIFICATION_FAILED",
                "설치된 래퍼를 검증할 수 없습니다.",
            ),
            stage = FinalReceiptStage.READY,
            origin = FinalReceiptOrigin.RAW_TERMINAL,
            rawTerminalAtEpochMillis = raw.terminalAtEpochMillis,
            terminalAtEpochMillis = 3,
        )

        assertTrue(store.createFinalReceipt(pending, raw, ready))
        pendingControl.failNextCommit = true
        assertFalse(store.clearPendingPreservingImport(pending))
        assertEquals(pending, store.load())
        assertEquals(ready, store.loadFinalReceipt())
        assertEquals(raw, store.loadTerminalResult())
        assertTrue(retainedImport.exists())
        assertTrue(store.clearPendingPreservingImport(pending))
        assertTrue(retainedImport.exists())
        recoveryControl.failNextCommit = true
        assertNull(store.acknowledgeFinalReceipt(ready, raw))
        assertEquals(ready, store.loadFinalReceipt())
        assertTrue(retainedImport.exists())

        val recreated = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        val acknowledged = requireNotNull(recreated.acknowledgeFinalReceipt(ready, raw))
        assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged.stage)
        recoveryControl.failNextCommit = true
        assertFalse(recreated.finalizeFinalReceipt(acknowledged))
        assertEquals(acknowledged, recreated.loadFinalReceipt())
        assertEquals(raw, recreated.loadTerminalResult())
        assertTrue(retainedImport.exists())
        assertTrue(recreated.finalizeFinalReceipt(acknowledged))
        assertNull(recreated.loadFinalReceipt())
        assertNull(recreated.loadTerminalResult())
        assertTrue(retainedImport.exists())
        importRoot.deleteRecursively()
    }

    @Test
    fun programCancelReadyAcceptsExactLateRawAfterPendingClearAndReplacesProvenance() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryControl = MemoryPreferenceControl()
        val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val importRoot = Files.createTempDirectory("sixbridge-late-raw").toFile()
        val token = "11111111-1111-1111-1111-111111111111"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        val retainedImport = File(importRoot, token).apply { mkdirs() }
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            pendingPreferences.edit()
                .putInt("session_id", 41)
                .putString("package_name", packageName)
                .putString("import_token", token)
                .putLong("started_at", 1)
                .commit(),
        )
        val pending = requireNotNull(store.load())
        val cancelling = FinalInstallReceipt(
            importToken = token,
            sessionId = 41,
            packageName = packageName,
            state = InstallState.Cancelled,
            stage = FinalReceiptStage.CANCELLING,
            origin = FinalReceiptOrigin.PROGRAM_CANCEL,
            rawTerminalAtEpochMillis = null,
            terminalAtEpochMillis = 2,
        )
        recoveryControl.failNextCommit = true
        assertFalse(store.createFinalReceipt(pending, null, cancelling))
        assertNull(store.loadFinalReceipt())
        assertEquals(pending, store.load())
        assertTrue(retainedImport.exists())
        assertTrue(store.createFinalReceipt(pending, null, cancelling))
        assertTrue(store.clearPendingPreservingImport(pending))
        val programReady = cancelling.copy(
            stage = FinalReceiptStage.READY,
            terminalAtEpochMillis = 3,
        )
        recoveryControl.failNextCommit = true
        assertFalse(store.replaceFinalReceipt(cancelling, null, programReady))
        assertEquals(cancelling, store.loadFinalReceipt())
        assertTrue(retainedImport.exists())
        assertTrue(store.replaceFinalReceipt(cancelling, null, programReady))

        assertTrue(
            store.recordTerminalResult(
                PackageInstallResult.Success(41),
                terminalAtEpochMillis = 4,
            ),
        )
        val lateRaw = requireNotNull(store.loadTerminalResult())
        assertNull(store.acknowledgeFinalReceipt(programReady, null))
        assertEquals(programReady, store.loadFinalReceipt())
        val rawReady = programReady.copy(
            state = InstallState.Installed(packageName),
            origin = FinalReceiptOrigin.RAW_TERMINAL,
            rawTerminalAtEpochMillis = lateRaw.terminalAtEpochMillis,
            terminalAtEpochMillis = 5,
        )
        recoveryControl.failNextCommit = true
        assertFalse(store.replaceFinalReceipt(programReady, lateRaw, rawReady))
        assertEquals(programReady, store.loadFinalReceipt())
        assertEquals(lateRaw, store.loadTerminalResult())
        assertTrue(retainedImport.exists())
        assertTrue(store.replaceFinalReceipt(programReady, lateRaw, rawReady))
        val acknowledged = requireNotNull(
            store.acknowledgeFinalReceipt(rawReady, lateRaw),
        )
        assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged.stage)
        assertEquals(acknowledged, store.loadFinalReceipt())
        assertTrue(retainedImport.exists())
        importRoot.deleteRecursively()
    }

    @Test
    fun normalHandoffRoundTripsTerminalOutcomeWithoutBlockingImportCleanupOwner() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryControl = MemoryPreferenceControl()
        val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val importRoot = Files.createTempDirectory("sixbridge-normal-handoff").toFile()
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        val token = "11111111-1111-1111-1111-111111111111"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        val prepared = NormalInstallHandoff(
            importToken = token,
            sessionId = null,
            packageName = packageName,
            state = InstallState.Cancelled,
            stage = NormalHandoffStage.PREPARED,
        )

        recoveryControl.failNextCommit = true
        assertFalse(store.compareAndSetNormalHandoff(null, prepared))
        assertNull(store.loadNormalHandoff())
        assertTrue(store.compareAndSetNormalHandoff(null, prepared))
        val recreated = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertEquals(prepared, recreated.loadNormalHandoff())
        assertFalse(recreated.compareAndSetNormalHandoff(prepared, null))
        assertEquals(prepared, recreated.loadNormalHandoff())
        val ready = prepared.copy(stage = NormalHandoffStage.OUTCOME_READY)
        recoveryControl.failNextCommit = true
        assertFalse(recreated.compareAndSetNormalHandoff(prepared, ready))
        assertEquals(prepared, recreated.loadNormalHandoff())
        assertTrue(recreated.compareAndSetNormalHandoff(prepared, ready))
        assertEquals(ready, recreated.loadNormalHandoff())
        recoveryControl.failNextCommit = true
        assertFalse(recreated.compareAndSetNormalHandoff(ready, null))
        assertEquals(ready, recreated.loadNormalHandoff())
        assertTrue(recreated.compareAndSetNormalHandoff(ready, null))
        assertNull(recreated.loadNormalHandoff())
        importRoot.deleteRecursively()
    }

    @Test
    fun normalHandoffRebaseRequiresExactCurrentRawReadyReceipt() {
        val fixture = normalRebaseFixture(includeLateRaw = true)
        val replacement = fixture.prepared.copy(state = fixture.currentRaw.state)

        fixture.recoveryControl.failNextCommit = true
        assertFalse(fixture.store.rebaseNormalHandoff(fixture.prepared, replacement))
        assertEquals(fixture.prepared, fixture.store.loadNormalHandoff())
        assertEquals(fixture.currentRaw, fixture.store.loadFinalReceipt())

        assertFalse(
            fixture.store.rebaseNormalHandoff(
                fixture.prepared,
                replacement.copy(importToken = "22222222-2222-2222-2222-222222222222"),
            ),
        )
        assertFalse(
            fixture.store.rebaseNormalHandoff(
                fixture.prepared,
                replacement.copy(sessionId = 42),
            ),
        )
        assertFalse(
            fixture.store.rebaseNormalHandoff(
                fixture.prepared,
                replacement.copy(
                    packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
                ),
            ),
        )
        assertFalse(
            fixture.store.rebaseNormalHandoff(
                fixture.prepared,
                replacement.copy(stage = NormalHandoffStage.OUTCOME_READY),
            ),
        )

        assertTrue(fixture.store.rebaseNormalHandoff(fixture.prepared, replacement))
        val recreated = SharedPreferencesPendingInstallStore(
            pendingPreferences = fixture.pendingPreferences,
            recoveryPreferences = fixture.recoveryPreferences,
            importDirectory = fixture.importRoot,
        )
        assertEquals(replacement, recreated.loadNormalHandoff())
        assertEquals(fixture.currentRaw, recreated.loadFinalReceipt())
        assertFalse(recreated.rebaseNormalHandoff(fixture.prepared, replacement))

        val programFixture = normalRebaseFixture(includeLateRaw = false)
        assertFalse(
            programFixture.store.rebaseNormalHandoff(
                programFixture.prepared,
                programFixture.prepared.copy(
                    state = InstallState.Installed(programFixture.prepared.packageName),
                ),
            ),
        )

        val acknowledgedFixture = normalRebaseFixture(includeLateRaw = true)
        val raw = requireNotNull(acknowledgedFixture.store.loadTerminalResult())
        val acknowledged = requireNotNull(
            acknowledgedFixture.store.acknowledgeFinalReceipt(
                acknowledgedFixture.currentRaw,
                raw,
            ),
        )
        assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged.stage)
        assertFalse(
            acknowledgedFixture.store.rebaseNormalHandoff(
                acknowledgedFixture.prepared,
                acknowledgedFixture.prepared.copy(state = acknowledged.state),
            ),
        )

        fixture.importRoot.deleteRecursively()
        programFixture.importRoot.deleteRecursively()
        acknowledgedFixture.importRoot.deleteRecursively()
    }

    @Test
    fun malformedFinalReceiptMarkerBlocksClaimAndReportsInvalid() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryPreferences = memorySharedPreferences()
        val importRoot = Files.createTempDirectory("sixbridge-malformed-final").toFile()
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            recoveryPreferences.edit()
                .putInt("final_version", 1)
                .putString("final_stage", "BROKEN")
                .commit(),
        )

        assertEquals(RecoveryRecordSnapshot.Invalid, store.finalReceiptSnapshot())
        assertNull(
            store.tryClaim(
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                importToken = "11111111-1111-1111-1111-111111111111",
                startedAtEpochMillis = 1,
            ),
        )
        assertTrue(pendingPreferences.all.isEmpty())
        importRoot.deleteRecursively()
    }

    @Test
    fun malformedNormalHandoffMarkerBlocksClaimAndReportsInvalid() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryPreferences = memorySharedPreferences()
        val importRoot = Files.createTempDirectory("sixbridge-malformed-handoff").toFile()
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            recoveryPreferences.edit()
                .putInt("handoff_version", 1)
                .putString("handoff_stage", "BROKEN")
                .commit(),
        )

        assertEquals(RecoveryRecordSnapshot.Invalid, store.normalHandoffSnapshot())
        assertNull(
            store.tryClaim(
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                importToken = "11111111-1111-1111-1111-111111111111",
                startedAtEpochMillis = 1,
            ),
        )
        assertTrue(pendingPreferences.all.isEmpty())
        importRoot.deleteRecursively()
    }

    @Test
    fun tokenlessRawQuarantineCommitFaultPreservesExactPendingAndImport() {
        val pendingPreferences = memorySharedPreferences()
        val recoveryControl = MemoryPreferenceControl()
        val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val importRoot = Files.createTempDirectory("sixbridge-tokenless-raw").toFile()
        val token = "11111111-1111-1111-1111-111111111111"
        val nextToken = "22222222-2222-2222-2222-222222222222"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        val retainedImport = File(importRoot, token).apply { mkdirs() }
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            pendingPreferences.edit()
                .putInt("session_id", 41)
                .putString("package_name", packageName)
                .putString("import_token", token)
                .putLong("started_at", 1)
                .commit(),
        )
        assertTrue(
            recoveryPreferences.edit()
                .putInt("result_session_id", 41)
                .putString("result_kind", "cancelled")
                .putString("result_package_name", packageName)
                .putLong("terminal_at", 2)
                .commit(),
        )
        val pending = requireNotNull(store.load())
        val legacyRaw = requireNotNull(store.loadTerminalResult())
        assertNull(legacyRaw.importToken)

        recoveryControl.failNextCommit = true
        assertFalse(store.quarantineTerminalResult(legacyRaw, pending, null))
        val recreated = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertEquals(pending, recreated.load())
        assertEquals(legacyRaw, recreated.loadTerminalResult())
        assertTrue(retainedImport.exists())

        assertTrue(recreated.quarantineTerminalResult(legacyRaw, pending, null))
        assertNull(recreated.loadTerminalResult())
        assertEquals(pending, recreated.load())
        assertTrue(retainedImport.exists())
        assertTrue(recreated.clearPendingPreservingImport(pending))
        assertNotNull(
            recreated.tryClaim(
                packageName = packageName,
                importToken = nextToken,
                startedAtEpochMillis = 3,
            ),
        )
        importRoot.deleteRecursively()
    }

    @Test
    fun inputLeaseIsReleasedAfterDurableWorkHandoff() {
        val source = assetZip("ondevice-conversion")
        val database = Room.inMemoryDatabaseBuilder(
            targetContext,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val inputRoot = File(
            targetContext.filesDir,
            "input-lease-gate/${UUID.randomUUID()}/conversion-input",
        )
        val workRoot = File(
            targetContext.filesDir,
            "input-lease-gate/${UUID.randomUUID()}/conversion-work",
        )
        val inputService = OnDeviceInputService(
            privateRoot = inputRoot,
            safInputOpener = SafInputOpener { ByteArrayInputStream(source) },
        )
        val packager = OnDeviceWrapperPackager(targetContext)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { true },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val coordinator = OnDeviceConversionCoordinator(
            input = inputService,
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(profile())
            },
            runtime = DefaultConversionRuntime(),
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort { null },
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(GeneratedBundleStore(targetContext)),
            imports = ConversionGeneratedImportPort { null },
        )
        var workToken: String? = null
        try {
            kotlinx.coroutines.runBlocking {
                val result = coordinator.begin(
                    OnDeviceInputRequest.Saf(android.net.Uri.EMPTY),
                )
                assertTrue(result is ConversionBeginResult.AnalysisReady)
                val restored = requireNotNull(workStore.restore())
                workToken = restored.token

                assertTrue(inputRoot.listFiles().orEmpty().isEmpty())
                workStore.withVerifiedInputFile(restored.token) { copied ->
                    assertTrue(copied.isFile)
                    assertArrayEquals(source, copied.readBytes())
                }
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                workToken?.let { runCatching { workStore.discard(it) } }
            }
            database.close()
            inputRoot.parentFile?.deleteRecursively()
            workRoot.parentFile?.deleteRecursively()
        }
    }

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
            input = object : ConversionInputPort {
                override suspend fun acquire(
                    request: OnDeviceInputRequest,
                ): OnDeviceInputArtifact = artifact

                override suspend fun release(artifact: OnDeviceInputArtifact): Boolean = true

                override suspend fun reclaimOrphans(): Boolean = true
            },
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

    private fun normalRebaseFixture(includeLateRaw: Boolean): NormalRebaseFixture {
        val pendingPreferences = memorySharedPreferences()
        val recoveryControl = MemoryPreferenceControl()
        val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val importRoot = Files.createTempDirectory("sixbridge-normal-rebase").toFile()
        val token = "11111111-1111-1111-1111-111111111111"
        val packageName = "dev.sixbridge.ext.${"a".repeat(32)}"
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = importRoot,
        )
        assertTrue(
            pendingPreferences.edit()
                .putInt("session_id", 41)
                .putString("package_name", packageName)
                .putString("import_token", token)
                .putLong("started_at", 1)
                .commit(),
        )
        val pending = requireNotNull(store.load())
        val cancelling = FinalInstallReceipt(
            importToken = token,
            sessionId = 41,
            packageName = packageName,
            state = InstallState.Cancelled,
            stage = FinalReceiptStage.CANCELLING,
            origin = FinalReceiptOrigin.PROGRAM_CANCEL,
            rawTerminalAtEpochMillis = null,
            terminalAtEpochMillis = 2,
        )
        assertTrue(store.createFinalReceipt(pending, null, cancelling))
        assertTrue(store.clearPendingPreservingImport(pending))
        val programReady = cancelling.copy(
            stage = FinalReceiptStage.READY,
            terminalAtEpochMillis = 3,
        )
        assertTrue(store.replaceFinalReceipt(cancelling, null, programReady))
        val prepared = NormalInstallHandoff(
            importToken = token,
            sessionId = 41,
            packageName = packageName,
            state = InstallState.Cancelled,
            stage = NormalHandoffStage.PREPARED,
        )
        assertTrue(store.compareAndSetNormalHandoff(null, prepared))
        val current = if (includeLateRaw) {
            assertTrue(
                store.recordTerminalResult(
                    PackageInstallResult.Failure(
                        sessionId = 41,
                        code = "INSTALL_FAILED",
                        message = "late raw failed",
                    ),
                    terminalAtEpochMillis = 4,
                ),
            )
            val raw = requireNotNull(store.loadTerminalResult())
            val rawReady = programReady.copy(
                state = InstallState.Failed("INSTALL_FAILED", "late raw failed"),
                origin = FinalReceiptOrigin.RAW_TERMINAL,
                rawTerminalAtEpochMillis = raw.terminalAtEpochMillis,
                terminalAtEpochMillis = 5,
            )
            assertTrue(store.replaceFinalReceipt(programReady, raw, rawReady))
            rawReady
        } else {
            programReady
        }
        return NormalRebaseFixture(
            store = store,
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            recoveryControl = recoveryControl,
            importRoot = importRoot,
            prepared = prepared,
            currentRaw = current,
        )
    }
}

private data class NormalRebaseFixture(
    val store: SharedPreferencesPendingInstallStore,
    val pendingPreferences: SharedPreferences,
    val recoveryPreferences: SharedPreferences,
    val recoveryControl: MemoryPreferenceControl,
    val importRoot: File,
    val prepared: NormalInstallHandoff,
    val currentRaw: FinalInstallReceipt,
)

internal class MemoryPreferenceControl {
    var failNextCommit: Boolean = false
}

internal fun memorySharedPreferences(
    control: MemoryPreferenceControl = MemoryPreferenceControl(),
): SharedPreferences {
    val values = linkedMapOf<String, Any?>()

    fun editor(): SharedPreferences.Editor {
        val updates = linkedMapOf<String, Any?>()
        val removals = linkedSetOf<String>()
        var clear = false
        lateinit var proxy: SharedPreferences.Editor

        fun applyChanges() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            updates.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }

        proxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "putString", "putStringSet", "putInt", "putLong", "putFloat",
                "putBoolean",
                -> {
                    updates[args[0] as String] = args[1]
                    removals.remove(args[0] as String)
                    proxy
                }

                "remove" -> {
                    removals += args[0] as String
                    updates.remove(args[0] as String)
                    proxy
                }

                "clear" -> {
                    clear = true
                    updates.clear()
                    removals.clear()
                    proxy
                }

                "commit" -> {
                    if (control.failNextCommit) {
                        control.failNextCommit = false
                        false
                    } else {
                        applyChanges()
                        true
                    }
                }

                "apply" -> {
                    applyChanges()
                    null
                }

                else -> error("지원하지 않는 SharedPreferences.Editor 호출: ${method.name}")
            }
        } as SharedPreferences.Editor
        return proxy
    }

    return Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "getAll" -> values.toMap()
            "getString" -> values[args[0] as String] as? String ?: args[1]
            "getStringSet" -> values[args[0] as String] as? Set<*> ?: args[1]
            "getInt" -> values[args[0] as String] as? Int ?: args[1]
            "getLong" -> values[args[0] as String] as? Long ?: args[1]
            "getFloat" -> values[args[0] as String] as? Float ?: args[1]
            "getBoolean" -> values[args[0] as String] as? Boolean ?: args[1]
            "contains" -> values.containsKey(args[0] as String)
            "edit" -> editor()
            "registerOnSharedPreferenceChangeListener",
            "unregisterOnSharedPreferenceChangeListener",
            -> null

            else -> error("지원하지 않는 SharedPreferences 호출: ${method.name}")
        }
    } as SharedPreferences
}
