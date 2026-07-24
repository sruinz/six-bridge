package dev.sixbridge.core.conversion

import java.nio.file.Path
import java.util.UUID
import kotlin.jvm.JvmSynthetic

internal sealed interface OwnedTempDirectory {
    @get:JvmSynthetic
    val rootPath: Path

    @get:JvmSynthetic
    val token: UUID

    companion object {
        @JvmSynthetic
        fun create(
            rootPath: Path,
            token: UUID,
        ): OwnedTempDirectory =
            DefaultOwnedTempDirectory(rootPath, token)
    }
}

private class DefaultOwnedTempDirectory(
    override val rootPath: Path,
    override val token: UUID,
) : OwnedTempDirectory

internal object OwnedTempTree {
    internal const val MAX_ACTIVE_OWNERS = 64
    internal const val MAX_OWNERS_PER_TREE = 32

    @JvmSynthetic
    fun create(prefix: String): OwnedTempDirectory =
        PendingTempCleanup.createTempDirectory(prefix)

    @JvmSynthetic
    fun create(
        parent: OwnedTempDirectory,
        prefix: String,
    ): OwnedTempDirectory =
        PendingTempCleanup.createTempDirectory(parent, prefix)

    @JvmSynthetic
    fun createNamed(
        parent: OwnedTempDirectory,
        name: String,
    ): OwnedTempDirectory =
        PendingTempCleanup.createNamed(parent, name)

    @JvmSynthetic
    fun createNamed(
        parent: Path,
        name: String,
    ): OwnedTempDirectory =
        PendingTempCleanup.createNamed(parent, name)

    @JvmSynthetic
    fun createAt(path: Path): OwnedTempDirectory =
        PendingTempCleanup.createAt(path)

    @JvmSynthetic
    fun delete(owner: OwnedTempDirectory) {
        PendingTempCleanup.delete(owner)
    }

    @JvmSynthetic
    fun delete(path: Path) {
        PendingTempCleanup.delete(path)
    }

    @JvmSynthetic
    fun abandon(owner: OwnedTempDirectory) {
        PendingTempCleanup.abandon(owner)
    }

    @JvmSynthetic
    fun abandon(path: Path) {
        PendingTempCleanup.abandon(path)
    }

    @JvmSynthetic
    fun abandonNoDelete(owner: OwnedTempDirectory) {
        PendingTempCleanup.abandonNoDelete(owner)
    }
}

internal class OwnedTempCleanupException :
    IllegalStateException("소유한 임시 경로를 정리하지 못했습니다.")

internal class OwnedTempAbandonedException :
    IllegalStateException("소유 handle을 닫고 임시 경로는 삭제하지 않았습니다.")
