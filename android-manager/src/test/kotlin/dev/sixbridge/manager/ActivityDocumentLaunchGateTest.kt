package dev.sixbridge.manager

import android.net.Uri
import android.os.Bundle
import dev.sixbridge.manager.ui.ManagerEffect
import java.io.File
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
    fun `APK export handoff recreation은 새 effect의 구조적 소유권을 보존한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/11111111-1111-1111-1111-111111111111/wrapper.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "smart-context-wrapper.apk",
        )
        val destination = Uri.parse("content://documents/export.apk")
        val handoff = SystemInstallApkDocumentHandoff().apply {
            begin(effect)
            selectDestination(destination)
        }
        val saved = Bundle().also(handoff::save)

        val restored = SystemInstallApkDocumentHandoff.restore(saved)

        assertIs<ManagerEffect.CreateSystemInstallApkDocument>(
            restored.pending?.effect,
        )
        assertEquals(effect, restored.pending?.effect)
        assertEquals(destination, restored.pending?.destination)
    }
}
