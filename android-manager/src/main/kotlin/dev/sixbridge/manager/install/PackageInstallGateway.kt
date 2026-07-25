package dev.sixbridge.manager.install

import android.content.Intent
import java.io.File

interface PackageInstallGateway {
    fun canRequestPackageInstalls(): Boolean
    fun unknownSourcesSettingsIntent(): Intent
    suspend fun createAndWrite(packageName: String, apk: File): Int
    fun commit(sessionId: Int)
    fun abandon(sessionId: Int)
    fun ownedSessions(): List<Int>
}

class PackageInstallSessionException(
    val sessionId: Int?,
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
