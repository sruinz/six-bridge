package dev.sixbridge.manager

import android.net.Uri
import android.os.Bundle
import dev.sixbridge.manager.ui.ManagerEffect
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
    fun `APK export handoff는 SHA 확인 성공 결과를 exact owner에게 전달한 뒤 acknowledge한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/11111111-1111-1111-1111-111111111111/wrapper.apk"),
            expectedSha256 = "b".repeat(64),
            suggestedFileName = "확장-1.0.0-aaaaaa.apk",
        )
        val destination = Uri.parse("content://documents/export.apk")
        val handoff = InstallApkExportDocumentHandoff().apply {
            begin(effect)
            selectDestination(destination)
        }

        val completion = kotlinx.coroutines.runBlocking {
            handoff.prepareCompletion {
                assertEquals(effect.expectedSha256, it.effect.expectedSha256)
                GeneratedDocumentSaveResult.Success
            }
        }

        val exact = requireNotNull(completion)
        assertEquals(effect.importToken, exact.action.importToken)
        assertEquals(destination, exact.action.uri)
        assertEquals(effect, handoff.pending?.effect)
        assertNull(handoff.acknowledge(exact))
        assertNull(handoff.pending)
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
    fun `APK copy 중 owner generation 변경은 copy를 취소하고 cleanup 결과를 기다린다`() =
        runTest {
            val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(7, 11)
            val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
            val started = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()

            val result = async {
                saveInstallApkExportDocumentWhileOwned(
                    owners = owners,
                    expected = expected,
                ) {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        error("owner가 바뀌면 copy가 취소되어야 합니다.")
                    } catch (_: CancellationException) {
                        cleaned.complete(Unit)
                        GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false)
                    }
                }
            }
            started.await()
            owners.value = dev.sixbridge.manager.ui.DocumentLaunchIdentity(8, 12)

            assertEquals(
                GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false),
                result.await(),
            )
            cleaned.await()
        }

    @Test
    fun `APK copy 취소 cleanup 실패 결과를 숨기지 않는다`() = runTest {
        val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(9, 13)
        val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
        val started = CompletableDeferred<Unit>()

        val result = async {
            saveInstallApkExportDocumentWhileOwned(
                owners = owners,
                expected = expected,
            ) {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    error("owner가 바뀌면 copy가 취소되어야 합니다.")
                } catch (_: CancellationException) {
                    GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true)
                }
            }
        }
        started.await()
        owners.value = null

        assertEquals(
            GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true),
            result.await(),
        )
    }

    @Test
    fun `APK 저장 성공 직후 owner를 잃으면 이번 destination만 삭제한다`() = runTest {
        val destination = Uri.parse("content://documents/stale-success.apk")
        var accepted = 0
        val deleted = mutableListOf<Uri>()

        val result = finalizeInstallApkExportDocument(
            destination = destination,
            accept = {
                accepted += 1
                false
            },
            delete = {
                deleted += it
                true
            },
        )

        assertEquals(InstallApkExportFinalizeResult.StaleDestinationDeleted, result)
        assertEquals(1, accepted)
        assertEquals(listOf(destination), deleted)
    }

    @Test
    fun `stale APK destination 삭제 실패는 exact cleanup failure를 반환한다`() = runTest {
        val destination = Uri.parse("content://documents/stale-cleanup-failed.apk")

        val result = finalizeInstallApkExportDocument(
            destination = destination,
            accept = { false },
            delete = { false },
        )

        assertEquals(InstallApkExportFinalizeResult.StaleDestinationCleanupFailed, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `recreation copy는 이전 같은 destination cleanup이 끝난 뒤 시작한다`() = runTest {
        val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(10, 14)
        val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
        val firstStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = async {
            saveInstallApkExportDocumentWhileOwned(owners, expected) {
                firstStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    error("owner 폐기 시 취소되어야 합니다.")
                } catch (_: CancellationException) {
                    withContext(NonCancellable) { allowCleanup.await() }
                    GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false)
                }
            }
        }
        firstStarted.await()
        owners.value = null
        runCurrent()
        owners.value = expected
        val second = async {
            saveInstallApkExportDocumentWhileOwned(owners, expected) {
                secondStarted.complete(Unit)
                GeneratedDocumentSaveResult.Success
            }
        }
        runCurrent()

        assertFalse(secondStarted.isCompleted)
        allowCleanup.complete(Unit)
        assertEquals(
            GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false),
            first.await(),
        )
        assertEquals(GeneratedDocumentSaveResult.Success, second.await())
    }
}
