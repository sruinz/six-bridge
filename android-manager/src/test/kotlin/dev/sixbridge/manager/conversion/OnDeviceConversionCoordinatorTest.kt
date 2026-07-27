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
import dev.sixbridge.manager.wrapper.InstalledWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
                "identity",
                "installed",
                "reserve",
                "building",
                "bind",
                "package",
                "publish",
                "import",
                "abort",
                "bundle-ready",
            ),
            events,
        )
        assertEquals(fixture.token, ready.generated.token)
        assertEquals("Example", ready.review.name)
        assertNull(fixture.activeOwner)
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
    fun `브라우저 profile이 unknown이면 durable input을 보존하고 build를 차단한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            profileResult = SamsungProfileSnapshot.Unknown
        }

        val failure = assertIs<ConversionBeginResult.Failure>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(ConversionFailureCode.PROFILE_UNKNOWN, failure.code)
        assertTrue(fixture.inputFile.isFile)
        assertTrue("reserve" !in fixture.events)
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

    @Test
    fun `Task 6 입력 오류 code를 사용자 실패 code로 보존한다`() = runTest {
        val cases = listOf(
            OnDeviceInputErrorCode.INVALID_DRAFT to ConversionFailureCode.DRAFT_INVALID,
            OnDeviceInputErrorCode.NETWORK to ConversionFailureCode.NETWORK,
            OnDeviceInputErrorCode.URL to ConversionFailureCode.URL,
            OnDeviceInputErrorCode.INVALID_ZIP to ConversionFailureCode.INPUT_INVALID,
            OnDeviceInputErrorCode.INVALID_CRX3 to ConversionFailureCode.INPUT_INVALID,
            OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED to ConversionFailureCode.INPUT_INVALID,
        )
        cases.forEach { (inputCode, expected) ->
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                inputFailure = OnDeviceInputException(inputCode, "private detail")
            }

            val failure = assertIs<ConversionBeginResult.Failure>(
                fixture.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )

            assertEquals(expected, failure.code, inputCode.name)
        }
    }

    @Test
    fun `구조화 취소는 Failure로 삼키지 않는다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            inputFailure = CancellationException("route replaced")
        }

        assertFailsWith<CancellationException> {
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            )
        }
    }

    @Test
    fun `identity와 package 취소는 보상 뒤 CancellationException을 재전파한다`() = runTest {
        val identity = Fixture(temporaryFolder, mutableListOf()).apply {
            identityFailure = CancellationException("identity cancelled")
        }
        val identityAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            identity.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        assertFailsWith<CancellationException> {
            identity.coordinator().build(
                identityAnalysis.token,
                identityAnalysis.requiredApprovals,
                true,
                null,
            )
        }
        assertEquals(1, identity.events.count { it == "abort" })

        val packaging = Fixture(temporaryFolder, mutableListOf()).apply {
            packageFailure = CancellationException("package cancelled")
        }
        val packageAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            packaging.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        assertFailsWith<CancellationException> {
            packaging.coordinator().build(
                packageAnalysis.token,
                packageAnalysis.requiredApprovals,
                true,
                null,
            )
        }
        assertEquals(ConversionJobStage.AWAITING_APPROVAL, packaging.stage)
        assertEquals(1, packaging.events.count { it == "abort" })
    }

    @Test
    fun `blocked unknown partial은 reserve 전에 차단한다`() = runTest {
        for (
            (status, expected) in listOf(
                CompatibilityStatus.BLOCKED to ConversionFailureCode.BLOCKED,
                CompatibilityStatus.UNKNOWN to ConversionFailureCode.UNKNOWN,
            )
        ) {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                compatibilityStatus = status
            }
            val failure = assertIs<ConversionBeginResult.Failure>(
                fixture.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )
            assertEquals(expected, failure.code)
            assertTrue("reserve" !in fixture.events)
        }

        val partial = Fixture(temporaryFolder, mutableListOf())
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            partial.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        val failure = assertIs<ConversionBuildResult.Failure>(
            partial.coordinator().build(
                analysis.token,
                analysis.requiredApprovals,
                partialConfirmed = false,
                identitySelection = null,
            ),
        )
        assertEquals(ConversionFailureCode.PARTIAL_CONFIRMATION_REQUIRED, failure.code)
        assertTrue("reserve" !in partial.events)
    }

    @Test
    fun `markerless는 선택이 필요하고 stable 입력은 override를 거부한다`() = runTest {
        val markerless = Fixture(temporaryFolder, mutableListOf()).apply {
            logicalIdentity = null
        }
        val markerlessAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            markerless.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        assertIs<IdentityRequirement.UserSelectionRequired>(
            markerlessAnalysis.identityRequirement,
        )
        val missing = assertIs<ConversionBuildResult.Failure>(
            markerless.coordinator().build(
                markerlessAnalysis.token,
                markerlessAnalysis.requiredApprovals,
                true,
                null,
            ),
        )
        assertEquals(ConversionFailureCode.IDENTITY_MISMATCH, missing.code)

        val stable = Fixture(temporaryFolder, mutableListOf())
        val stableAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            stable.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        val override = assertIs<ConversionBuildResult.Failure>(
            stable.coordinator().build(
                stableAnalysis.token,
                stableAnalysis.requiredApprovals,
                true,
                InstallationIdentitySelection.CreateNew,
            ),
        )
        assertEquals(ConversionFailureCode.IDENTITY_MISMATCH, override.code)
    }

    @Test
    fun `signer 불일치와 reserve 실패는 즉시 승인 단계와 빈 owner로 복구한다`() = runTest {
        val signer = Fixture(temporaryFolder, mutableListOf()).apply {
            installedWrapper = InstalledWrapper(
                PACKAGE,
                EXTENSION_ID,
                "Example",
                7,
                "b".repeat(64),
            )
        }
        val signerAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            signer.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        val signerFailure = assertIs<ConversionBuildResult.Failure>(
            signer.coordinator().build(
                signerAnalysis.token,
                signerAnalysis.requiredApprovals,
                true,
                null,
            ),
        )
        assertEquals(ConversionFailureCode.IDENTITY_MISMATCH, signerFailure.code)
        assertEquals(ConversionJobStage.AWAITING_APPROVAL, signer.stage)
        assertTrue(signer.ownerAborted)

        val reserve = Fixture(temporaryFolder, mutableListOf()).apply {
            reserveFailure = IllegalStateException("reserve failed")
        }
        val reserveAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            reserve.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        assertIs<ConversionBuildResult.Failure>(
            reserve.coordinator().build(
                reserveAnalysis.token,
                reserveAnalysis.requiredApprovals,
                true,
                null,
            ),
        )
        assertEquals(ConversionJobStage.AWAITING_APPROVAL, reserve.stage)
        assertTrue(reserve.ownerAborted)
    }

    @Test
    fun `generated import 실패 뒤 restore가 같은 six를 다시 import하고 완료한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            importFailuresRemaining = 1
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        val failed = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals,
                true,
                null,
            ),
        )
        assertEquals(ConversionFailureCode.BUNDLE_IO, failed.code)
        assertTrue(requireNotNull(failed.generated).file.isFile)
        assertEquals(ConversionJobStage.BUILDING, fixture.stage)

        val restored = assertIs<RestoredConversion.BundleReady>(coordinator.restore())

        assertEquals(failed.generated, restored.generated)
        assertEquals(ConversionJobStage.BUNDLE_READY, fixture.stage)
        assertNull(fixture.activeOwner)
    }

    @Test
    fun `begin 뒤 원본 변이는 재분석에서 차단하고 reserve하지 않는다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            verifiedInputFailureCall = 2
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        val failure = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals,
                true,
                null,
            ),
        )

        assertEquals(ConversionFailureCode.INPUT_INVALID, failure.code)
        assertTrue("reserve" !in fixture.events)
    }

    @Test
    fun `prepare proof가 재분석 source와 다르면 reserve 전에 차단한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            preparedSourceSha256 = "9".repeat(64)
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        val failure = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(analysis.token, analysis.requiredApprovals, true, null),
        )

        assertEquals(ConversionFailureCode.TRANSFORM_MISMATCH, failure.code)
        assertTrue("reserve" !in fixture.events)
    }

    @Test
    fun `Keystore resolve 실패와 package 실패는 즉시 승인 단계와 빈 owner로 복구한다`() =
        runTest {
            val keystore = Fixture(temporaryFolder, mutableListOf()).apply {
                identityFailure = IllegalStateException("keystore unavailable")
            }
            val keystoreAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
                keystore.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )
            val keystoreFailure = assertIs<ConversionBuildResult.Failure>(
                keystore.coordinator().build(
                    keystoreAnalysis.token,
                    keystoreAnalysis.requiredApprovals,
                    true,
                    null,
                ),
            )
            assertEquals(ConversionFailureCode.KEYSTORE, keystoreFailure.code)
            assertEquals(ConversionJobStage.AWAITING_APPROVAL, keystore.stage)
            assertTrue(keystore.ownerAborted)

            val packaging = Fixture(temporaryFolder, mutableListOf()).apply {
                packageFailuresRemaining = 1
            }
            val packageAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
                packaging.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )
            val packageFailure = assertIs<ConversionBuildResult.Failure>(
                packaging.coordinator().build(
                    packageAnalysis.token,
                    packageAnalysis.requiredApprovals,
                    true,
                    null,
                ),
            )
            assertEquals(ConversionFailureCode.PACKAGE, packageFailure.code)
            assertEquals(ConversionJobStage.AWAITING_APPROVAL, packaging.stage)
            assertTrue(packaging.ownerAborted)
        }

    @Test
    fun `설치 version을 floor로 넘기고 package 실패 뒤 예약 version을 재사용하지 않는다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                installedWrapper = InstalledWrapper(
                    PACKAGE,
                    EXTENSION_ID,
                    "Example",
                    7,
                    "a".repeat(64),
                )
                nextVersionCode = 8
                packageFailuresRemaining = 1
            }
            val coordinator = fixture.coordinator()
            val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
                coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
            )

            assertIs<ConversionBuildResult.Failure>(
                coordinator.build(analysis.token, analysis.requiredApprovals, true, null),
            )
            assertIs<ConversionBuildResult.BundleReady>(
                coordinator.build(analysis.token, analysis.requiredApprovals, true, null),
            )

            assertEquals(listOf<Long?>(7, 7), fixture.installedVersionFloors)
            assertEquals(listOf(8, 9), fixture.reservedVersionCodes)
        }

    @Test
    fun `동시에 요청한 build는 package를 한 번에 하나만 실행한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            packageEntered = CompletableDeferred()
            packageRelease = CompletableDeferred()
            exposeAwaitingWhilePackaging = true
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        val first = async {
            coordinator.build(analysis.token, analysis.requiredApprovals, true, null)
        }
        requireNotNull(fixture.packageEntered).await()
        val second = async {
            coordinator.build(analysis.token, analysis.requiredApprovals, true, null)
        }
        requireNotNull(fixture.packageRelease).complete(Unit)

        assertIs<ConversionBuildResult.BundleReady>(first.await())
        assertIs<ConversionBuildResult.Failure>(second.await())
        assertEquals(1, fixture.maximumConcurrentPackages)
    }

    @Test
    fun `process restore는 승인 분석과 bundle ready 결과만 복구한다`() = runTest {
        val analysisFixture = Fixture(temporaryFolder, mutableListOf())
        val analysisCoordinator = analysisFixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            analysisCoordinator.begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        val restoredAnalysis =
            assertIs<RestoredConversion.Analysis>(analysisCoordinator.restore())
        assertEquals(analysis.token, restoredAnalysis.value.token)

        val readyFixture = Fixture(temporaryFolder, mutableListOf())
        val readyCoordinator = readyFixture.coordinator()
        val readyAnalysis = assertIs<ConversionBeginResult.AnalysisReady>(
            readyCoordinator.begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        val ready = assertIs<ConversionBuildResult.BundleReady>(
            readyCoordinator.build(
                readyAnalysis.token,
                readyAnalysis.requiredApprovals,
                true,
                null,
            ),
        )

        val restoredReady =
            assertIs<RestoredConversion.BundleReady>(readyCoordinator.restore())
        assertEquals(ready.generated, restoredReady.generated)
    }

    @Test
    fun `import 실패 결과를 취소하면 owner generated work를 함께 정리한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            importFailuresRemaining = 1
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )
        val failed = assertIs<ConversionBuildResult.Failure>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals,
                true,
                null,
            ),
        )
        assertTrue(requireNotNull(failed.generated).file.isFile)
        val saved = ByteArrayOutputStream()

        assertEquals("2".repeat(64), coordinator.copyGenerated(analysis.token, saved))
        assertEquals("bundle", saved.toString(Charsets.UTF_8.name()))
        assertTrue(failed.generated.file.isFile)

        assertTrue(coordinator.discard(analysis.token))

        assertTrue(fixture.ownerAborted)
        assertNull(fixture.activeOwner)
        assertTrue(!failed.generated.file.exists())
        assertTrue(fixture.workDiscarded)
    }

    private class Fixture(
        folder: TemporaryFolder,
        val events: MutableList<String>,
    ) {
        val token = "11111111-1111-4111-8111-111111111111"
        val inputFile = folder.newFile("${UUID.randomUUID()}.input")
            .apply { writeText("input") }
        private val generatedFile = folder.newFile("${UUID.randomUUID()}.six")
            .apply { writeText("bundle") }
        var currentProfile = profile()
        var profileResult: SamsungProfileSnapshot = SamsungProfileSnapshot.Supported(currentProfile)
        var stage = ConversionJobStage.INPUT_READY
            private set
        private var storedProfile: String? = null
        var inputFailure: Throwable? = null
        var compatibilityStatus = CompatibilityStatus.PARTIAL
        var logicalIdentity: LogicalSourceIdentity? =
            LogicalSourceIdentity.storeExtensionId(EXTENSION_ID)
        var installedWrapper: InstalledWrapper? = null
        var reserveFailure: Throwable? = null
        var identityFailure: Throwable? = null
        var importFailuresRemaining = 0
        var packageFailuresRemaining = 0
        var packageFailure: Throwable? = null
        var preparedSourceSha256 = "4".repeat(64)
        var verifiedInputFailureCall: Int? = null
        private var verifiedInputCalls = 0
        var nextVersionCode = 1
        val installedVersionFloors = mutableListOf<Long?>()
        val reservedVersionCodes = mutableListOf<Int>()
        var packageEntered: CompletableDeferred<Unit>? = null
        var packageRelease: CompletableDeferred<Unit>? = null
        var exposeAwaitingWhilePackaging = false
        private var concurrentPackages = 0
        var maximumConcurrentPackages = 0
            private set
        var activeOwner: String? = null
        var ownerAborted = false
        var workDiscarded = false

        fun coordinator() = OnDeviceConversionCoordinator(
            input = ConversionInputPort {
                events += "acquire"
                inputFailure?.let { throw it }
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
                    activeOwner = token
                }

                override suspend fun markBundleReady(
                    token: String,
                    bundle: File,
                    expectedSha256: String,
                ) {
                    events += "bundle-ready"
                    stage = ConversionJobStage.BUNDLE_READY
                    activeOwner = null
                }

                override suspend fun discard(token: String): Boolean {
                    workDiscarded = true
                    return true
                }

                override suspend fun resetAwaitingApproval(
                    token: String,
                    profileSha256: String,
                ) {
                    events += "reset-awaiting"
                    stage = ConversionJobStage.AWAITING_APPROVAL
                }

                override suspend fun <T> withVerifiedInputFile(
                    token: String,
                    consume: (File) -> T,
                ): T {
                    verifiedInputCalls += 1
                    if (verifiedInputCalls == verifiedInputFailureCall) {
                        throw IllegalStateException("verified source changed")
                    }
                    return consume(inputFile)
                }
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
                    identityFailure?.let { throw it }
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
                    reserveFailure?.let { throw it }
                    installedVersionFloors += installedVersionCode
                    val versionCode = nextVersionCode++
                    reservedVersionCodes += versionCode
                    return ReservedPackageIdentity(
                        resolved = resolved,
                        versionCode = versionCode,
                        opaque = Unit,
                    )
                }

                override suspend fun abort(ownerToken: String) {
                    events += "abort"
                    ownerAborted = true
                    activeOwner = null
                }
            },
            installed = ConversionInstalledPort {
                events += "installed"
                installedWrapper
            },
            packages = object : ConversionPackagePort {
                override suspend fun packageWrapper(
                    reservation: ReservedPackageIdentity,
                    bound: BoundConversionSnapshot,
                    token: String,
                ): PackagedConversion {
                    events += "package"
                    concurrentPackages += 1
                    maximumConcurrentPackages =
                        maxOf(maximumConcurrentPackages, concurrentPackages)
                    packageEntered?.complete(Unit)
                    try {
                        packageRelease?.await()
                        packageFailure?.let { throw it }
                        if (packageFailuresRemaining-- > 0) {
                            error("package failed")
                        }
                        return PackagedConversion(
                            extensionId = EXTENSION_ID,
                            packageName = PACKAGE,
                            versionCode = reservation.versionCode,
                            wrapperSha256 = "3".repeat(64),
                            outputApk = inputFile,
                            opaque = Unit,
                        )
                    } finally {
                        concurrentPackages -= 1
                    }
                }
            },
            bundles = object : ConversionBundlePort {
                override fun publish(token: String, packaged: PackagedConversion): GeneratedBundle {
                    events += "publish"
                    return GeneratedBundle(token, generatedFile, "2".repeat(64), "$token.six")
                }

                override fun recover(token: String): GeneratedBundle? =
                    GeneratedBundle(token, generatedFile, "2".repeat(64), "$token.six")

                override fun discard(token: String): Boolean = generatedFile.delete()

                override fun copyTo(token: String, output: java.io.OutputStream): String {
                    generatedFile.inputStream().use { it.copyTo(output) }
                    return "2".repeat(64)
                }
            },
            imports = ConversionGeneratedImportPort {
                events += "import"
                if (importFailuresRemaining-- > 0) {
                    null
                } else {
                    ReviewBundle(
                        token = "22222222-2222-4222-8222-222222222222",
                        packageName = PACKAGE,
                        extensionId = EXTENSION_ID,
                        name = "Example",
                        version = "1.0",
                        status = ContractCompatibilityStatus.PARTIAL,
                        issues = emptyList(),
                    )
                }
            },
        )

        private fun job() = RestoredConversionJob(
            token = token,
            inputKind = OnDeviceInputKind.CRX3,
            inputSha256 = "1".repeat(64),
            stage = if (exposeAwaitingWhilePackaging && concurrentPackages > 0) {
                ConversionJobStage.AWAITING_APPROVAL
            } else {
                stage
            },
            approvedAdapterIds = emptySet(),
            profileSha256 = storedProfile,
            bundleSha256 = null,
        )

        private fun analysis(profile: BrowserProfile) = ConversionAnalysisSnapshot(
            report = coreReport(profile, compatibilityStatus),
            selectedAutomaticAdapters = emptySet(),
            requiredApprovals = setOf("adapter"),
            logicalSourceIdentity = logicalIdentity,
        )

        private fun prepared(profile: BrowserProfile) = PreparedConversionSnapshot(
            reportBefore = coreReport(profile, compatibilityStatus).copy(
                sourceSha256 = preparedSourceSha256,
            ),
            originalSourceSha256 = "4".repeat(64),
            planSha256 = "5".repeat(64),
            transformedSourceZip = "zip".encodeToByteArray(),
            passthroughCrx = "crx".encodeToByteArray(),
            logicalSourceIdentity = logicalIdentity,
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
            sourceKind = "CRX3",
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

        private fun coreReport(
            profile: BrowserProfile,
            status: CompatibilityStatus = CompatibilityStatus.PARTIAL,
        ) = CompatibilityReport(
            status = status,
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
