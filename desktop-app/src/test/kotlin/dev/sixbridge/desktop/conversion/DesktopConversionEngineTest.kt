package dev.sixbridge.desktop.conversion

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.draft.DraftSignature
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.input.DesktopInputSealer
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopConversionEngineTest {
    @Test
    fun `desktop engine은 typed authorization을 재검증하고 서명된 v4 provenance만 반환한다`() {
        Fixture().use { fixture ->
            val denied = fixture.engine.analyze(
                DesktopExtensionInput.Local(fixture.dynamicSource),
                profile,
                AnalysisControl(),
            )
            assertEquals(CompatibilityStatus.UNKNOWN, denied.report.status)
            assertFailsWith<DesktopConversionException> {
                fixture.engine.buildDraft(
                    denied,
                    emptySet(),
                    null,
                    ConversionAuthorization.NONE,
                    AnalysisControl(),
                )
            }
            assertFalse(fixture.engine.release(denied.token))

            val approved = fixture.engine.analyze(
                DesktopExtensionInput.Local(fixture.dynamicSource),
                profile,
                AnalysisControl(),
            )
            val artifact = fixture.engine.buildDraft(
                approved,
                emptySet(),
                null,
                ConversionAuthorization.forceUnknownApiUsage(),
                AnalysisControl(),
            )
            val verified = SixDraft.read(artifact.privateFile)
            assertEquals(4, verified.payload.draft.schemaVersion)
            assertEquals(2, verified.payload.plan.schemaVersion)
            assertEquals(CompatibilityStatus.UNKNOWN, verified.payload.compatibilityBefore.status)
            assertEquals(listOf("api_usage_dynamic"), artifact.forcedCompatibility?.issueCodes)
            assertEquals(verified.payload.plan.forcedCompatibility, artifact.forcedCompatibility)
            assertFailsWith<UnsupportedOperationException> {
                (requireNotNull(artifact.forcedCompatibility).issueCodes as MutableList<String>) +=
                    "forged"
            }
            assertTrue(fixture.engine.release(approved.token))
        }
    }

    @Test
    fun `불필요하거나 강제 불가한 authorization은 service 출력 전에 거부한다`() {
        Fixture().use { fixture ->
            listOf(fixture.source, fixture.blockedSource).forEach { source ->
                val session = fixture.engine.analyze(
                    DesktopExtensionInput.Local(source),
                    profile,
                    AnalysisControl(),
                )
                assertFailsWith<DesktopConversionException> {
                    fixture.engine.buildDraft(
                        session,
                        emptySet(),
                        null,
                        ConversionAuthorization.forceUnknownApiUsage(),
                        AnalysisControl(),
                    )
                }
                assertFalse(fixture.engine.release(session.token))
            }
        }
    }

    @Test
    fun `분석은 자동 adapter와 승인 adapter를 분리한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.popupSource), profile, AnalysisControl())
            assertEquals(session.report.adapterDecisions.filter { it.disposition.name == "AUTOMATIC" }.mapTo(sortedSetOf()) { it.id }, session.selectedAutomaticAdapters)
            assertEquals(session.report.adapterDecisions.filter { it.disposition.name == "REQUIRES_APPROVAL" }.mapTo(sortedSetOf()) { it.id }, session.requiredApprovals)
            assertEquals(session.report.adapterDecisions.filter { it.disposition.name == "OPTIONAL_APPROVAL" }.mapTo(sortedSetOf()) { it.id }, session.optionalApprovals)
            assertTrue(fixture.engine.release(session.token))
            assertFalse(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `선택적 adapter 승인은 build validation을 통과한다`() {
        Fixture(
            hooks = DesktopConversionEngineTestHooks(
                beforeDraft = { throw IllegalStateException("validation passed") },
            ),
        ).use { fixture ->
            val session = fixture.engine.analyze(
                DesktopExtensionInput.Local(fixture.popupSource),
                profile,
                AnalysisControl(),
            )
            val optional = session.optionalApprovals.single()

            val result = assertFailsWith<IllegalStateException> {
                fixture.engine.buildDraft(
                    session,
                    session.requiredApprovals + optional,
                    null,
                    AnalysisControl(),
                )
            }

            assertEquals("validation passed", result.message)
            assertFalse(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `분석 뒤 원본을 바꿔도 build는 sealed portable snapshot만 사용한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            fixture.source.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"mutated","version":"9"}""")
            val artifact = fixture.engine.buildDraft(session, session.requiredApprovals, "b".repeat(32), AnalysisControl())
            val verified = SixDraft.read(artifact.privateFile)
            assertEquals(2, verified.payload.draft.schemaVersion)
            assertEquals(1, verified.payload.plan.schemaVersion)
            assertEquals(null, verified.payload.plan.forcedCompatibility)
            assertEquals(null, artifact.forcedCompatibility)
            assertEquals(DraftSourceKind.ZIP, verified.payload.sourceKind)
            assertNotEquals("mutated", verified.payload.compatibilityBefore.extensionName)
            assertEquals(DraftSignature.sha256(Files.readAllBytes(artifact.privateFile)), artifact.sha256)
            assertEquals(DraftSignature.fingerprint(fixture.signer.public.encoded), artifact.signerFingerprint)
            assertFalse(Files.exists(artifact.privateFile.fileSystem.getPath("/sixbridge/${session.token}/.local/keystore/crx")))
            assertTrue(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `build는 변경 가능한 profile과 승인 공개 session 대신 분석 snapshot을 사용한다`() {
        Fixture().use { fixture ->
            val mutableFeatures = profile.supportedFeatures.toMutableSet()
            val mutableDifferences = mutableListOf<String>()
            val mutableProfile = profile.copy(
                supportedFeatures = mutableFeatures,
                profileDifferences = mutableDifferences,
            )
            val session = fixture.engine.analyze(
                DesktopExtensionInput.Local(fixture.popupSource),
                mutableProfile,
                AnalysisControl(),
            )
            val expectedProfileHash = session.profile.canonicalSha256()
            mutableFeatures += "changed-after-analysis"
            mutableDifferences += "changed-after-analysis"
            assertFailsWith<UnsupportedOperationException> {
                (session.requiredApprovals as MutableSet<String>).clear()
            }
            val artifact = fixture.engine.buildDraft(
                session,
                session.requiredApprovals,
                null,
                AnalysisControl(),
            )
            assertEquals(expectedProfileHash, SixDraft.read(artifact.privateFile).payload.compatibilityBefore.browserProfileSha256)
            assertTrue(fixture.engine.release(session.token))
            assertEquals(expectedProfileHash, session.profile.canonicalSha256())
        }
    }

    @Test
    fun `suggested filename은 unicode control을 치환하고 surrogate pair를 보존한다`() {
        Fixture().use { fixture ->
            val name = "a".repeat(111) + "😀" + '\u007f' + '\u0085'
            val source = fixture.sourceWithName(name)
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(source), profile, AnalysisControl())
            val artifact = fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl())

            assertTrue(artifact.suggestedFileName.length <= 120)
            assertFalse(artifact.suggestedFileName.any(Char::isISOControl))
            assertFalse(artifact.suggestedFileName.dropLast(DRAFT_EXTENSION.length).last().isHighSurrogate())
            assertTrue(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `차단 또는 unknown session은 draft 생성 전에 정리한다`() {
        Fixture().use { fixture ->
            listOf(
                DesktopExtensionInput.Local(fixture.blockedSource) to profile,
                DesktopExtensionInput.Local(fixture.source) to profile.copy(profileDifferences = listOf("unverified")),
            ).forEach { (input, selectedProfile) ->
                val actual = fixture.engine.analyze(input, selectedProfile, AnalysisControl())
                assertTrue(actual.report.status in setOf(CompatibilityStatus.BLOCKED, CompatibilityStatus.UNKNOWN))
                assertFailsWith<DesktopConversionException> { fixture.engine.buildDraft(actual, emptySet(), null, AnalysisControl()) }
                assertFalse(fixture.engine.release(actual.token))
            }
        }
    }

    @Test
    fun `승인 집합과 위조 session을 거부하고 lease를 한 번만 정리한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.popupSource), profile, AnalysisControl())
            assertTrue(session.requiredApprovals.isNotEmpty())
            assertFailsWith<DesktopConversionException> { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertFalse(fixture.engine.release(session.token))
            val second = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            assertFailsWith<DesktopConversionException> { fixture.engine.buildDraft(second.copy(input = DesktopExtensionInput.Local(fixture.popupSource)), second.requiredApprovals, null, AnalysisControl()) }
            assertFalse(fixture.engine.release(second.token))
        }
    }

    @Test
    fun `build 취소와 close는 session을 정리하고 같은 token을 재해제하지 않는다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            assertFailsWith<CancellationException> { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl { throw CancellationException("deadline") }) }
            assertFalse(fixture.engine.release(session.token))
            val active = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            fixture.engine.close()
            fixture.engine.close()
            assertFalse(fixture.engine.release(active.token))
        }
    }

    @Test
    fun `portable JSON은 동일 sealed fixture의 core service와 결정적으로 일치한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            val artifact = fixture.engine.buildDraft(session, emptySet(), "b".repeat(32), AnalysisControl())
            val desktop = SixDraft.read(artifact.privateFile)
            fixture.manager.create().use { lease ->
                val resolved = fixture.engine.resolvedInputForTesting(session)
                val core = SixBridgeService(lease.workspace.root, secureConversionRoot = lease.secureRoot, draftSigningIdentityProvider = fixture.provider)
                val output = lease.workspace.artifactRoot.resolve("core.sixdraft")
                core.draftPortableResolved(resolved, profile, output, "b".repeat(32), emptySet(), emptyList(), AnalysisControl())
                val direct = SixDraft.read(output)
                assertContentEquals(direct.payload.conversionPlanJson, desktop.payload.conversionPlanJson)
                assertContentEquals(direct.payload.compatibilityBeforeJson, desktop.payload.compatibilityBeforeJson)
                assertContentEquals(direct.payload.compatibilityAfterJson, desktop.payload.compatibilityAfterJson)
                assertContentEquals(direct.payload.expectedJson, desktop.payload.expectedJson)
            }
            assertTrue(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `report 밖 extra approval은 build 전에 거부하고 정확히 한 번 정리한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.popupSource), profile, AnalysisControl())
            assertFailsWith<DesktopConversionException> {
                fixture.engine.buildDraft(session, session.requiredApprovals + "not-in-report", null, AnalysisControl())
            }
            assertFalse(fixture.engine.release(session.token))
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `build result source SHA mismatch는 artifact를 반환하지 않고 정리한다`() {
        Fixture(hooks = DesktopConversionEngineTestHooks(sourceSha256Override = { "0".repeat(64) })).use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            assertFailsWith<DesktopConversionException> { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertFalse(fixture.engine.release(session.token))
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `internal build failure은 session을 정리하고 원인 오류를 보존한다`() {
        Fixture(hooks = DesktopConversionEngineTestHooks(beforeDraft = { throw IOException("injected build failure") })).use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            val failure = assertFailsWith<IOException> { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertEquals("injected build failure", failure.message)
            assertFalse(fixture.engine.release(session.token))
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `build failure 중 cleanup exception은 suppressed로 보존한다`() {
        val hooks = DesktopConversionEngineTestHooks(
            beforeDraft = { throw IOException("primary failure") },
            cleanupOverride = { throw IllegalStateException("cleanup failure") },
        )
        Fixture(hooks = hooks).use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            val failure = assertFailsWith<IOException> { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertEquals("cleanup failure", failure.suppressed.single().message)
            assertFalse(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `explicit cancellation과 release 경쟁은 map first로 한 번만 정리한다`() {
        cancellationReleaseRace("explicit cancellation")
    }

    @Test
    fun `deadline cancellation과 release 경쟁은 map first로 한 번만 정리한다`() {
        cancellationReleaseRace("deadline cancellation")
    }

    @Test
    fun `build와 release 경쟁은 성공 artifact를 release 전까지 유지한다`() {
        val entered = CountDownLatch(1)
        val continueBuild = CountDownLatch(1)
        val releaseLookedUp = CountDownLatch(1)
        val continueRelease = CountDownLatch(1)
        val lookups = AtomicInteger()
        val hooks = DesktopConversionEngineTestHooks(beforeDraft = {
            entered.countDown()
            check(continueBuild.await(10, TimeUnit.SECONDS))
        }, afterContextLookup = {
            if (lookups.incrementAndGet() == 2) {
                releaseLookedUp.countDown()
                check(continueRelease.await(10, TimeUnit.SECONDS))
            }
        })
        Fixture(hooks = hooks).use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            var artifact: DesktopDraftArtifact? = null
            val build = thread { artifact = fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            var released: Boolean? = null
            val release = thread { released = fixture.engine.release(session.token) }
            assertTrue(releaseLookedUp.await(10, TimeUnit.SECONDS))
            continueBuild.countDown()
            build.join(10_000)
            assertTrue(Files.isRegularFile(requireNotNull(artifact).privateFile))
            continueRelease.countDown()
            release.join(10_000)
            assertTrue(released == true)
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `build와 close 경쟁은 build가 끝난 뒤 단 한 번만 정리한다`() {
        val entered = CountDownLatch(1)
        val continueBuild = CountDownLatch(1)
        val hooks = DesktopConversionEngineTestHooks(beforeDraft = {
            entered.countDown()
            check(continueBuild.await(10, TimeUnit.SECONDS))
        })
        Fixture(hooks = hooks).use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            val build = thread { fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl()) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val close = thread { fixture.engine.close() }
            assertTrue(close.isAlive)
            continueBuild.countDown()
            build.join(10_000)
            close.join(10_000)
            assertFalse(fixture.engine.release(session.token))
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `동시 두 release는 하나만 true이고 map 제거가 cleanup보다 먼저다`() {
        val lookedUp = CountDownLatch(2)
        val continueRelease = CountDownLatch(1)
        lateinit var engine: DesktopConversionEngine
        val hooks = DesktopConversionEngineTestHooks(
            afterContextLookup = { lookedUp.countDown(); check(continueRelease.await(10, TimeUnit.SECONDS)) },
            afterMapRemoval = { token -> assertFalse(engine.isRegisteredForTesting(token)) },
        )
        Fixture(hooks = hooks).use { fixture ->
            engine = fixture.engine
            val session = engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            var first: Boolean? = null
            var second: Boolean? = null
            val releaseOne = thread { first = engine.release(session.token) }
            val releaseTwo = thread { second = engine.release(session.token) }
            assertTrue(lookedUp.await(10, TimeUnit.SECONDS))
            continueRelease.countDown()
            releaseOne.join(10_000)
            releaseTwo.join(10_000)
            assertEquals(1, listOf(first, second).count { it == true })
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `host input과 Jimfs workspace 양쪽에 CRX key directory를 만들지 않는다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            val artifact = fixture.engine.buildDraft(session, emptySet(), null, AnalysisControl())
            assertFalse(Files.exists(fixture.hostAppData.resolve(".local/keystore/crx")))
            assertFalse(Files.exists(artifact.privateFile.fileSystem.getPath("/sixbridge/${session.token}/.local/keystore/crx")))
            assertTrue(fixture.engine.release(session.token))
        }
    }

    @Test
    fun `analyze 등록 직전 close는 lease를 한 번 정리하고 이후 분석을 거부한다`() {
        val beforeRegistration = CountDownLatch(1)
        val continueRegistration = CountDownLatch(1)
        val hooks = DesktopConversionEngineTestHooks(
            beforeContextRegistration = {
                beforeRegistration.countDown()
                check(continueRegistration.await(10, TimeUnit.SECONDS))
            },
        )
        Fixture(hooks = hooks).use { fixture ->
            var failure: Throwable? = null
            val analyze = thread {
                failure = runCatching {
                    fixture.engine.analyze(
                        DesktopExtensionInput.Local(fixture.source),
                        profile,
                        AnalysisControl(),
                    )
                }.exceptionOrNull()
            }
            assertTrue(beforeRegistration.await(10, TimeUnit.SECONDS))

            fixture.engine.close()
            assertEquals(0, fixture.engine.activeContextCountForTesting())
            continueRegistration.countDown()
            analyze.join(10_000)

            assertTrue(failure is DesktopConversionException)
            assertEquals(1, fixture.closeCount.get())
            assertEquals(0, fixture.engine.activeContextCountForTesting())
            assertFailsWith<DesktopConversionException> {
                fixture.engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            }
            assertEquals(1, fixture.closeCount.get())
        }
    }

    @Test
    fun `CRX 분석부터 guarded portable draft 게시까지 원본과 signer를 보존한다`() {
        Fixture().use { fixture ->
            val crx = fixture.validCrx()
            val session = fixture.engine.analyze(
                DesktopExtensionInput.Local(crx.path),
                profile,
                AnalysisControl(),
            )
            assertEquals(crx.extensionId, session.report.originalExtensionId)
            val artifact = fixture.engine.buildDraft(
                session,
                session.requiredApprovals,
                "b".repeat(32),
                AnalysisControl(),
            )
            val verified = SixDraft.read(artifact.privateFile)

            assertEquals(2, verified.payload.draft.schemaVersion)
            assertEquals(DraftSourceKind.CRX, verified.payload.sourceKind)
            assertContentEquals(crx.bytes, verified.payload.source)
            assertEquals(DraftSignature.sha256(Files.readAllBytes(artifact.privateFile)), artifact.sha256)
            assertEquals(DraftSignature.fingerprint(fixture.signer.public.encoded), artifact.signerFingerprint)
            assertFalse(Files.exists(fixture.hostAppData.resolve(".local/keystore/crx")))
            assertFalse(Files.exists(artifact.privateFile.fileSystem.getPath("/sixbridge/${session.token}/.local/keystore/crx")))
            assertTrue(fixture.engine.release(session.token))
            assertEquals(1, fixture.closeCount.get())
        }
    }

    private fun cancellationReleaseRace(reason: String) {
        val entered = CountDownLatch(1)
        val continueBuild = CountDownLatch(1)
        lateinit var engine: DesktopConversionEngine
        val hooks = DesktopConversionEngineTestHooks(
            beforeDraft = { entered.countDown(); check(continueBuild.await(10, TimeUnit.SECONDS)) },
            afterMapRemoval = { token -> assertFalse(engine.isRegisteredForTesting(token)) },
        )
        Fixture(hooks = hooks).use { fixture ->
            engine = fixture.engine
            val session = engine.analyze(DesktopExtensionInput.Local(fixture.source), profile, AnalysisControl())
            var failure: Throwable? = null
            val build = thread { failure = runCatching { engine.buildDraft(session, emptySet(), null, AnalysisControl { throw CancellationException(reason) }) }.exceptionOrNull() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            var released: Boolean? = null
            val release = thread { released = engine.release(session.token) }
            continueBuild.countDown()
            build.join(10_000)
            release.join(10_000)
            assertTrue(failure is CancellationException)
            assertFalse(released == true)
            assertEquals(1, fixture.closeCount.get())
        }
    }

    private class Fixture(
        hooks: DesktopConversionEngineTestHooks = DesktopConversionEngineTestHooks(),
    ) : AutoCloseable {
        val closeCount = AtomicInteger()
        val manager = DesktopWorkspaceManager.testing(closeCount::incrementAndGet)
        val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val provider = DraftSigningIdentityProvider { signer }
        val sealer = DesktopInputSealer { error("Chrome input is not used in this test") }
        val engine = DesktopConversionEngine.testing(manager, sealer, provider, hooks)
        val hostAppData = Files.createTempDirectory("desktop-engine-app-data")
        val source = Files.createDirectory(hostAppData.resolve("source")).also { root -> root.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"sealed source","version":"1"}""") }
        val popupSource = Files.createDirectory(hostAppData.resolve("popup")).also { root ->
            root.resolve("popup.html").writeText(
                "<html><head></head><body><input type='email'></body></html>",
            )
            root.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"popup","version":"1","action":{"default_popup":"popup.html"}}""")
        }
        val blockedSource = Files.createDirectory(hostAppData.resolve("blocked")).also { root ->
            root.resolve("manifest.json").writeText("""{"manifest_version":2,"name":"blocked","version":"1"}""")
        }
        val dynamicSource = Files.createDirectory(hostAppData.resolve("dynamic")).also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"dynamic","version":"1","content_scripts":[{"matches":["https://*/*"],"js":["content.js"]}]}""",
            )
            root.resolve("content.js").writeText("chrome[namespace].sync.get('value');")
        }
        fun sourceWithName(name: String) = Files.createTempDirectory(hostAppData, "name-").also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"$name","version":"1"}""",
            )
        }
        fun validCrx(): CrxFixture {
            val packed = Crx3.pack(
                source,
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
            )
            val path = hostAppData.resolve("sealed-source.crx")
            Files.write(path, packed.bytes)
            return CrxFixture(path, packed.bytes, packed.extensionId)
        }
        override fun close() { engine.close() }
    }

    private data class CrxFixture(
        val path: java.nio.file.Path,
        val bytes: ByteArray,
        val extensionId: String,
    )

    private companion object {
        const val DRAFT_EXTENSION = ".sixdraft"
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser", versionName = BrowserProfile.BASELINE_VERSION, versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION, supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(), sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256, supportedFeatures = setOf("extension.options-page.user-accessible"),
        )
    }
}
