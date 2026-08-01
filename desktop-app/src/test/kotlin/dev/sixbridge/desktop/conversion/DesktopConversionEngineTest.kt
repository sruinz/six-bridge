package dev.sixbridge.desktop.conversion

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.draft.DraftSignature
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.input.DesktopInputSealer
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.CancellationException
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
    fun `분석은 자동 adapter와 승인 adapter를 분리한다`() {
        Fixture().use { fixture ->
            val session = fixture.engine.analyze(DesktopExtensionInput.Local(fixture.popupSource), profile, AnalysisControl())
            assertEquals(session.report.adapterDecisions.filter { it.disposition.name == "AUTOMATIC" }.mapTo(sortedSetOf()) { it.id }, session.selectedAutomaticAdapters)
            assertEquals(session.report.adapterDecisions.filter { it.disposition.name == "REQUIRES_APPROVAL" }.mapTo(sortedSetOf()) { it.id }, session.requiredApprovals)
            assertTrue(fixture.engine.release(session.token))
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
            assertEquals(DraftSourceKind.ZIP, verified.payload.sourceKind)
            assertNotEquals("mutated", verified.payload.compatibilityBefore.extensionName)
            assertEquals(DraftSignature.sha256(Files.readAllBytes(artifact.privateFile)), artifact.sha256)
            assertEquals(DraftSignature.fingerprint(fixture.signer.public.encoded), artifact.signerFingerprint)
            assertFalse(Files.exists(artifact.privateFile.fileSystem.getPath("/sixbridge/${session.token}/.local/keystore/crx")))
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

    private class Fixture : AutoCloseable {
        val manager = DesktopWorkspaceManager()
        val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val provider = DraftSigningIdentityProvider { signer }
        val sealer = DesktopInputSealer { error("Chrome input is not used in this test") }
        val engine = DesktopConversionEngine.testing(manager, sealer, provider)
        val source = Files.createTempDirectory("desktop-engine-source").also { root -> root.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"sealed source","version":"1"}""") }
        val popupSource = Files.createTempDirectory("desktop-engine-popup").also { root ->
            root.resolve("popup.html").writeText("<p>popup</p>")
            root.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"popup","version":"1","action":{"default_popup":"popup.html"}}""")
        }
        val blockedSource = Files.createTempDirectory("desktop-engine-blocked").also { root ->
            root.resolve("manifest.json").writeText("""{"manifest_version":2,"name":"blocked","version":"1"}""")
        }
        override fun close() { engine.close() }
    }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser", versionName = BrowserProfile.BASELINE_VERSION, versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION, supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(), sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256, supportedFeatures = setOf("extension.options-page.user-accessible"),
        )
    }
}
