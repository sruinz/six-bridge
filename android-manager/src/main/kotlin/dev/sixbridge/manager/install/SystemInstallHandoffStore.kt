package dev.sixbridge.manager.install

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

interface SystemInstallHandoffStore {
    fun snapshot(): RecoveryRecordSnapshot<PendingSystemInstall>
    fun tryPrepare(record: PendingSystemInstall): Boolean
    fun markLaunched(expected: PendingSystemInstall): PendingSystemInstall?
    fun clear(expected: PendingSystemInstall): Boolean
}

class SharedPreferencesSystemInstallHandoffStore internal constructor(
    private val preferences: SharedPreferences,
) : SystemInstallHandoffStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun snapshot(): RecoveryRecordSnapshot<PendingSystemInstall> = synchronized(PROCESS_LOCK) {
        snapshotLocked()
    }

    override fun tryPrepare(record: PendingSystemInstall): Boolean = synchronized(PROCESS_LOCK) {
        if (record.stage != SystemInstallStage.PREPARED ||
            !isValid(record) ||
            snapshotLocked() != RecoveryRecordSnapshot.Absent
        ) {
            return@synchronized false
        }
        writeLocked(record)
    }

    override fun markLaunched(
        expected: PendingSystemInstall,
    ): PendingSystemInstall? = synchronized(PROCESS_LOCK) {
        if (loadLocked() != expected || expected.stage != SystemInstallStage.PREPARED) {
            return@synchronized null
        }
        expected.copy(stage = SystemInstallStage.LAUNCHED)
            .takeIf(::writeLocked)
    }

    override fun clear(expected: PendingSystemInstall): Boolean = synchronized(PROCESS_LOCK) {
        if (loadLocked() != expected) return@synchronized false
        preferences.edit().removeAll(RECORD_KEYS).commit()
    }

    private fun snapshotLocked(): RecoveryRecordSnapshot<PendingSystemInstall> {
        if (RECORD_KEYS.none(preferences::contains)) return RecoveryRecordSnapshot.Absent
        return runCatching(::loadLocked).getOrNull()
            ?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Invalid
    }

    private fun loadLocked(): PendingSystemInstall? = runCatching {
        readLocked()
    }.getOrNull()

    private fun readLocked(): PendingSystemInstall? {
        if (!REQUIRED_KEYS.all(preferences::contains)) return null
        val previousPresent = preferences.getBoolean(KEY_PREVIOUS_PRESENT, false)
        if (previousPresent != PREVIOUS_KEYS.all(preferences::contains)) return null
        if (!previousPresent && PREVIOUS_KEYS.any(preferences::contains)) return null
        val expected = ExpectedInstalledWrapper(
            packageName = preferences.getString(KEY_PACKAGE_NAME, null).orEmpty(),
            extensionId = preferences.getString(KEY_EXTENSION_ID, null).orEmpty(),
            versionCode = preferences.getLong(KEY_VERSION_CODE, -1),
            signerSha256 = preferences.getString(KEY_SIGNER_SHA256, null).orEmpty(),
        )
        val previous = if (previousPresent) {
            ExpectedInstalledWrapper(
                packageName = preferences.getString(KEY_PREVIOUS_PACKAGE_NAME, null).orEmpty(),
                extensionId = preferences.getString(KEY_PREVIOUS_EXTENSION_ID, null).orEmpty(),
                versionCode = preferences.getLong(KEY_PREVIOUS_VERSION_CODE, -1),
                signerSha256 = preferences.getString(KEY_PREVIOUS_SIGNER_SHA256, null).orEmpty(),
            )
        } else {
            null
        }
        val stage = enumValueOrNull<SystemInstallStage>(
            preferences.getString(KEY_STAGE, null),
        ) ?: return null
        return PendingSystemInstall(
            importToken = preferences.getString(KEY_IMPORT_TOKEN, null).orEmpty(),
            expected = expected,
            wrapperSha256 = preferences.getString(KEY_WRAPPER_SHA256, null).orEmpty(),
            previous = previous,
            startedAtEpochMillis = preferences.getLong(KEY_STARTED_AT, -1),
            stage = stage,
            version = preferences.getInt(KEY_VERSION, -1),
        ).takeIf(::isValid)
    }

    private fun writeLocked(record: PendingSystemInstall): Boolean {
        val editor = preferences.edit()
            .putInt(KEY_VERSION, record.version)
            .putString(KEY_IMPORT_TOKEN, record.importToken)
            .putString(KEY_PACKAGE_NAME, record.expected.packageName)
            .putString(KEY_EXTENSION_ID, record.expected.extensionId)
            .putLong(KEY_VERSION_CODE, record.expected.versionCode)
            .putString(KEY_SIGNER_SHA256, record.expected.signerSha256)
            .putString(KEY_WRAPPER_SHA256, record.wrapperSha256)
            .putLong(KEY_STARTED_AT, record.startedAtEpochMillis)
            .putString(KEY_STAGE, record.stage.name)
            .putBoolean(KEY_PREVIOUS_PRESENT, record.previous != null)
        record.previous?.let { previous ->
            editor
                .putString(KEY_PREVIOUS_PACKAGE_NAME, previous.packageName)
                .putString(KEY_PREVIOUS_EXTENSION_ID, previous.extensionId)
                .putLong(KEY_PREVIOUS_VERSION_CODE, previous.versionCode)
                .putString(KEY_PREVIOUS_SIGNER_SHA256, previous.signerSha256)
        } ?: editor.removeAll(PREVIOUS_KEYS)
        return editor.commit()
    }

    private fun isValid(record: PendingSystemInstall): Boolean =
        record.version == CURRENT_VERSION &&
            isCanonicalToken(record.importToken) &&
            isValid(record.expected) &&
            SHA_256.matches(record.wrapperSha256) &&
            (record.previous == null || isValid(record.previous)) &&
            record.startedAtEpochMillis > 0

    private fun isValid(wrapper: ExpectedInstalledWrapper): Boolean =
        PACKAGE_NAME.matches(wrapper.packageName) &&
            EXTENSION_ID.matches(wrapper.extensionId) &&
            wrapper.versionCode > 0 &&
            SHA_256.matches(wrapper.signerSha256)

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }

    private fun SharedPreferences.Editor.removeAll(keys: Iterable<String>): SharedPreferences.Editor {
        keys.forEach(::remove)
        return this
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val PREFERENCES_NAME = "system_install_handoff"
        const val CURRENT_VERSION = 1
        const val KEY_VERSION = "version"
        const val KEY_IMPORT_TOKEN = "import_token"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_EXTENSION_ID = "extension_id"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_SIGNER_SHA256 = "signer_sha256"
        const val KEY_WRAPPER_SHA256 = "wrapper_sha256"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_STAGE = "stage"
        const val KEY_PREVIOUS_PRESENT = "previous_present"
        const val KEY_PREVIOUS_PACKAGE_NAME = "previous_package_name"
        const val KEY_PREVIOUS_EXTENSION_ID = "previous_extension_id"
        const val KEY_PREVIOUS_VERSION_CODE = "previous_version_code"
        const val KEY_PREVIOUS_SIGNER_SHA256 = "previous_signer_sha256"
        val REQUIRED_KEYS = listOf(
            KEY_VERSION,
            KEY_IMPORT_TOKEN,
            KEY_PACKAGE_NAME,
            KEY_EXTENSION_ID,
            KEY_VERSION_CODE,
            KEY_SIGNER_SHA256,
            KEY_WRAPPER_SHA256,
            KEY_STARTED_AT,
            KEY_STAGE,
            KEY_PREVIOUS_PRESENT,
        )
        val PREVIOUS_KEYS = listOf(
            KEY_PREVIOUS_PACKAGE_NAME,
            KEY_PREVIOUS_EXTENSION_ID,
            KEY_PREVIOUS_VERSION_CODE,
            KEY_PREVIOUS_SIGNER_SHA256,
        )
        val RECORD_KEYS = REQUIRED_KEYS + PREVIOUS_KEYS
        val PACKAGE_NAME = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
        val EXTENSION_ID = Regex("[a-p]{32}")
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
