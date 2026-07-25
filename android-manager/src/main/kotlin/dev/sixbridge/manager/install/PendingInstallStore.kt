package dev.sixbridge.manager.install

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

interface PendingInstallStore {
    fun load(): PendingInstall?
    fun save(pending: PendingInstall)
    fun clear()
    fun discardImport(importToken: String)
}

class SharedPreferencesPendingInstallStore private constructor(
    private val preferences: SharedPreferences,
    private val importDirectory: File,
) : PendingInstallStore {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        importDirectory = File(context.cacheDir, IMPORT_DIRECTORY),
    )

    override fun load(): PendingInstall? {
        val pending = PendingInstall(
            sessionId = preferences.getInt(KEY_SESSION_ID, -1),
            packageName = preferences.getString(KEY_PACKAGE_NAME, null).orEmpty(),
            importToken = preferences.getString(KEY_IMPORT_TOKEN, null).orEmpty(),
            startedAtEpochMillis = preferences.getLong(KEY_STARTED_AT, -1),
        )
        return pending.takeIf(::isValid)
    }

    override fun save(pending: PendingInstall) {
        require(isValid(pending)) { "올바르지 않은 pending install입니다." }
        check(
            preferences.edit()
                .clear()
                .putInt(KEY_SESSION_ID, pending.sessionId)
                .putString(KEY_PACKAGE_NAME, pending.packageName)
                .putString(KEY_IMPORT_TOKEN, pending.importToken)
                .putLong(KEY_STARTED_AT, pending.startedAtEpochMillis)
                .commit(),
        ) {
            "pending install을 저장하지 못했습니다."
        }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) {
            "pending install을 삭제하지 못했습니다."
        }
    }

    override fun discardImport(importToken: String) {
        if (!isCanonicalToken(importToken)) return
        val root = canonicalImportRoot() ?: return
        val target = File(root, importToken)
        if (runCatching { target.canonicalFile.parentFile == root }.getOrDefault(false)) {
            check(deleteTree(target)) { "임시 import를 삭제하지 못했습니다." }
        }
    }

    private fun isValid(pending: PendingInstall): Boolean =
        pending.sessionId >= 0 &&
            PACKAGE_NAME.matches(pending.packageName) &&
            isCanonicalToken(pending.importToken) &&
            pending.startedAtEpochMillis > 0

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private fun canonicalImportRoot(): File? = runCatching {
        if (!importDirectory.exists() || !importDirectory.isDirectory) return null
        if (Files.isSymbolicLink(importDirectory.toPath())) return null
        importDirectory.canonicalFile
    }.getOrNull()

    private fun deleteTree(directory: File): Boolean {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        error: IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private companion object {
        const val PREFERENCES_NAME = "pending_install"
        const val IMPORT_DIRECTORY = "imports"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_IMPORT_TOKEN = "import_token"
        const val KEY_STARTED_AT = "started_at"
        val PACKAGE_NAME = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}
