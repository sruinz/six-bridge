package dev.sixbridge.manager

import android.net.Uri
import android.os.Bundle
import dev.sixbridge.manager.ui.ManagerEffect
import dev.sixbridge.manager.install.InstallState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityDocumentLaunchGateTest {
    @Test
    fun `active A 뒤 queued B는 A callback identity를 보존하고 그 다음 B를 launch한다`() {
        val first = PendingActivityDocumentLaunch(
            token = null,
            ownerGeneration = 1,
            launchId = 10,
        )
        val second = PendingActivityDocumentLaunch(
            token = null,
            ownerGeneration = 2,
            launchId = 11,
        )
        val gate = ActivityDocumentLaunchGate()

        assertEquals(first, gate.enqueue(first))
        assertNull(gate.enqueue(second))

        assertEquals(
            ActivityDocumentLaunchCompletion(completed = first, launchNext = second),
            gate.complete(),
        )
        assertEquals(second, gate.active)
        assertNull(gate.queued)
        assertEquals(
            ActivityDocumentLaunchCompletion(completed = second, launchNext = null),
            gate.complete(),
        )
    }

    @Test
    fun `recreation은 active와 queued launch identity를 모두 복원한다`() {
        val active = PendingActivityDocumentLaunch(null, 3, 12)
        val queued = PendingActivityDocumentLaunch(null, 4, 13)

        val gate = ActivityDocumentLaunchGate(active = active, queued = queued)

        assertEquals(active, gate.active)
        assertEquals(queued, gate.queued)
        assertEquals(
            ActivityDocumentLaunchCompletion(active, queued),
            gate.complete(),
        )
    }

    @Test
    fun `draft launcher는 active와 하나의 latest queued launch만 유지한다`() {
        val first = PendingActivityDocumentLaunch(null, 10, 20)
        val replaced = PendingActivityDocumentLaunch(null, 11, 21)
        val latest = PendingActivityDocumentLaunch(null, 12, 22)
        val gate = ActivityDocumentLaunchGate()

        assertEquals(first, gate.enqueue(first))
        assertNull(gate.enqueue(replaced))
        assertNull(gate.enqueue(latest))

        assertEquals(
            ActivityDocumentLaunchCompletion(first, latest),
            gate.complete(),
        )
    }

    @Test
    fun `APK export handoff recreation은 새 effect의 구조적 소유권을 보존한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/11111111-1111-1111-1111-111111111111/wrapper.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "smart-context-wrapper.apk",
            ownerGeneration = 1,
            launchId = 1,
        )
        val destination = Uri.parse("content://documents/export.apk")
        val handoff = InstallApkExportDocumentHandoff().apply {
            begin(effect)
            selectDestination(destination)
        }
        val saved = Bundle().also(handoff::save)

        val restored = InstallApkExportDocumentHandoff.restore(saved)

        assertIs<ManagerEffect.CreateInstallApkDocument>(
            restored.pending?.effect,
        )
        assertEquals(effect, restored.pending?.effect)
        assertEquals(destination, restored.pending?.destination)
    }

    @Test
    fun `APK handoff는 non-positive document identity 복원을 거부한다`() {
        val saved = Bundle().apply {
            putString("pending_system_apk_token", "11111111-1111-1111-1111-111111111111")
            putString("pending_system_apk_source", "/retained/wrapper.apk")
            putString("pending_system_apk_sha256", "a".repeat(64))
            putString("pending_system_apk_name", "wrapper.apk")
            putLong("pending_system_apk_owner", 0)
            putLong("pending_system_apk_launch_id", -1)
        }

        assertNull(InstallApkExportDocumentHandoff.restore(saved).pending)
    }

    @Test
    fun `APK picker active A는 보존하고 latest queued C만 다음 launch로 승격한다`() {
        fun effect(launchId: Long) = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/wrapper-$launchId.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "wrapper-$launchId.apk",
            ownerGeneration = launchId,
            launchId = launchId,
        )
        val first = effect(1)
        val second = effect(2)
        val latest = effect(3)
        val handoff = InstallApkExportDocumentHandoff()

        assertEquals(first, handoff.begin(first)?.effect)
        assertNull(handoff.begin(second))
        assertNull(handoff.begin(latest))
        val restored = InstallApkExportDocumentHandoff.restore(
            Bundle().also(handoff::save),
        )
        val completion = restored.cancelSelection()

        assertEquals(latest, restored.acknowledge(requireNotNull(completion))?.effect)
        assertEquals(latest, restored.pending?.effect)
        assertNull(restored.queued)
    }

    @Test
    fun `APK callback은 launch 당시 exact receipt를 한 번만 소비한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/wrapper.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "wrapper.apk",
            ownerGeneration = 51,
            launchId = 52,
        )
        val handoff = InstallApkExportDocumentHandoff()
        val pending = requireNotNull(handoff.begin(effect))

        assertEquals(true, handoff.markLaunched(pending))
        assertEquals(false, handoff.markLaunched(pending))
        assertEquals(pending, handoff.peekLaunchReceipt())
        assertEquals(true, handoff.consumeLaunchReceipt(pending))
        assertNull(handoff.peekLaunchReceipt())
        assertEquals(false, handoff.consumeLaunchReceipt(pending))
    }

    @Test
    fun `오래된 launch receipt는 현재 pending destination에 재결합하지 않는다`() {
        fun effect(id: Long) = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/wrapper-$id.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "wrapper-$id.apk",
            ownerGeneration = id,
            launchId = id,
        )
        val handoff = InstallApkExportDocumentHandoff()
        val first = requireNotNull(handoff.begin(effect(61)))
        handoff.begin(effect(62))

        assertNull(
            handoff.selectDestination(
                first.copy(effect = effect(63)),
                Uri.parse("content://documents/stale.apk"),
            ),
        )
        assertNull(handoff.pending?.destination)
    }

}
