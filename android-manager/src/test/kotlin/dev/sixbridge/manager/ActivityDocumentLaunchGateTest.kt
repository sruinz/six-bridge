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
    fun `copy 중 recreation은 삭제될 destination을 재사용하지 않고 취소 completion으로 전환한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/wrapper.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "wrapper.apk",
            ownerGeneration = 21,
            launchId = 22,
        )
        val handoff = InstallApkExportDocumentHandoff().apply {
            begin(effect)
            selectDestination(Uri.parse("content://documents/interrupted.apk"))
        }

        val restored = InstallApkExportDocumentHandoff.restore(
            Bundle().also(handoff::save),
        )
        val interrupted = restored.cancelRestoredCopy()

        assertEquals(effect, interrupted?.pending?.effect)
        assertNull(interrupted?.action?.uri)
        assertNull(restored.pending?.destination)
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
    fun `APK export handoff는 SHA 확인 성공 결과를 exact owner에게 전달한 뒤 acknowledge한다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/11111111-1111-1111-1111-111111111111/wrapper.apk"),
            expectedSha256 = "b".repeat(64),
            suggestedFileName = "확장-1.0.0-aaaaaa.apk",
            ownerGeneration = 2,
            launchId = 2,
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
    fun `Activity parent 취소에서도 cleanup 실패 callback을 완료한다`() = runTest {
        val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(31, 32)
        val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
        val started = CompletableDeferred<Unit>()
        val failureReported = CompletableDeferred<Unit>()

        val attempt = async {
            saveInstallApkExportDocumentWhileOwned(
                owners = owners,
                expected = expected,
                onCleanupFailed = { failureReported.complete(Unit) },
            ) {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                    error("parent 취소가 전달되어야 합니다.")
                } catch (_: CancellationException) {
                    GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true)
                }
            }
        }
        started.await()
        attempt.cancel()

        failureReported.await()
    }

    @Test
    fun `일반 save failure cleanup 실패도 exact callback으로 보고한다`() = runTest {
        val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(33, 34)
        val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
        var failures = 0

        val result = saveInstallApkExportDocumentWhileOwned(
            owners = owners,
            expected = expected,
            onCleanupFailed = { failures += 1 },
        ) {
            GeneratedDocumentSaveResult.Failure(cleanupFailed = true)
        }

        assertEquals(GeneratedDocumentSaveResult.Failure(true), result)
        assertEquals(1, failures)
    }

    @Test
    fun `mutex 대기 중 owner를 잃은 copy는 ownership rejected를 보존한다`() = runTest {
        val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(35, 36)
        val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(null)
        var copies = 0

        val result = saveInstallApkExportDocumentWhileOwned(owners, expected) {
            copies += 1
            GeneratedDocumentSaveResult.Success
        }

        assertEquals(GeneratedDocumentSaveResult.OwnershipRejected, result)
        assertEquals(0, copies)
    }

    @Test
    fun `recreation은 old cleanup이 삭제한 같은 URI를 다시 삭제하거나 재사용하지 않는다`() =
        runTest {
            val expected = dev.sixbridge.manager.ui.DocumentLaunchIdentity(37, 38)
            val owners = MutableStateFlow<dev.sixbridge.manager.ui.DocumentLaunchIdentity?>(expected)
            val destination = Uri.parse("content://documents/config-cleaned.apk")
            val result = saveInstallApkExportDocumentWhileOwned(
                owners = owners,
                expected = expected,
                destination = destination,
            ) {
                GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false)
            }
            var duplicateDeletes = 0

            val cleaned = deleteInstallApkDestinationSerialized(expected, destination) {
                duplicateDeletes += 1
                false
            }

            assertEquals(GeneratedDocumentSaveResult.Cancelled(false), result)
            assertEquals(true, cleaned)
            assertEquals(0, duplicateDeletes)

            var newAttemptDeletes = 0
            assertEquals(
                false,
                deleteInstallApkDestinationSerialized(
                    expected.copy(launchId = 39),
                    destination,
                ) {
                    newAttemptDeletes += 1
                    false
                },
            )
            assertEquals(1, newAttemptDeletes)
        }

    @Test
    fun `committed APK의 stale Activity bundle은 destination을 삭제하지 않는다`() {
        val effect = ManagerEffect.CreateInstallApkDocument(
            importToken = "11111111-1111-1111-1111-111111111111",
            source = File("/retained/wrapper.apk"),
            expectedSha256 = "a".repeat(64),
            suggestedFileName = "wrapper.apk",
            ownerGeneration = 40,
            launchId = 41,
        )

        assertEquals(
            false,
            shouldCleanupRestoredInstallApk(
                InstallState.AwaitingManualInstall(effect.importToken),
                effect,
            ),
        )
        assertEquals(
            true,
            shouldCleanupRestoredInstallApk(
                InstallState.PreparingApkExport(effect.importToken),
                effect,
            ),
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
