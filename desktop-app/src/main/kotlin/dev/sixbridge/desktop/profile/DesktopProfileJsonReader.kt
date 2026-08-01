package dev.sixbridge.desktop.profile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

class DesktopProfileJsonReadException internal constructor() :
    IllegalArgumentException("profile JSON을 안전하게 읽지 못했습니다.")

class DesktopProfileJsonReader private constructor(
    private val afterOpen: (Path) -> Unit,
) {
    constructor() : this({})

    fun read(path: Path): ByteArray = try {
        val before = signature(path)
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            afterOpen(path)
            val bytes = readBounded(channel)
            if (before != signature(path)) throw DesktopProfileJsonReadException()
            bytes
        }
    } catch (error: DesktopProfileJsonReadException) {
        throw error
    } catch (_: Exception) {
        throw DesktopProfileJsonReadException()
    }

    private fun signature(path: Path): FileSignature {
        if (Files.isSymbolicLink(path)) throw DesktopProfileJsonReadException()
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile) throw DesktopProfileJsonReadException()
        val fileKey = attributes.fileKey() ?: throw DesktopProfileJsonReadException()
        return FileSignature(
            fileKey = fileKey,
            size = attributes.size(),
            lastModified = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun readBounded(channel: FileChannel): ByteArray {
        val output = ByteArrayOutputStream(DEFAULT_BUFFER_BYTES)
        var total = 0
        while (true) {
            val remainingWithSentinel = MAX_BYTES + 1 - total
            val buffer = ByteBuffer.allocate(minOf(DEFAULT_BUFFER_BYTES, remainingWithSentinel))
            val count = channel.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            total += count
            if (total > MAX_BYTES) throw DesktopProfileJsonReadException()
            output.write(buffer.array(), 0, count)
        }
    }

    private data class FileSignature(
        val fileKey: Any,
        val size: Long,
        val lastModified: Long,
    )

    internal companion object {
        const val MAX_BYTES = 1024 * 1024
        private const val DEFAULT_BUFFER_BYTES = 8192

        fun testing(afterOpen: (Path) -> Unit): DesktopProfileJsonReader =
            DesktopProfileJsonReader(afterOpen)
    }
}
