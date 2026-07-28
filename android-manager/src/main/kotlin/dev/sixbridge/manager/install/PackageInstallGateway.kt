package dev.sixbridge.manager.install

import android.content.Intent
import java.io.File

interface PackageInstallGateway {
    fun canRequestPackageInstalls(): Boolean
    fun unknownSourcesSettingsIntent(): Intent
    suspend fun createSession(packageName: String): Int
    suspend fun writeSession(sessionId: Int, apk: File)
    fun commit(sessionId: Int)
    fun abandon(sessionId: Int)
    fun ownedSessions(): List<Int>
    fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot?
}

data class InstallSessionSnapshot(
    val sessionId: Int,
    val packageName: String?,
    val ownedByCaller: Boolean,
)

class PackageInstallSessionException(
    val sessionId: Int?,
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
