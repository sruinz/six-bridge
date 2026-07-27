package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDeviceConversionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `승인된 변환은 package generated import BUNDLE_READY 순서를 지킨다`() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(temporaryFolder, events)
        val coordinator = fixture.coordinator()

        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )
        val ready = assertIs<ConversionBuildResult.BundleReady>(
            coordinator.build(
                token = analysis.token,
                approvedAdapterIds = analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = null,
            ),
        )

        assertEquals(
            listOf(
                "acquire",
                "create",
                "profile",
                "analyze",
                "awaiting",
                "restore",
                "profile",
                "analyze",
                "prepare",
                "building",
                "identity",
                "installed",
                "reserve",
                "bind",
                "package",
                "publish",
                "import",
                "bundle-ready",
            ),
            events,
        )
        assertEquals(fixture.token, ready.generated.token)
        assertEquals("Example", ready.review.name)
    }

    @Test
    fun `브라우저가 없으면 durable input을 보존하고 build를 차단한다`() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(temporaryFolder, events).apply {
            profileResult = SamsungProfileSnapshot.Missing
        }
        val coordinator = fixture.coordinator()

        val failure = assertIs<ConversionBeginResult.Failure>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        assertEquals(ConversionFailureCode.BROWSER_MISSING, failure.code)
        assertEquals(listOf("acquire", "create", "profile"), events)
        assertTrue(fixture.inputFile.isFile)
    }

    @Test
    fun `필수 승인 누락과 unknown 승인은 reserve 전에 거부한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf())
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        val missing = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(analysis.token, emptySet(), true, null),
        )
        val unknown = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(analysis.token, setOf("unknown"), true, null),
        )

        assertEquals(ConversionFailureCode.APPROVAL_REQUIRED, missing.code)
        assertEquals(ConversionFailureCode.APPROVAL_REQUIRED, unknown.code)
        assertTrue("reserve" !in fixture.events)
    }

    @Test
    fun `profile 변경은 새 분석을 저장하고 재승인을 요구한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf())
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )
        fixture.currentProfile = profile(versionCode = 2)

        val changed = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals,
                partialConfirmed = true,
                identitySelection = null,
            ),
        )

        assertEquals(ConversionFailureCode.PROFILE_CHANGED, changed.code)
        assertTrue("refresh-awaiting" in fixture.events)
        assertTrue("reserve" !in fixture.events)
    }

    private class Fixture(
        folder: TemporaryFolder,
        val events: MutableList<String>,
    ) {
        val token = "11111111-1111-4111-8111-111111111111"
        val inputFile = folder.newFile("input.bin").apply { writeText("input") }
        private val generatedFile = folder.newFile("$token.six").apply { writeText("bundle") }
        var currentProfile = profile()
        var profileResult: SamsungProfileSnapshot = SamsungProfileSnapshot.Supported(currentProfile)
        private var stage = ConversionJobStage.INPUT_READY
        private var storedProfile: String? = null

        fun coordinator() = OnDeviceConversionCoordinator(
            input = ConversionInputPort {
                events += "acquire"
                OnDeviceInputArtifact(
                    token,
                    OnDeviceInputKind.CRX3,
                    inputFile,
                    "1".repeat(64),
                    EXTENSION_ID,
                )
            },
            work = object : ConversionWorkPort {
                override suspend fun create(artifact: OnDeviceInputArtifact): RestoredConversionJob {
                    events += "create"
                    return job()
                }

                override suspend fun restore(): RestoredConversionJob? {
                    events += "restore"
                    return job()
                }

                override suspend fun markAwaitingApproval(token: String, profileSha256: String) {
                    events += "awaiting"
                    stage = ConversionJobStage.AWAITING_APPROVAL
                    storedProfile = profileSha256
                }

                override suspend fun refreshAwaitingApproval(
                    token: String,
                    expectedProfileSha256: String,
                    profileSha256: String,
                ) {
                    events += "refresh-awaiting"
                    storedProfile = profileSha256
                }

                override suspend fun markBuilding(
                    token: String,
                    approvedAdapterIds: Set<String>,
                    profileSha256: String,
                ) {
                    events += "building"
                    stage = ConversionJobStage.BUILDING
                }

                override suspend fun markBundleReady(
                    token: String,
                    bundle: File,
                    expectedSha256: String,
                ) {
                    events += "bundle-ready"
                    stage = ConversionJobStage.BUNDLE_READY
                }

                override suspend fun discard(token: String): Boolean = true

                override suspend fun <T> withVerifiedInputFile(
                    token: String,
                    consume: (File) -> T,
                ): T = consume(inputFile)
            },
            profiles = ConversionProfilePort {
                events += "profile"
                when (val result = profileResult) {
                    is SamsungProfileSnapshot.Supported ->
                        SamsungProfileSnapshot.Supported(currentProfile)
                    else -> result
                }
            },
            runtime = object : ConversionRuntimePort {
                override fun analyze(
                    input: File,
                    kind: OnDeviceInputKind,
                    profile: BrowserProfile,
                ): ConversionAnalysisSnapshot {
                    events += "analyze"
                    return analysis(profile)
                }

                override fun prepare(
                    input: File,
                    kind: OnDeviceInputKind,
                    profile: BrowserProfile,
                    approvedAdapterIds: Set<String>,
                ): PreparedConversionSnapshot {
                    events += "prepare"
                    return prepared(profile)
                }

                override fun bind(
                    prepared: PreparedConversionSnapshot,
                    profile: BrowserProfile,
                    identity: OnDevicePackageIdentity,
                ): BoundConversionSnapshot {
                    events += "bind"
                    return bound(profile)
                }
            },
            identities = object : ConversionIdentityPort {
                override suspend fun existingTargets(profileMajorVersion: Int) = emptyList<ExistingConversionTarget>()

                override suspend fun resolve(
                    prepared: PreparedConversionSnapshot,
                    profile: BrowserProfile,
                    selection: InstallationIdentitySelection?,
                    ownerToken: String,
                ): ResolvedPackageIdentity {
                    events += "identity"
                    return ResolvedPackageIdentity(
                        key = InstallationIdentityKey.create(
                            LogicalSourceIdentity.storeExtensionId(EXTENSION_ID),
                            profile.majorVersion,
                            InstallationIdentityMode.PASSTHROUGH,
                        ),
                        identity = packageIdentity(),
                        unreservedLease = null,
                    )
                }

                override suspend fun reserve(
                    resolved: ResolvedPackageIdentity,
                    displayName: String,
                    installedVersionCode: Long?,
                    ownerToken: String,
                ): ReservedPackageIdentity {
                    events += "reserve"
                    return ReservedPackageIdentity(
                        resolved = resolved,
                        versionCode = 1,
                        opaque = Unit,
                    )
                }

                override suspend fun abort(ownerToken: String) = Unit
            },
            installed = ConversionInstalledPort {
                events += "installed"
                null
            },
            packages = object : ConversionPackagePort {
                override suspend fun packageWrapper(
                    reservation: ReservedPackageIdentity,
                    bound: BoundConversionSnapshot,
                    token: String,
                ): PackagedConversion {
                    events += "package"
                    return PackagedConversion(
                        extensionId = EXTENSION_ID,
                        packageName = PACKAGE,
                        versionCode = 1,
                        wrapperSha256 = "3".repeat(64),
                        outputApk = inputFile,
                        opaque = Unit,
                    )
                }
            },
            bundles = object : ConversionBundlePort {
                override fun publish(token: String, packaged: PackagedConversion): GeneratedBundle {
                    events += "publish"
                    return GeneratedBundle(token, generatedFile, "2".repeat(64), "$token.six")
                }

                override fun recover(token: String): GeneratedBundle? = null
            },
            imports = ConversionGeneratedImportPort {
                events += "import"
                ReviewBundle(
                    token = "22222222-2222-4222-8222-222222222222",
                    packageName = PACKAGE,
                    extensionId = EXTENSION_ID,
                    name = "Example",
                    version = "1.0",
                    status = ContractCompatibilityStatus.PARTIAL,
                    issues = emptyList(),
                )
            },
        )

        private fun job() = RestoredConversionJob(
            token = token,
            inputKind = OnDeviceInputKind.CRX3,
            inputSha256 = "1".repeat(64),
            stage = stage,
            approvedAdapterIds = emptySet(),
            profileSha256 = storedProfile,
            bundleSha256 = null,
        )

        private fun analysis(profile: BrowserProfile) = ConversionAnalysisSnapshot(
            report = coreReport(profile),
            selectedAutomaticAdapters = emptySet(),
            requiredApprovals = setOf("adapter"),
            logicalSourceIdentity =
                LogicalSourceIdentity.storeExtensionId(EXTENSION_ID),
        )

        private fun prepared(profile: BrowserProfile) = PreparedConversionSnapshot(
            reportBefore = coreReport(profile),
            originalSourceSha256 = "4".repeat(64),
            planSha256 = "5".repeat(64),
            transformedSourceZip = "zip".encodeToByteArray(),
            passthroughCrx = "crx".encodeToByteArray(),
            logicalSourceIdentity =
                LogicalSourceIdentity.storeExtensionId(EXTENSION_ID),
            descriptorName = "Example",
            descriptorVersion = "1.0",
            sourceKind = "CRX3",
            opaque = Unit,
        )

        private fun bound(profile: BrowserProfile) = BoundConversionSnapshot(
            sourceZip = "zip".encodeToByteArray(),
            passthroughCrx = "crx".encodeToByteArray(),
            compatibility = contractReport(profile),
            extensionId = EXTENSION_ID,
        )
    }

    companion object {
        private val EXTENSION_ID = "a".repeat(32)
        private val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"

        private fun profile(versionCode: Long = 1) = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = versionCode,
            majorVersion = 30,
            supportedApis = emptySet(),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            installationApkSetSha256 = "f".repeat(64),
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )

        private fun coreReport(profile: BrowserProfile) = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Example",
            extensionVersion = "1.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            sourceSha256 = "4".repeat(64),
            browserProfileSha256 = profile.canonicalSha256(),
        )

        private fun contractReport(profile: BrowserProfile) = ContractCompatibilityReport(
            schemaVersion = 4,
            status = ContractCompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Example",
            extensionVersion = "1.0",
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "passthrough",
            adapterDecisions = emptyList(),
            originalExtensionId = EXTENSION_ID,
            convertedExtensionId = EXTENSION_ID,
            sourceSha256 = "4".repeat(64),
            browserProfileSha256 = profile.canonicalSha256(),
            originalSourceSha256 = "4".repeat(64),
            planSha256 = "5".repeat(64),
        )

        private fun packageIdentity() = OnDevicePackageIdentity(
            extensionId = EXTENSION_ID,
            packageName = PACKAGE,
            wrapperSignerSha256 = "a".repeat(64),
            crxKeyAlias = null,
            crxPublicKeyDer = null,
        )
    }
}
