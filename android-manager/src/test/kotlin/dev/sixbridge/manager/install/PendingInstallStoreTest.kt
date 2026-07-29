package dev.sixbridge.manager.install

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingInstallStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
