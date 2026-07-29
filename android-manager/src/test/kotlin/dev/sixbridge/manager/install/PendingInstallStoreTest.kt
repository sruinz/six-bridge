package dev.sixbridge.manager.install

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PendingInstallStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `MY_FILES receipt는 재생성 뒤에도 READY ACKNOWLEDGED finalize로 전이한다`() {
        val context = RuntimeEnvironment.getApplication()
        val pending = context.getSharedPreferences(
            "pending_install_store_my_files_test",
            Context.MODE_PRIVATE,
        )
        val recovery = context.getSharedPreferences(
            "install_recovery_my_files_test",
            Context.MODE_PRIVATE,
        )
        pending.edit().clear().commit()
        recovery.edit().clear().commit()
        val ready = myFilesReceipt()
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pending,
            recoveryPreferences = recovery,
            importDirectory = File(temporaryFolder.root, "imports"),
        )

        assertTrue(store.createFinalReceipt(null, null, ready))
        val recreated = SharedPreferencesPendingInstallStore(
            pendingPreferences = pending,
            recoveryPreferences = recovery,
            importDirectory = File(temporaryFolder.root, "imports"),
        )
        assertEquals(ready, recreated.loadFinalReceipt())
        val acknowledged = recreated.acknowledgeFinalReceipt(ready, null)
        assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged?.stage)
        assertTrue(recreated.finalizeFinalReceipt(requireNotNull(acknowledged)))
        assertNull(recreated.loadFinalReceipt())
    }

    @Test
    fun `MY_FILES ACKNOWLEDGED receipt는 최초 record로 직접 생성할 수 없다`() {
        val store = mutableStore()
        val acknowledged = myFilesReceipt().copy(
            stage = FinalReceiptStage.ACKNOWLEDGED,
        )

        assertFalse(store.createFinalReceipt(null, null, acknowledged))
        assertNull(store.loadFinalReceipt())
    }

    @Test
    fun `MY_FILES receipt는 session raw timestamp CANCELLING을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            myFilesReceipt().copy(sessionId = 41)
        }
        assertFailsWith<IllegalArgumentException> {
            myFilesReceipt().copy(rawTerminalAtEpochMillis = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            myFilesReceipt().copy(stage = FinalReceiptStage.CANCELLING)
        }
    }

    @Test
    fun `MY_FILES receipt는 raw terminal이 존재하면 생성하지 않는다`() {
        val store = mutableStore()
        val claim = requireNotNull(
            store.tryClaim(PACKAGE, TOKEN, startedAtEpochMillis = 1),
        )
        val pending = requireNotNull(store.assignSession(claim, 41))
        assertTrue(
            store.recordTerminalResult(
                PackageInstallResult.Cancelled(41),
                terminalAtEpochMillis = 2,
            ),
        )
        val raw = requireNotNull(store.loadTerminalResult())

        assertFalse(
            store.createFinalReceipt(
                expectedPending = pending,
                expectedRaw = raw,
                receipt = myFilesReceipt(),
            ),
        )
        assertNull(store.loadFinalReceipt())
    }

    @Test
    fun `SYSTEM_INSTALLER receipt는 raw 없이 READY ACKNOWLEDGED finalize로 전이한다`() {
        val store = mutableStore()
        val ready = systemInstallerReceipt()

        assertTrue(store.createFinalReceipt(null, null, ready))
        assertEquals(ready, store.loadFinalReceipt())
        val acknowledged = store.acknowledgeFinalReceipt(ready, null)
        assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged?.stage)
        assertTrue(store.finalizeFinalReceipt(requireNotNull(acknowledged)))
        assertNull(store.loadFinalReceipt())
    }

    @Test
    fun `SYSTEM_INSTALLER ACKNOWLEDGED receipt는 최초 record로 직접 생성할 수 없다`() {
        val store = mutableStore()
        val acknowledged = systemInstallerReceipt().copy(
            stage = FinalReceiptStage.ACKNOWLEDGED,
        )

        assertFalse(store.createFinalReceipt(null, null, acknowledged))
        assertNull(store.loadFinalReceipt())
    }

    @Test
    fun `SYSTEM_INSTALLER receipt는 session raw timestamp CANCELLING을 허용하지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            systemInstallerReceipt().copy(sessionId = 41)
        }
        assertFailsWith<IllegalArgumentException> {
            systemInstallerReceipt().copy(rawTerminalAtEpochMillis = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            systemInstallerReceipt().copy(stage = FinalReceiptStage.CANCELLING)
        }
    }

    @Test
    fun `SYSTEM_INSTALLER receipt는 raw terminal이 존재하면 생성하지 않는다`() {
        val store = mutableStore()
        val claim = requireNotNull(
            store.tryClaim(PACKAGE, TOKEN, startedAtEpochMillis = 1),
        )
        val pending = requireNotNull(store.assignSession(claim, 41))
        assertTrue(
            store.recordTerminalResult(
                PackageInstallResult.Cancelled(41),
                terminalAtEpochMillis = 2,
            ),
        )
        val raw = requireNotNull(store.loadTerminalResult())

        assertFalse(
            store.createFinalReceipt(
                expectedPending = pending,
                expectedRaw = raw,
                receipt = systemInstallerReceipt(),
            ),
        )
        assertNull(store.loadFinalReceipt())
    }

    @Test
    fun `present malformed terminal identity and failure fields make the whole recovery snapshot Invalid`() {
        listOf(
            "malformed import token" to ("result_import_token" to "not-a-canonical-token"),
            "blank failure code" to ("result_code" to ""),
            "malformed failure code" to ("result_code" to "lowercase"),
            "blank failure message" to ("result_message" to ""),
            "malformed failure message" to ("result_message" to "\u007f"),
        ).forEach { (caseName, malformedEntry) ->
            val raw = validLegacyFailureRecord().apply {
                put(malformedEntry.first, malformedEntry.second)
            }
            val original = raw.toMap()
            val store = store(raw)

            assertEquals(
                RecoveryStoreSnapshot.Invalid,
                store.recoveryStoreSnapshot(),
                caseName,
            )
            assertNull(
                store.tryClaim(PACKAGE, TOKEN, startedAtEpochMillis = 3),
                caseName,
            )
            assertEquals(original, raw, caseName)
        }
    }

    @Test
    fun `truly missing legacy terminal fields retain null identity and failure defaults`() {
        val raw = validLegacyFailureRecord()
        val snapshot = assertIs<RecoveryStoreSnapshot.Valid>(
            store(raw).recoveryStoreSnapshot(),
        )
        val terminal = requireNotNull(snapshot.terminal)
        val failure = assertIs<PackageInstallResult.Failure>(terminal.result)

        assertNull(terminal.importToken)
        assertEquals("PACKAGE_INSTALLER_FAILURE", failure.code)
        assertEquals(
            "PackageInstaller가 설치를 완료하지 못했습니다.",
            failure.message,
        )
        assertEquals(raw, validLegacyFailureRecord())
    }

    @Test
    fun `success와 cancelled에 failure 전용 field가 존재하면 전체 recovery snapshot이 Invalid다`() {
        listOf(
            "success malformed code" to
                validLegacyTerminalRecord("success").apply {
                    put("result_code", "lowercase")
                },
            "success malformed message" to
                validLegacyTerminalRecord("success").apply {
                    put("result_message", "\u007f")
                },
            "cancelled wrong-typed code" to
                validLegacyTerminalRecord("cancelled").apply {
                    put("result_code", 7)
                },
            "cancelled wrong-typed message" to
                validLegacyTerminalRecord("cancelled").apply {
                    put("result_message", 7)
                },
        ).forEach { (caseName, raw) ->
            val original = raw.toMap()
            val store = store(raw)

            assertEquals(
                RecoveryStoreSnapshot.Invalid,
                store.recoveryStoreSnapshot(),
                caseName,
            )
            assertNull(
                store.tryClaim(PACKAGE, TOKEN, startedAtEpochMillis = 3),
                caseName,
            )
            assertEquals(original, raw, caseName)
        }
    }

    private fun store(rawRecovery: MutableMap<String, Any>): SharedPreferencesPendingInstallStore =
        SharedPreferencesPendingInstallStore(
            pendingPreferences = readOnlyPreferences(emptyMap()),
            recoveryPreferences = readOnlyPreferences(rawRecovery),
            importDirectory = File(temporaryFolder.root, "imports"),
        )

    private fun mutableStore(): SharedPreferencesPendingInstallStore {
        val context = RuntimeEnvironment.getApplication()
        val pending = context.getSharedPreferences("pending_install_store_test", Context.MODE_PRIVATE)
        val recovery = context.getSharedPreferences("install_recovery_test", Context.MODE_PRIVATE)
        pending.edit().clear().commit()
        recovery.edit().clear().commit()
        return SharedPreferencesPendingInstallStore(
            pendingPreferences = pending,
            recoveryPreferences = recovery,
            importDirectory = File(temporaryFolder.root, "imports"),
        )
    }

    private fun systemInstallerReceipt() = FinalInstallReceipt(
        importToken = TOKEN,
        sessionId = null,
        packageName = PACKAGE,
        state = InstallState.Installed(PACKAGE),
        stage = FinalReceiptStage.READY,
        origin = FinalReceiptOrigin.SYSTEM_INSTALLER,
        rawTerminalAtEpochMillis = null,
        terminalAtEpochMillis = 3,
    )

    private fun myFilesReceipt() = systemInstallerReceipt().copy(
        origin = FinalReceiptOrigin.MY_FILES,
    )

    private fun validLegacyFailureRecord(): MutableMap<String, Any> = mutableMapOf(
        "result_session_id" to 41,
        "result_kind" to "failure",
        "result_package_name" to PACKAGE,
        "terminal_at" to 2L,
    )

    private fun validLegacyTerminalRecord(kind: String): MutableMap<String, Any> = mutableMapOf(
        "result_session_id" to 41,
        "result_kind" to kind,
        "result_package_name" to PACKAGE,
        "terminal_at" to 2L,
    )

    private fun readOnlyPreferences(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "contains" -> values.containsKey(arguments.orEmpty()[0] as String)
                "getInt" -> typedValue(values, arguments, Int::class.javaObjectType)
                "getLong" -> typedValue(values, arguments, Long::class.javaObjectType)
                "getBoolean" -> typedValue(values, arguments, Boolean::class.javaObjectType)
                "getString" -> typedValue(values, arguments, String::class.java)
                "getAll" -> values.toMap()
                "equals" -> proxy === arguments.orEmpty()[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ReadOnlySharedPreferences($values)"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

    private fun typedValue(
        values: Map<String, Any>,
        arguments: Array<out Any?>?,
        expectedType: Class<*>,
    ): Any? {
        val key = arguments.orEmpty()[0] as String
        val defaultValue = arguments.orEmpty()[1]
        if (!values.containsKey(key)) return defaultValue
        val value = values.getValue(key)
        if (!expectedType.isInstance(value)) {
            throw ClassCastException("$key is not ${expectedType.simpleName}")
        }
        return value
    }

    private companion object {
        const val TOKEN = "00000000-0000-0000-0000-000000000001"
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
