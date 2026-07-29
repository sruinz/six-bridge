package dev.sixbridge.manager.install

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SystemInstallHandoffStoreTest {
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun `prepared 레코드는 같은 소유자만 launched로 전이한다`() {
        val store = store()
        val prepared = pending(stage = SystemInstallStage.PREPARED)

        assertTrue(store.tryPrepare(prepared))
        assertNull(store.markLaunched(prepared.copy(importToken = OTHER_TOKEN)))
        assertEquals(
            prepared.copy(stage = SystemInstallStage.LAUNCHED),
            store.markLaunched(prepared),
        )
    }

    @Test
    fun `동시 prepare는 하나의 소유자만 획득한다`() {
        val store = store()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = executor.invokeAll(
                listOf(
                    Callable { store.tryPrepare(pending(importToken = TOKEN)) },
                    Callable { store.tryPrepare(pending(importToken = OTHER_TOKEN)) },
                ),
            ).map { it.get() }

            assertEquals(1, outcomes.count { it })
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(store.snapshot())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `prepare는 prepared record만 수락한다`() {
        val store = store()

        assertFalse(store.tryPrepare(pending(stage = SystemInstallStage.LAUNCHED)))
        assertEquals(RecoveryRecordSnapshot.Absent, store.snapshot())
    }

    @Test
    fun `재생성된 store도 준비된 기대 신원을 보존한다`() {
        val prepared = pending(previous = expected(versionCode = 11))

        assertTrue(store().tryPrepare(prepared))

        assertEquals(
            RecoveryRecordSnapshot.Present(prepared),
            store().snapshot(),
        )
    }

    @Test
    fun `clear는 완전히 일치하는 record만 제거한다`() {
        val store = store()
        val prepared = pending()
        assertTrue(store.tryPrepare(prepared))

        assertFalse(store.clear(prepared.copy(startedAtEpochMillis = 124)))
        assertEquals(RecoveryRecordSnapshot.Present(prepared), store.snapshot())
        assertTrue(store.clear(prepared))
        assertEquals(RecoveryRecordSnapshot.Absent, store.snapshot())
    }

    @Test
    fun `손상된 signer digest는 Invalid snapshot으로 fail closed한다`() {
        assertTrue(store().tryPrepare(pending()))
        preferences.edit().putString("signer_sha256", "broken").commit()

        assertEquals(RecoveryRecordSnapshot.Invalid, store().snapshot())
        assertFalse(store().tryPrepare(pending()))
    }

    @Test
    fun `일부 key만 존재하는 record는 Invalid snapshot으로 fail closed한다`() {
        preferences.edit().putString("import_token", TOKEN).commit()

        assertEquals(RecoveryRecordSnapshot.Invalid, store().snapshot())
        assertFalse(store().tryPrepare(pending()))
    }

    @Test
    fun `허용하지 않는 version은 Invalid snapshot으로 fail closed한다`() {
        assertTrue(store().tryPrepare(pending()))
        preferences.edit().putInt("version", 2).commit()

        assertEquals(RecoveryRecordSnapshot.Invalid, store().snapshot())
        assertFalse(store().clear(pending()))
    }

    @Test
    fun `타입이 손상된 record는 mutation과 clear에서도 fail closed한다`() {
        val store = store()
        val prepared = pending()
        assertTrue(store.tryPrepare(prepared))
        preferences.edit().putString("version_code", "12").commit()

        assertEquals(RecoveryRecordSnapshot.Invalid, store.snapshot())
        assertNull(store.markLaunched(prepared))
        assertFalse(store.clear(prepared))
    }

    private fun store(): SharedPreferencesSystemInstallHandoffStore =
        SharedPreferencesSystemInstallHandoffStore(preferences)

    private fun pending(
        importToken: String = TOKEN,
        stage: SystemInstallStage = SystemInstallStage.PREPARED,
        previous: ExpectedInstalledWrapper? = null,
    ) = PendingSystemInstall(
        importToken = importToken,
        expected = expected(),
        wrapperSha256 = SHA_256,
        previous = previous,
        startedAtEpochMillis = 123,
        stage = stage,
    )

    private fun expected(versionCode: Long = 12) = ExpectedInstalledWrapper(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        versionCode = versionCode,
        signerSha256 = SHA_256,
    )

    private companion object {
        const val PREFERENCES_NAME = "system_install_handoff_store_test"
        const val TOKEN = "00000000-0000-0000-0000-000000000001"
        const val OTHER_TOKEN = "00000000-0000-0000-0000-000000000002"
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXTENSION_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
