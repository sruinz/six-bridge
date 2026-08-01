package dev.sixbridge.desktop.conversion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DesktopDraftSaveException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class DesktopDraftSaverTestHooks(
    val createLink: ((Path, Path) -> Path)? = null,
    val beforeCopy: (() -> Unit)? = null,
    val deletePublishedTemporary: ((Path) -> Unit)? = null,
)

class DesktopDraftSaver private constructor(
    private val hooks: DesktopDraftSaverTestHooks?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    constructor() : this(null, Unit)

    internal constructor(hooks: DesktopDraftSaverTestHooks) : this(hooks, Unit)

    private val pendingTemporaryCleanup = ConcurrentHashMap.newKeySet<Path>()

    fun save(artifact: DesktopDraftArtifact, destination: Path) {
        retryPendingTemporaryCleanup()
        val source = sourceIdentity(artifact)
        val requestedTarget = destination.toAbsolutePath().normalize()
        val parent = parentIdentity(requireNotNull(requestedTarget.parent) { "저장 목적지에 부모 디렉터리가 필요합니다." })
        val target = parent.path.resolve(requestedTarget.fileName)
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "같은 이름의 파일이 이미 있습니다." }
        probeHardLinkSupport(parent)

        val temporary = parent.path.resolve(".sixbridge-save-${UUID.randomUUID()}.tmp")
        var temporaryCreated = false
        var published = false
        try {
            verifyParent(parent)
            val actualHash = copyAndHash(source, temporary)
            temporaryCreated = true
            verifyParent(parent)
            verifyTemporary(parent, temporary)
            check(actualHash.equals(artifact.sha256, ignoreCase = true)) {
                "생성된 초안의 SHA-256 검증에 실패했습니다."
            }
            hooks?.beforeCopy?.invoke()
            verifyParent(parent)

            // ATOMIC_MOVE는 기존 목적지를 덮어쓸 수 있다. 같은 directory의 hard link는
            // 검증·fsync가 끝난 inode를 no-replace로 원자 공개한다.
            createLink(target, temporary)
            verifyParent(parent)
            check(Files.isSameFile(target, temporary)) { "초안 공개 inode 검증에 실패했습니다." }
            published = true
            deletePublishedTemporary(temporary)
            temporaryCreated = false
        } finally {
            if (temporaryCreated && !published) deleteOwnedTemporary(temporary)
        }
    }

    private fun sourceIdentity(artifact: DesktopDraftArtifact): FileIdentity {
        val token = try {
            UUID.fromString(artifact.token).also { require(it.toString() == artifact.token) }
        } catch (error: IllegalArgumentException) {
            throw DesktopDraftSaveException("초안 token이 올바르지 않습니다.", error)
        }
        val source = artifact.privateFile.toAbsolutePath().normalize()
        val artifacts = source.fileSystem.getPath("/sixbridge")
            .resolve(token.toString())
            .resolve("artifacts")
        if (source.parent != artifacts) {
            throw DesktopDraftSaveException("초안 파일이 현재 변환 workspace의 artifacts 경계 밖에 있습니다.")
        }
        return fileIdentity(source, "초안 파일")
    }

    private fun parentIdentity(path: Path): ParentIdentity {
        val candidate = path.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(candidate)) throw DesktopDraftSaveException("저장 목적지 parent는 심볼릭 링크일 수 없습니다.")
        val real = try {
            candidate.toRealPath()
        } catch (error: Exception) {
            throw DesktopDraftSaveException("저장 목적지 parent를 확인할 수 없습니다.", error)
        }
        val attributes = try {
            Files.readAttributes(real, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw DesktopDraftSaveException("저장 목적지 parent를 확인할 수 없습니다.", error)
        }
        if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.fileKey() == null) {
            throw DesktopDraftSaveException("저장 목적지 parent가 실제 디렉터리가 아닙니다.")
        }
        return ParentIdentity(real, attributes.fileKey())
    }

    private fun verifyParent(expected: ParentIdentity) {
        val actual = parentIdentity(expected.path)
        if (actual.path != expected.path || actual.fileKey != expected.fileKey) {
            throw DesktopDraftSaveException("저장 중 목적지 parent가 변경되었습니다.")
        }
    }

    private fun probeHardLinkSupport(parent: ParentIdentity) {
        val temporary = parent.path.resolve(".sixbridge-save-probe-${UUID.randomUUID()}.tmp")
        val link = parent.path.resolve(".sixbridge-save-probe-${UUID.randomUUID()}.link")
        var temporaryCreated = false
        var linkCreated = false
        try {
            verifyParent(parent)
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { it.force(true) }
            temporaryCreated = true
            verifyParent(parent)
            createLink(link, temporary)
            linkCreated = true
            verifyParent(parent)
            check(Files.isSameFile(temporary, link)) { "hard link probe inode 검증에 실패했습니다." }
        } catch (error: DesktopDraftSaveException) {
            throw error
        } catch (error: Throwable) {
            throw DesktopDraftSaveException("이 저장 위치는 안전한 원자적 초안 공개를 지원하지 않습니다.", error)
        } finally {
            if (linkCreated) deleteOwnedTemporary(link)
            if (temporaryCreated) deleteOwnedTemporary(temporary)
        }
    }

    private fun copyAndHash(source: FileIdentity, temporary: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var temporaryCreated = false
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { output ->
                temporaryCreated = true
                FileChannel.open(source.path, READ).use { input ->
                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    while (true) {
                        buffer.clear()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        buffer.flip()
                        digest.update(buffer.asReadOnlyBuffer())
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    output.force(true)
                }
            }
            verifyFileIdentity(source)
        } catch (error: Throwable) {
            if (temporaryCreated) deleteOwnedTemporary(temporary)
            throw error
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyTemporary(parent: ParentIdentity, temporary: Path) {
        if (temporary.parent != parent.path || !Files.isSameFile(temporary.parent, parent.path)) {
            throw DesktopDraftSaveException("임시 초안 파일이 검증된 목적지 밖에 있습니다.")
        }
        fileIdentity(temporary, "임시 초안 파일")
    }

    private fun fileIdentity(path: Path, label: String): FileIdentity {
        if (Files.isSymbolicLink(path)) throw DesktopDraftSaveException("${label}은 심볼릭 링크일 수 없습니다.")
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw DesktopDraftSaveException("${label}을 확인할 수 없습니다.", error)
        }
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.fileKey() == null) {
            throw DesktopDraftSaveException("${label}은 일반 파일이어야 합니다.")
        }
        return FileIdentity(path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun verifyFileIdentity(expected: FileIdentity) {
        val actual = fileIdentity(expected.path, "초안 파일")
        if (actual.fileKey != expected.fileKey || actual.size != expected.size || actual.modifiedMillis != expected.modifiedMillis) {
            throw DesktopDraftSaveException("저장 중 초안 파일이 변경되었습니다.")
        }
    }

    private fun createLink(link: Path, existing: Path): Path =
        hooks?.createLink?.invoke(link, existing) ?: Files.createLink(link, existing)

    private fun deletePublishedTemporary(temporary: Path) {
        try {
            hooks?.deletePublishedTemporary?.invoke(temporary) ?: Files.delete(temporary)
        } catch (_: Exception) {
            pendingTemporaryCleanup.add(temporary)
            try {
                Files.deleteIfExists(temporary)
                pendingTemporaryCleanup.remove(temporary)
            } catch (_: Exception) {
                // 공개는 이미 완료됐다. 다음 save에서 재시도하며 결과 파일은 성공으로 유지한다.
            }
        }
    }

    private fun retryPendingTemporaryCleanup() {
        pendingTemporaryCleanup.toList().forEach { temporary ->
            try {
                Files.deleteIfExists(temporary)
                pendingTemporaryCleanup.remove(temporary)
            } catch (_: Exception) {
                // 완성된 destination은 유효하므로 다음 저장 전 다시 시도한다.
            }
        }
    }

    private fun deleteOwnedTemporary(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // 실패 경로에서는 원래 실패를 보존한다. 현재 JVM에는 directory handle 기반 정리가 없다.
        }
    }

    private data class ParentIdentity(val path: Path, val fileKey: Any)
    private data class FileIdentity(val path: Path, val fileKey: Any, val size: Long, val modifiedMillis: Long)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
