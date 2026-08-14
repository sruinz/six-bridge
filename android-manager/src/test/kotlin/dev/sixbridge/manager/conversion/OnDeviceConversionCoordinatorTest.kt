package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnDeviceConversionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `begin은 reclaim acquire create release 뒤에만 분석한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf())

        val result = assertIs<ConversionBeginResult.AnalysisReady>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(fixture.token, result.token)
        assertEquals(
            listOf("reclaim", "acquire", "create", "release", "profile", "analyze", "awaiting"),
            fixture.events,
        )
        assertEquals(listOf(fixture.token), fixture.releasedTokens)
    }

    @Test
    fun `work create 실패도 exact input lease를 해제한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            workCreateFailure = IllegalStateException("create failed")
        }

        val result = assertIs<ConversionBeginResult.Failure>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(ConversionFailureCode.INPUT_INVALID, result.code)
        assertNull(result.token)
        assertEquals(listOf("reclaim", "acquire", "create", "release"), fixture.events)
        assertEquals(listOf(fixture.token), fixture.releasedTokens)
    }

    @Test
    fun `work create 취소도 lease를 해제하고 원래 cancellation을 보존한다`() = runTest {
        val cancellation = CancellationException("create cancelled")
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            workCreateFailure = cancellation
        }

        val thrown = assertFailsWith<CancellationException> {
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            )
        }

        assertEquals(cancellation, thrown)
        assertEquals(listOf("reclaim", "acquire", "create", "release"), fixture.events)
        assertEquals(listOf(fixture.token), fixture.releasedTokens)
    }

    @Test
    fun `create 성공 뒤 release 실패는 work token을 가진 INPUT_CLEANUP이다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            inputReleaseResult = false
        }

        val result = assertIs<ConversionBeginResult.Failure>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(ConversionFailureCode.INPUT_CLEANUP, result.code)
        assertEquals(fixture.token, result.token)
        assertEquals(listOf("reclaim", "acquire", "create", "release"), fixture.events)
        assertEquals(listOf(fixture.token), fixture.releasedTokens)
        assertTrue(fixture.inputFile.isFile)
    }

    @Test
    fun `create 실패와 release 실패가 겹치면 INPUT_CLEANUP으로 보존한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            workCreateFailure = IllegalStateException("create failed")
            inputReleaseResult = false
        }

        val result = assertIs<ConversionBeginResult.Failure>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(ConversionFailureCode.INPUT_CLEANUP, result.code)
        assertNull(result.token)
        assertEquals(listOf("reclaim", "acquire", "create", "release"), fixture.events)
    }

    @Test
    fun `post claim create 실패는 durable work token을 INPUT_INVALID에 보존한다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                workCreateFailure = ConversionWorkCreateException(CLAIMED_TOKEN)
            }

            val result = assertIs<ConversionBeginResult.Failure>(
                fixture.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )

            assertEquals(ConversionFailureCode.INPUT_INVALID, result.code)
            assertEquals(CLAIMED_TOKEN, result.token)
            assertEquals(listOf("reclaim", "acquire", "create", "release"), fixture.events)
        }

    @Test
    fun `post claim create와 release 실패는 durable work token을 INPUT_CLEANUP에 보존한다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                workCreateFailure = ConversionWorkCreateException(CLAIMED_TOKEN)
                inputReleaseResult = false
            }

            val result = assertIs<ConversionBeginResult.Failure>(
                fixture.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )

            assertEquals(ConversionFailureCode.INPUT_CLEANUP, result.code)
            assertEquals(CLAIMED_TOKEN, result.token)
        }

    @Test
    fun `release 대기 중 caller 취소는 cleanup 완료 뒤 INPUT_CLEANUP보다 우선한다`() =
        runBlocking {
            val releaseEntered = CountDownLatch(1)
            val allowRelease = CountDownLatch(1)
            val observed = AtomicReference<Any?>()
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                inputReleaseResult = false
                inputReleaseBlockingEntered = releaseEntered
                inputReleaseBlockingGate = allowRelease
            }
            val deferred = async(Dispatchers.Default) {
                observed.set(
                    try {
                        fixture.coordinator().begin(
                            OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                        )
                    } catch (failure: CancellationException) {
                        failure
                    },
                )
            }
            assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))

            deferred.cancel(CancellationException("caller cancelled"))
            allowRelease.countDown()
            deferred.join()

            assertIs<CancellationException>(observed.get())
            assertEquals(1, fixture.events.count { it == "release" })
            assertTrue(fixture.inputOrphaned)
            assertTrue("profile" !in fixture.events)
            assertTrue(fixture.abortedOwnerTokens.isEmpty())
            assertEquals(listOf(fixture.token), fixture.discardedBundleTokens)
            assertEquals(listOf(fixture.token), fixture.discardedTokens)
        }

    @Test
    fun `취소와 release 실패가 겹쳐도 cancellation을 우선하고 다음 begin이 reclaim한다`() =
        runTest {
            val cancellation = CancellationException("create cancelled")
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                workCreateFailure = cancellation
                inputReleaseResult = false
            }
            val coordinator = fixture.coordinator()

            assertFailsWith<CancellationException> {
                coordinator.begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                )
            }
            assertTrue(fixture.inputOrphaned)
            fixture.workCreateFailure = null
            fixture.inputReleaseResult = true
            assertIs<ConversionBeginResult.AnalysisReady>(
                coordinator.begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )

            assertEquals(2, fixture.events.count { it == "reclaim" })
            assertEquals(2, fixture.events.count { it == "release" })
            assertFalse(fixture.inputOrphaned)
        }

    @Test
    fun `reclaim 실패는 새 artifact를 만들기 전에 INPUT_CLEANUP으로 차단한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            inputReclaimResult = false
        }

        val result = assertIs<ConversionBeginResult.Failure>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(ConversionFailureCode.INPUT_CLEANUP, result.code)
        assertNull(result.token)
        assertEquals(listOf("reclaim"), fixture.events)
    }

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
                "reclaim",
                "acquire",
                "create",
                "release",
                "profile",
                "analyze",
                "awaiting",
                "restore-build-aware",
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
                "bundle-ready-job-commit",
                "identity-commit",
            ),
            events,
        )
        assertEquals(fixture.token, ready.generated.token)
        assertEquals("Example", ready.review.name)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(ready.review.wrapperSha256))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(ready.review.signerSha256))
        assertTrue(fixture.abortedOwnerTokens.isEmpty())
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
        assertEquals(listOf("reclaim", "acquire", "create", "release", "profile"), events)
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
    fun `분석의 선택적 승인은 coordinator build에서 허용한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            optionalApprovals = setOf("popup-native-autofill-guard")
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        assertEquals(fixture.optionalApprovals, analysis.optionalApprovals)
        assertIs<ConversionBuildResult.BundleReady>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals + analysis.optionalApprovals,
                partialConfirmed = true,
                identitySelection = null,
            ),
        )
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
    fun `analysis와 prepared identity mode 양방향 불일치는 reserve 전에 거부한다`() =
        runTest {
            listOf(
                InstallationIdentityMode.CONVERTED to
                    InstallationIdentityMode.PASSTHROUGH,
                InstallationIdentityMode.PASSTHROUGH to
                    InstallationIdentityMode.CONVERTED,
            ).forEach { (analysisMode, preparedMode) ->
                val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                    analysisIdentityMode = analysisMode
                    preparedIdentityMode = preparedMode
                    preparedPassthroughCrx = if (
                        preparedMode == InstallationIdentityMode.PASSTHROUGH
                    ) {
                        "crx".encodeToByteArray()
                    } else {
                        null
                    }
                }
                val coordinator = fixture.coordinator()
                val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
                    coordinator.begin(
                        OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                    ),
                )
                val expectedPreview = InstallationIdentityKey.create(
                    requireNotNull(fixture.logicalIdentity),
                    analysis.profile.majorVersion,
                    analysisMode,
                ).identitySha256.take(12)
                assertEquals(
                    expectedPreview,
                    assertIs<IdentityRequirement.Stable>(
                        analysis.identityRequirement,
                    ).identityKeyPreview,
                )

                val failure = assertIs<ConversionBuildResult.Failure>(
                    coordinator.build(
                        analysis.token,
                        analysis.requiredApprovals,
                        partialConfirmed = true,
                        identitySelection = null,
                    ),
                )

                assertEquals(ConversionFailureCode.TRANSFORM_MISMATCH, failure.code)
                assertEquals(0, fixture.events.count { it == "identity" })
                assertEquals(0, fixture.events.count { it == "reserve" })
                assertTrue("building" !in fixture.events)
                assertNull(fixture.activeOwner)
            }
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
    fun `raw IllegalArgumentException package validation 실패는 PACKAGE다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            packageFailure = IllegalArgumentException("package validation failed")
        }
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        val failure = assertIs<ConversionBuildResult.Failure>(
            fixture.coordinator().build(
                analysis.token,
                analysis.requiredApprovals,
                true,
                null,
            ),
        )

        assertEquals(ConversionFailureCode.PACKAGE, failure.code)
    }

    @Test
    fun `동일한 message도 명시된 domain type으로만 분류한다`() = runTest {
        data class Case(
            val expected: ConversionFailureCode,
            val configure: Fixture.() -> Unit,
        )

        val cases = listOf(
            Case(ConversionFailureCode.IDENTITY_MISMATCH) {
                identityFailure = ConversionIdentityException(
                    IllegalArgumentException("same failure"),
                )
            },
            Case(ConversionFailureCode.KEYSTORE) {
                identityFailure = ConversionKeyStoreException(
                    IllegalArgumentException("same failure"),
                )
            },
            Case(ConversionFailureCode.PACKAGE) {
                packageFailure = ConversionPackageException(
                    IllegalArgumentException("same failure"),
                )
            },
            Case(ConversionFailureCode.BUNDLE_IO) {
                bundleFailure = ConversionBundleException(
                    IllegalArgumentException("same failure"),
                )
            },
            Case(ConversionFailureCode.BUNDLE_IO) {
                importFailure = ConversionBundleException(
                    IllegalArgumentException("same failure"),
                )
            },
            Case(ConversionFailureCode.PACKAGE) {
                identityFailure = IllegalStateException("unclassified failure")
            },
        )

        cases.forEach { case ->
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply(case.configure)
            val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
                fixture.coordinator().begin(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )

            val failure = assertIs<ConversionBuildResult.Failure>(
                fixture.coordinator().build(
                    analysis.token,
                    analysis.requiredApprovals,
                    true,
                    null,
                ),
            )

            assertEquals(case.expected, failure.code, "expected=${case.expected}")
        }
    }

    @Test
    fun `blocked와 미확인 partial은 reserve 전에 차단한다`() = runTest {
        val blocked = Fixture(temporaryFolder, mutableListOf()).apply {
            compatibilityStatus = CompatibilityStatus.BLOCKED
        }
        val blockedFailure = assertIs<ConversionBeginResult.Failure>(
            blocked.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        assertEquals(ConversionFailureCode.BLOCKED, blockedFailure.code)
        assertTrue("reserve" !in blocked.events)

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
    fun `UNKNOWN 분석은 일반 실패로 축약하지 않고 빌드 불가 결과를 반환한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            compatibilityStatus = CompatibilityStatus.UNKNOWN
        }

        val result = assertIs<ConversionBeginResult.AnalysisReady>(
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )

        assertEquals(CompatibilityStatus.UNKNOWN, result.report.status)
    }

    @Test
    fun `분석 중 caller 취소는 모바일 control checkpoint에 전달된다`() = runBlocking {
        val analysisEntered = CountDownLatch(1)
        val allowAnalysis = CountDownLatch(1)
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            analysisBlockingEntered = analysisEntered
            analysisBlockingGate = allowAnalysis
        }
        val deferred = async(Dispatchers.Default) {
            fixture.coordinator().begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            )
        }
        assertTrue(analysisEntered.await(5, TimeUnit.SECONDS))

        deferred.cancel(CancellationException("caller cancelled"))
        allowAnalysis.countDown()
        deferred.join()

        assertEquals(AnalysisSafetyPolicy.mobile, fixture.analysisPolicy)
        assertTrue(fixture.analysisCancellationObserved)
        assertTrue(fixture.abortedOwnerTokens.isEmpty())
        assertEquals(listOf(fixture.token), fixture.discardedBundleTokens)
        assertEquals(listOf(fixture.token), fixture.discardedTokens)
    }

    @Test
    fun `분석 취소는 identity failed-attempt release와 분리해 정리한다`() =
        runBlocking {
            val analysisEntered = CountDownLatch(1)
            val allowAnalysis = CountDownLatch(1)
            val observed = AtomicReference<CancellationException>()
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                analysisBlockingEntered = analysisEntered
                analysisBlockingGate = allowAnalysis
                identityAbortFailure = IllegalStateException("abort failed")
            }
            val deferred = async(Dispatchers.Default) {
                try {
                    fixture.coordinator().begin(
                        OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                    )
                } catch (error: CancellationException) {
                    observed.set(error)
                }
            }
            assertTrue(analysisEntered.await(5, TimeUnit.SECONDS))

            deferred.cancel(CancellationException("caller cancelled"))
            allowAnalysis.countDown()
            deferred.join()

            assertIs<CancellationException>(observed.get())
            assertTrue(fixture.abortedOwnerTokens.isEmpty())
            assertEquals(listOf(fixture.token), fixture.discardedBundleTokens)
            assertEquals(listOf(fixture.token), fixture.discardedTokens)
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
        assertTrue(fixture.abortedOwnerTokens.isEmpty())
        assertNull(fixture.activeOwner)
    }

    @Test
    fun `import 실패 직후 build 재호출은 normal restore로 BUILDING을 파괴하지 않는다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                importFailuresRemaining = 1
                normalRestoreDestroysBuilding = true
            }
            val coordinator = fixture.coordinator()
            val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
                coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
            )
            assertIs<ConversionBuildResult.Failure>(
                coordinator.build(analysis.token, analysis.requiredApprovals, true, null),
            )

            val retried = assertIs<ConversionBuildResult.BundleReady>(
                coordinator.build(analysis.token, analysis.requiredApprovals, true, null),
            )

            assertEquals(fixture.token, retried.generated.token)
            assertEquals(ConversionJobStage.BUNDLE_READY, fixture.stage)
            assertTrue(!fixture.normalRestoreDestroyedBuilding)
        }

    @Test
    fun `import 실패 직후 analyze와 재생성 build도 BUILDING generated를 조정한다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                importFailuresRemaining = 2
                normalRestoreDestroysBuilding = true
            }
            val first = fixture.coordinator()
            val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
                first.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
            )
            assertIs<ConversionBuildResult.Failure>(
                first.build(analysis.token, analysis.requiredApprovals, true, null),
            )

            val analyzed = assertIs<ConversionBeginResult.Failure>(
                first.analyze(analysis.token),
            )
            assertEquals(ConversionFailureCode.BUNDLE_IO, analyzed.code)
            assertEquals(ConversionJobStage.BUILDING, fixture.stage)
            assertTrue(!fixture.normalRestoreDestroyedBuilding)

            val recreated = fixture.coordinator()
            assertIs<ConversionBuildResult.BundleReady>(
                recreated.build(analysis.token, analysis.requiredApprovals, true, null),
            )
            assertEquals(ConversionJobStage.BUNDLE_READY, fixture.stage)
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
                identityFailure = ConversionKeyStoreException(
                    IllegalStateException("keystore unavailable"),
                )
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
    fun `미완료 stage는 restore에서 분석하지 않고 exact token을 폐기한다`() = runTest {
        listOf(
            ConversionJobStage.INPUT_READY,
            ConversionJobStage.AWAITING_APPROVAL,
            ConversionJobStage.BUILDING,
            ConversionJobStage.DISCARDING,
        ).forEach { restoredStage ->
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                this.restoredStage = restoredStage
                recoveredGenerated = null
            }

            assertIs<RestoredConversion.InterruptedDiscarded>(
                fixture.coordinator().restore(),
            )
            assertEquals(0, fixture.analyzeCalls)
            assertTrue(fixture.abortedOwnerTokens.isEmpty())
            assertEquals(listOf(fixture.token), fixture.discardedBundleTokens)
            assertEquals(listOf(fixture.token), fixture.discardedTokens)
        }
    }

    @Test
    fun `미완료 exact token cleanup 실패는 pending으로 남기고 restore에서 재시도한다`() =
        runTest {
            val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                restoredStage = ConversionJobStage.AWAITING_APPROVAL
                recoveredGenerated = null
                bundleDiscardResult = true
                workDiscardResult = false
            }
            val coordinator = fixture.coordinator()

            val pending = assertIs<RestoredConversion.InterruptedCleanupPending>(
                coordinator.restore(),
            )

            assertEquals(fixture.token, pending.token)
            assertEquals(0, fixture.analyzeCalls)
            assertEquals(listOf(fixture.token), fixture.discardedTokens)

            fixture.workDiscardResult = true
            assertIs<RestoredConversion.InterruptedDiscarded>(coordinator.restore())
            assertEquals(listOf(fixture.token, fixture.token), fixture.discardedTokens)
        }

    @Test
    fun `BUILDING generated proof는 import 정책에 따라 기존 완료 결과를 복구한다`() =
        runTest {
            listOf(true, false).forEach { importReview ->
                val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
                    restoredStage = ConversionJobStage.BUILDING
                }

                val restored = fixture.coordinator().restore(importReview)

                if (importReview) {
                    assertIs<RestoredConversion.BundleReady>(restored)
                    assertEquals(ConversionJobStage.BUNDLE_READY, fixture.stage)
                } else {
                    assertIs<RestoredConversion.GeneratedPending>(restored)
                    assertEquals(ConversionJobStage.INPUT_READY, fixture.stage)
                }
                assertEquals(0, fixture.analyzeCalls)
                assertTrue(fixture.discardedTokens.isEmpty())
            }
        }

    @Test
    fun `BUNDLE_READY는 기존 generated와 review를 보존한다`() = runTest {
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
        assertEquals(0, readyFixture.discardedTokens.size)
    }

    @Test
    fun `startup cache cleanup은 첫 restore의 work 조회 전에 한 번만 실행한다`() = runTest {
        val events = mutableListOf<String>()
        val warnings = mutableListOf<AnalysisCacheCleanupResult>()
        val fixture = Fixture(temporaryFolder, events).apply {
            restoredStage = ConversionJobStage.BUNDLE_READY
        }
        val coordinator = fixture.coordinator(
            analysisCacheCleaner = {
                events += "clean-cache"
                AnalysisCacheCleanupResult(deleted = 2, failed = 1)
            },
            onAnalysisCacheCleanupWarning = warnings::add,
        )

        assertIs<RestoredConversion.BundleReady>(coordinator.restore())
        assertIs<RestoredConversion.BundleReady>(coordinator.restore())

        assertEquals(1, events.count { it == "clean-cache" })
        assertTrue(events.indexOf("clean-cache") < events.indexOf("restore-build-aware"))
        assertEquals(
            listOf(AnalysisCacheCleanupResult(deleted = 2, failed = 1)),
            warnings,
        )
    }

    @Test
    fun `conversion cleanup 복원은 generated를 새 review로 import하지 않는다`() = runTest {
        val events = mutableListOf<String>()
        val fixture = Fixture(temporaryFolder, events)
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(
                OnDeviceInputRequest.ChromeStore("https://example.invalid"),
            ),
        )
        val ready = assertIs<ConversionBuildResult.BundleReady>(
            coordinator.build(
                analysis.token,
                analysis.requiredApprovals,
                true,
                null,
            ),
        )
        events.clear()

        val restored = assertIs<RestoredConversion.GeneratedPending>(
            coordinator.restore(importReview = false),
        )

        assertEquals(ready.generated, restored.generated)
        assertTrue("import" !in events)
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

        assertFalse(fixture.ownerAborted)
        assertEquals(analysis.token, fixture.activeOwner)
        assertTrue(!failed.generated.file.exists())
        assertTrue(fixture.workDiscarded)
    }

    @Test
    fun `손상 generated는 recover되지 않아도 명시적 discard가 제거한다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            bundleRecoverReturnsNull = true
        }
        val coordinator = fixture.coordinator()
        val analysis = assertIs<ConversionBeginResult.AnalysisReady>(
            coordinator.begin(OnDeviceInputRequest.ChromeStore("https://example.invalid")),
        )

        assertTrue(coordinator.discard(analysis.token))

        assertTrue(!fixture.generatedFile.isFile)
        assertTrue(fixture.workDiscarded)
    }

    @Test
    fun `discard는 bundle cleanup false를 work cleanup true로 숨기지 않는다`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            bundleDiscardResult = false
        }

        assertTrue(fixture.generatedFile.isFile)
        assertTrue(fixture.coordinator().discard(fixture.token).not())
        assertTrue(fixture.workDiscarded)
    }

    @Test
    fun `generated discard waits for physical other work cleanup`() = runTest {
        val fixture = Fixture(temporaryFolder, mutableListOf()).apply {
            generatedFile.delete()
            bundleDiscardResult = true
            workDiscardResult = false
        }

        assertTrue(!fixture.coordinator().discard(fixture.token))
        assertTrue(fixture.workDiscarded)

        fixture.workDiscardResult = true
        assertTrue(fixture.coordinator().discard(fixture.token))
    }

    private class Fixture(
        folder: TemporaryFolder,
        val events: MutableList<String>,
    ) {
        val token = "11111111-1111-4111-8111-111111111111"
        val inputFile = folder.newFile("${UUID.randomUUID()}.input")
            .apply { writeText("input") }
        val generatedFile = folder.newFile("${UUID.randomUUID()}.six")
            .apply { writeText("bundle") }
        var currentProfile = profile()
        var profileResult: SamsungProfileSnapshot = SamsungProfileSnapshot.Supported(currentProfile)
        var restoredStage: ConversionJobStage? = null
        var stage = ConversionJobStage.INPUT_READY
            private set
        private var storedProfile: String? = null
        var inputFailure: Throwable? = null
        var inputReleaseResult = true
        var inputReclaimResult = true
        var inputOrphaned = false
        var inputReleaseBlockingEntered: CountDownLatch? = null
        var inputReleaseBlockingGate: CountDownLatch? = null
        val releasedTokens = mutableListOf<String>()
        var workCreateFailure: Throwable? = null
        var compatibilityStatus = CompatibilityStatus.PARTIAL
        var analysisBlockingEntered: CountDownLatch? = null
        var analysisBlockingGate: CountDownLatch? = null
        var analysisPolicy: AnalysisSafetyPolicy? = null
        var analysisCancellationObserved = false
        var logicalIdentity: LogicalSourceIdentity? =
            LogicalSourceIdentity.storeExtensionId(EXTENSION_ID)
        var analysisIdentityMode = InstallationIdentityMode.PASSTHROUGH
        var preparedIdentityMode = InstallationIdentityMode.PASSTHROUGH
        var preparedPassthroughCrx: ByteArray? = "crx".encodeToByteArray()
        var installedWrapper: InstalledWrapper? = null
        var reserveFailure: Throwable? = null
        var identityFailure: Throwable? = null
        var identityAbortFailure: Throwable? = null
        var importFailuresRemaining = 0
        var packageFailuresRemaining = 0
        var optionalApprovals: Set<String> = emptySet()
        var packageFailure: Throwable? = null
        var bundleFailure: Throwable? = null
        var importFailure: Throwable? = null
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
        var normalRestoreDestroysBuilding = false
        var normalRestoreDestroyedBuilding = false
        var bundleRecoverReturnsNull = false
        var recoveredGenerated: GeneratedBundle? =
            GeneratedBundle(token, generatedFile, "2".repeat(64), "$token.six")
        var bundleDiscardResult: Boolean? = null
        var activeOwner: String? = null
        var ownerAborted = false
        val abortedOwnerTokens = mutableListOf<String>()
        val discardedBundleTokens = mutableListOf<String>()
        val discardedTokens = mutableListOf<String>()
        var analyzeCalls = 0
        var workDiscarded = false
        var workDiscardResult = true

        fun coordinator(
            analysisCacheCleaner: suspend () -> AnalysisCacheCleanupResult = {
                AnalysisCacheCleanupResult(deleted = 0, failed = 0)
            },
            onAnalysisCacheCleanupWarning: (AnalysisCacheCleanupResult) -> Unit = {},
        ) = OnDeviceConversionCoordinator(
            input = object : ConversionInputPort {
                override suspend fun acquire(
                    request: OnDeviceInputRequest,
                ): OnDeviceInputArtifact {
                    events += "acquire"
                    inputFailure?.let { throw it }
                    return OnDeviceInputArtifact(
                        token,
                        OnDeviceInputKind.CRX3,
                        inputFile,
                        "1".repeat(64),
                        EXTENSION_ID,
                    )
                }

                override suspend fun release(artifact: OnDeviceInputArtifact): Boolean {
                    events += "release"
                    releasedTokens += artifact.token
                    inputReleaseBlockingEntered?.countDown()
                    inputReleaseBlockingGate?.await(5, TimeUnit.SECONDS)
                    if (!inputReleaseResult) inputOrphaned = true
                    return inputReleaseResult
                }

                override suspend fun reclaimOrphans(): Boolean {
                    events += "reclaim"
                    if (inputReclaimResult) inputOrphaned = false
                    return inputReclaimResult
                }
            },
            work = object : ConversionWorkPort {
                override suspend fun create(artifact: OnDeviceInputArtifact): RestoredConversionJob {
                    events += "create"
                    workCreateFailure?.let { throw it }
                    return job()
                }

                override suspend fun restore(): RestoredConversionJob? {
                    events += "restore"
                    if (
                        normalRestoreDestroysBuilding &&
                        stage == ConversionJobStage.BUILDING
                    ) {
                        normalRestoreDestroyedBuilding = true
                        stage = ConversionJobStage.INPUT_READY
                        storedProfile = null
                    }
                    return job()
                }

                override suspend fun restoreBuildAware(): RestoredConversionJob {
                    events += "restore-build-aware"
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
                    events += "bundle-ready-job-commit"
                    stage = ConversionJobStage.BUNDLE_READY
                    activeOwner = null
                    events += "identity-commit"
                }

                override suspend fun discard(token: String): Boolean {
                    workDiscarded = true
                    discardedTokens += token
                    return workDiscardResult
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
                    control: AnalysisControl,
                ): ConversionAnalysisSnapshot {
                    events += "analyze"
                    analyzeCalls += 1
                    analysisPolicy = control.policy
                    analysisBlockingEntered?.countDown()
                    analysisBlockingGate?.await(5, TimeUnit.SECONDS)
                    try {
                        control.checkCancelled()
                    } catch (error: CancellationException) {
                        analysisCancellationObserved = true
                        throw error
                    }
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

                override suspend fun releaseFailedAttempt(ownerToken: String) {
                    events += "abort"
                    abortedOwnerTokens += ownerToken
                    identityAbortFailure?.let { throw it }
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
                    bundleFailure?.let { throw it }
                    return GeneratedBundle(token, generatedFile, "2".repeat(64), "$token.six")
                }

                override fun recover(token: String): GeneratedBundle? =
                    if (bundleRecoverReturnsNull) {
                        null
                    } else {
                        recoveredGenerated
                    }

                override fun discard(token: String): Boolean {
                    discardedBundleTokens += token
                    val deleted = generatedFile.delete()
                    return bundleDiscardResult ?: deleted
                }

                override fun copyTo(token: String, output: java.io.OutputStream): String {
                    generatedFile.inputStream().use { it.copyTo(output) }
                    return "2".repeat(64)
                }
            },
            imports = ConversionGeneratedImportPort {
                events += "import"
                importFailure?.let { throw it }
                if (importFailuresRemaining-- > 0) {
                    null
                } else {
                    ReviewBundle(
                        token = "22222222-2222-4222-8222-222222222222",
                        packageName = PACKAGE,
                        extensionId = EXTENSION_ID,
                        originalExtensionId = null,
                        wrapperSha256 =
                            "2222222222222222222222222222222222222222222222222222222222222222",
                        signerSha256 =
                            "3333333333333333333333333333333333333333333333333333333333333333",
                        name = "Example",
                        version = "1.0",
                        status = ContractCompatibilityStatus.PARTIAL,
                        issues = emptyList(),
                    )
                }
            },
            analysisCacheCleaner = analysisCacheCleaner,
            onAnalysisCacheCleanupWarning = onAnalysisCacheCleanupWarning,
        )

        private fun job() = RestoredConversionJob(
            token = token,
            inputKind = OnDeviceInputKind.CRX3,
            inputSha256 = "1".repeat(64),
            stage = restoredStage ?: if (exposeAwaitingWhilePackaging && concurrentPackages > 0) {
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
            optionalApprovals = optionalApprovals,
            logicalSourceIdentity = logicalIdentity,
            identityMode = analysisIdentityMode,
        )

        private fun prepared(profile: BrowserProfile) = PreparedConversionSnapshot(
            reportBefore = coreReport(profile, compatibilityStatus).copy(
                sourceSha256 = preparedSourceSha256,
            ),
            originalSourceSha256 = "4".repeat(64),
            planSha256 = "5".repeat(64),
            transformedSourceZip = "zip".encodeToByteArray(),
            passthroughCrx = preparedPassthroughCrx,
            logicalSourceIdentity = logicalIdentity,
            identityMode = preparedIdentityMode,
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
        private val CLAIMED_TOKEN = "77777777-7777-4777-8777-777777777777"

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
