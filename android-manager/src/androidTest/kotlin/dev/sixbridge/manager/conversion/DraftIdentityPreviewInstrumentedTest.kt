package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftSelectedAdapter
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3Writer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftIdentityPreviewInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun crx3PassthroughDraftPreviewMatchesResolvedAndReservedIdentityKey() =
        assertPassthroughDraftPreview(ExtensionInputKind.CRX3)

    @Test
    fun chromeWebStorePassthroughDraftPreviewMatchesResolvedAndReservedIdentityKey() =
        assertPassthroughDraftPreview(ExtensionInputKind.CHROME_WEB_STORE)

    @Test
    fun schemaV3DraftRunsThroughRuntimeIdentityStageAndActualWrapper() {
        val profile = profile()
        val fixture = schemaV3PreparedDraft(profile)
        val identityKey = InstallationIdentityKey.create(
            LogicalSourceIdentity.signedDraft(fixture.sourceIdentity),
            profile.majorVersion,
            InstallationIdentityMode.PREPARED,
        )
        val forbiddenAlias = "sixbridge.crx.rsa.v1.${identityKey.identitySha256}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(forbiddenAlias))
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val inputRoot = File(
            context.filesDir,
            "schema3-full-path-input/${UUID.randomUUID()}",
        )
        val workRoot = File(
            context.filesDir,
            "schema3-full-path-work/${UUID.randomUUID()}",
        )
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val generatedStore = GeneratedBundleStore(context)
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { true },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val inputService = OnDeviceInputService(
            privateRoot = inputRoot,
            safInputOpener = SafInputOpener {
                ByteArrayInputStream(fixture.draft)
            },
        )
        val coordinator = OnDeviceConversionCoordinator(
            input = inputService,
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(profile)
            },
            runtime = DefaultConversionRuntime(),
            identities = DefaultConversionIdentityPort(ledger, packager),
            installed = ConversionInstalledPort { null },
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(generatedStore),
            imports = ConversionGeneratedImportPort { null },
        )
        var token: String? = null
        val wrapper = File(
            context.cacheDir,
            "schema3-full-path-wrapper-${UUID.randomUUID()}.apk",
        )
        try {
            runBlocking {
                val analysis = coordinator.begin(
                    OnDeviceInputRequest.Saf(android.net.Uri.EMPTY),
                )
                check(analysis is ConversionBeginResult.AnalysisReady) {
                    "schema3 전체 경로 분석 실패: $analysis"
                }
                token = analysis.token
                assertEquals(
                    identityKey.identitySha256.take(12),
                    (analysis.identityRequirement as IdentityRequirement.Stable)
                        .identityKeyPreview,
                )

                val built = coordinator.build(
                    token = analysis.token,
                    approvedAdapterIds = analysis.requiredApprovals,
                    partialConfirmed = true,
                    identitySelection = null,
                )
                val generated = when (built) {
                    is ConversionBuildResult.BundleReady -> built.generated
                    is ConversionBuildResult.Failure -> {
                        assertEquals(ConversionFailureCode.BUNDLE_IO, built.code)
                        requireNotNull(built.generated) {
                            "actual wrapper bundle이 import terminal 전에 생성되지 않았습니다."
                        }
                    }
                }
                val bundle = generated.file.inputStream().use(SixBundleContract::read)
                wrapper.writeBytes(bundle.wrapperApk)
                val wrapperFacts =
                    AndroidArchiveInspector(context.packageManager).inspect(wrapper)

                assertArrayEquals(fixture.preparedCrx, wrapperFacts.crxBytes)
                assertEquals(fixture.extensionId, bundle.extension.extensionId)
                assertEquals(fixture.extensionId, wrapperFacts.metadata["extension-id"])
                assertFalse(keyStore.containsAlias(forbiddenAlias))
            }
        } finally {
            runBlocking {
                token?.let { runCatching { coordinator.discard(it) } }
            }
            database.close()
            wrapper.delete()
            inputRoot.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    @Test
    fun directZipCrxAndChromeRetainInstallationIdentityModes() {
        val runtime = DefaultConversionRuntime()
        val profile = profile()
        val zip = simpleExtensionZip()
        val crx = externalSignedCrx(zip)
        listOf(
            Triple(OnDeviceInputKind.ZIP, zip, InstallationIdentityMode.CONVERTED),
            Triple(OnDeviceInputKind.CRX3, crx, InstallationIdentityMode.PASSTHROUGH),
            Triple(
                OnDeviceInputKind.CHROME_WEB_STORE,
                crx,
                InstallationIdentityMode.PASSTHROUGH,
            ),
        ).forEach { (kind, bytes, expectedMode) ->
            val file = File(context.cacheDir, "direct-mode-${kind.name}.bin")
            try {
                file.writeBytes(bytes)
                val analysis = runtime.analyze(
                    file,
                    kind,
                    profile,
                    AnalysisControl(AnalysisSafetyPolicy.mobile),
                )
                val prepared = runtime.prepare(
                    file,
                    kind,
                    profile,
                    analysis.requiredApprovals,
                )

                assertEquals(expectedMode, analysis.identityMode)
                assertEquals(expectedMode, prepared.identityMode)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun passthroughModeWithoutCrxProofIsRejectedBeforeIdentityMutation() {
        assertForgedPreparedRejected(
            baseKind = OnDeviceInputKind.CRX3,
            identityMode = InstallationIdentityMode.PASSTHROUGH,
            passthroughCrx = null,
        )
    }

    @Test
    fun convertedModeWithCrxProofIsRejectedBeforeLeaseCreation() {
        val crx = externalSignedCrx(simpleExtensionZip())
        assertForgedPreparedRejected(
            baseKind = OnDeviceInputKind.ZIP,
            identityMode = InstallationIdentityMode.CONVERTED,
            passthroughCrx = crx,
        )
    }

    private fun assertForgedPreparedRejected(
        baseKind: OnDeviceInputKind,
        identityMode: InstallationIdentityMode,
        passthroughCrx: ByteArray?,
    ) {
        val profile = profile()
        val bytes = if (baseKind == OnDeviceInputKind.ZIP) {
            simpleExtensionZip()
        } else {
            externalSignedCrx(simpleExtensionZip())
        }
        val artifactToken = UUID.randomUUID().toString()
        val source = File(
            context.filesDir,
            "forged-identity-input/$artifactToken/input.bin",
        ).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(bytes)
        }
        val runtime = DefaultConversionRuntime()
        val analysis = runtime.analyze(
            source,
            baseKind,
            profile,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )
        val validPrepared = runtime.prepare(
            source,
            baseKind,
            profile,
            analysis.requiredApprovals,
        )
        val forgedPrepared = validPrepared.copy(
            identityMode = identityMode,
            passthroughCrx = passthroughCrx,
        )
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val workRoot = File(
            context.filesDir,
            "forged-identity-work/${UUID.randomUUID()}",
        )
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { true },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        var ownerToken: String? = null
        try {
            runBlocking {
                val job = workStore.create(
                    OnDeviceInputArtifact(
                        token = artifactToken,
                        kind = baseKind,
                        privateFile = source,
                        sha256 = sha256(bytes),
                        expectedExtensionId = null,
                    ),
                )
                ownerToken = job.token
                val outcome = runCatching {
                    DefaultConversionIdentityPort(ledger, packager).resolve(
                        prepared = forgedPrepared,
                        profile = profile,
                        selection = if (forgedPrepared.logicalSourceIdentity == null) {
                            InstallationIdentitySelection.CreateNew
                        } else {
                            null
                        },
                        ownerToken = job.token,
                    )
                }

                assertNull(
                    database.conversionIdentityDao().getLeaseByOwnerToken(job.token),
                )
                assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
                assertEquals(
                    "prepared identity mode와 passthrough CRX proof가 일치하지 않습니다.",
                    outcome.exceptionOrNull()?.message,
                )
            }
        } finally {
            runBlocking {
                ownerToken?.let {
                    runCatching { ledger.abortByOwnerToken(it, packager) }
                    runCatching { workStore.discard(it) }
                }
            }
            database.close()
            source.parentFile?.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    private fun assertPassthroughDraftPreview(inputKind: ExtensionInputKind) {
        val profile = profile()
        val crx = externalSignedCrx(simpleExtensionZip())
        val crxProof = Crx3Verifier.verify(crx)
        val sourceIdentity = "store:${crxProof.extensionId}"
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromCrx(
                    kind = inputKind,
                    crxBytes = crx,
                    expectedExtensionId = crxProof.extensionId,
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                emptySet(),
            )
        }
        check(prepared.passthroughCrx != null)
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val compatibilityBefore = prepared.compatibilityBefore.copy(
            originalExtensionId = crxProof.extensionId,
        )
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "draft-preview-gate",
                adapterVersions = selected.associate { it.id to it.version },
                sourceIdentity = sourceIdentity,
            ),
            input = DraftInputMetadata(
                inputKind = inputKind,
                originalSourceSha256 = prepared.originalSourceSha256,
                browserProfileSha256 = prepared.browserProfileSha256,
                originalExtensionId = crxProof.extensionId,
                originalExtensionVersion = requireNotNull(
                    prepared.compatibilityBefore.extensionVersion,
                ),
                sourceIdentity = sourceIdentity,
            ),
            sourceKind = DraftSourceKind.CRX,
            source = crx,
            plan = DraftConversionPlan.create(
                compatibilityBefore,
                selected,
            ),
            compatibilityBefore = compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        val draftBytes = SixDraft.createBytes(
            payload,
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        )
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        val artifactToken = UUID.randomUUID().toString()
        val input = File(
            context.filesDir,
            "draft-preview-input/$artifactToken/input.bin",
        ).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(draftBytes)
        }
        val workRoot = File(
            context.filesDir,
            "draft-preview-work/${UUID.randomUUID()}",
        )
        val packager = OnDeviceWrapperPackager(context)
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val workStore = ConversionWorkStore(
            privateRoot = workRoot,
            dao = database.conversionJobDao(),
            bundleVerifier = ConversionBundleVerifier { true },
            identityOwnerLifecycle = ledger.ownerLifecycle(packager),
        )
        val runtime = DefaultConversionRuntime()
        val identities = DefaultConversionIdentityPort(ledger, packager)
        val coordinator = OnDeviceConversionCoordinator(
            input = object : ConversionInputPort {
                override suspend fun acquire(
                    request: OnDeviceInputRequest,
                ) = OnDeviceInputArtifact(
                    token = artifactToken,
                    kind = OnDeviceInputKind.SIX_DRAFT,
                    privateFile = input,
                    sha256 = sha256(draftBytes),
                    expectedExtensionId = null,
                )

                override suspend fun release(artifact: OnDeviceInputArtifact): Boolean = true

                override suspend fun reclaimOrphans(): Boolean = true
            },
            work = DefaultConversionWorkPort(workStore),
            profiles = ConversionProfilePort {
                SamsungProfileSnapshot.Supported(profile)
            },
            runtime = runtime,
            identities = identities,
            installed = ConversionInstalledPort { null },
            packages = DefaultConversionPackagePort(ledger, packager),
            bundles = DefaultConversionBundlePort(GeneratedBundleStore(context)),
            imports = ConversionGeneratedImportPort { null },
        )
        var ownerToken: String? = null
        try {
            runBlocking {
                val analysis = coordinator.begin(OnDeviceInputRequest.Saf(android.net.Uri.EMPTY))
                check(analysis is ConversionBeginResult.AnalysisReady)
                ownerToken = analysis.token
                val preview = checkNotNull(
                    (analysis.identityRequirement as? IdentityRequirement.Stable)
                        ?.identityKeyPreview,
                )
                val preparedDraft = runtime.prepare(
                    input,
                    OnDeviceInputKind.SIX_DRAFT,
                    profile,
                    analysis.requiredApprovals,
                )
                val resolved = identities.resolve(
                    preparedDraft,
                    profile,
                    null,
                    analysis.token,
                )
                val reserved = identities.reserve(
                    resolved,
                    preparedDraft.descriptorName,
                    null,
                    analysis.token,
                )

                assertEquals(InstallationIdentityMode.PASSTHROUGH, resolved.key.mode)
                assertEquals(resolved.key, reserved.resolved.key)
                assertEquals(resolved.key.identitySha256.take(12), preview)
            }
        } finally {
            runBlocking {
                ownerToken?.let {
                    runCatching { ledger.abortByOwnerToken(it, packager) }
                    runCatching { workStore.discard(it) }
                }
            }
            database.close()
            input.parentFile?.deleteRecursively()
            workRoot.deleteRecursively()
        }
    }

    private fun externalSignedCrx(source: ByteArray): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        return Crx3Writer.create(
            source,
            object : Crx3SigningKey {
                override fun publicKeyDer(): ByteArray = pair.public.encoded

                override fun signSha256Rsa(payload: ByteArray): ByteArray =
                    Signature.getInstance("SHA256withRSA").run {
                        initSign(pair.private)
                        update(payload)
                        sign()
                    }
            },
        ).bytes
    }

    private fun schemaV3PreparedDraft(profile: BrowserProfile): PreparedDraftFixture {
        val sourceIdentity = "project:${UUID.randomUUID().toString().replace("-", "")}"
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    kind = ExtensionInputKind.ZIP,
                    zipBytes = simpleExtensionZip(),
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                emptySet(),
            )
        }
        val preparedCrx = externalSignedCrx(prepared.transformedSourceZip)
        val proof = Crx3Verifier.verify(preparedCrx)
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV3(
                coreVersion = "schema3-full-path",
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
            sourceKind = DraftSourceKind.PREPARED_CRX,
            source = preparedCrx,
            plan = DraftConversionPlan.create(
                prepared.compatibilityBefore,
                selected,
            ),
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter.copy(
                convertedExtensionId = proof.extensionId,
                sourceSha256 = proof.zipSha256,
            ),
        )
        return PreparedDraftFixture(
            draft = SixDraft.createBytes(
                payload,
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
            ),
            preparedCrx = preparedCrx,
            extensionId = proof.extensionId,
            sourceIdentity = sourceIdentity,
        )
    }

    private fun simpleExtensionZip(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write(
                """
                {"manifest_version":3,"name":"Preview","version":"1.0"}
                """.trimIndent().encodeToByteArray(),
            )
            zip.closeEntry()
        }
        output.toByteArray()
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

    private data class PreparedDraftFixture(
        val draft: ByteArray,
        val preparedCrx: ByteArray,
        val extensionId: String,
        val sourceIdentity: String,
    )
}
