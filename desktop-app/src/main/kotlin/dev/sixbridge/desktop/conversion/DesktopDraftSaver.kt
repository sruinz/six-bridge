package dev.sixbridge.desktop.conversion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

class DesktopDraftSaver {
    fun save(artifact: DesktopDraftArtifact, destination: Path) {
        val target = destination.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "저장 목적지에 부모 디렉터리가 필요합니다." }
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "저장 목적지의 부모가 실제 디렉터리가 아닙니다." }
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "같은 이름의 파일이 이미 있습니다." }

        val temporary = parent.resolve(".sixbridge-save-${UUID.randomUUID()}.tmp")
        var temporaryCreated = false
        try {
            val actualHash = copyAndHash(artifact.privateFile, temporary)
            temporaryCreated = true
            check(actualHash.equals(artifact.sha256, ignoreCase = true)) {
                "생성된 초안의 SHA-256 검증에 실패했습니다."
            }

            // ATOMIC_MOVE는 provider에 따라 기존 파일을 덮어쓸 수 있다. 같은 directory의
            // hard link 생성은 이미 fsync된 temp inode를 no-replace로 공개한다.
            Files.createLink(target, temporary)
            Files.delete(temporary)
            temporaryCreated = false
        } finally {
            if (temporaryCreated) {
                Files.deleteIfExists(temporary)
            }
        }
    }

    private fun copyAndHash(source: Path, temporary: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var temporaryCreated = false
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { output ->
                temporaryCreated = true
                FileChannel.open(source, READ).use { input ->
                    val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
                    while (true) {
                        buffer.clear()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        buffer.flip()
                        val digestBytes = buffer.asReadOnlyBuffer()
                        digest.update(digestBytes)
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    output.force(true)
                }
            }
        } catch (error: Throwable) {
            if (temporaryCreated) Files.deleteIfExists(temporary)
            throw error
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
