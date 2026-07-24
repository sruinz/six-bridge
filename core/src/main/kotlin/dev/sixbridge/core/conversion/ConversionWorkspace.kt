package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

class ConversionWorkspace : AutoCloseable {
    private val root = Files.createTempDirectory("sixbridge-conversion-")
    private var closed = false

    fun snapshot(source: Path): ExtensionSnapshot {
        check(!closed) { "이미 정리된 conversion workspace입니다." }
        val snapshotRoot = Files.createTempDirectory(root, "snapshot-")
        return try {
            val sourceZip = ExtensionZip.createBytes(
                sourceRoot = source,
                includeProjectMarker = true,
            )
            val original = ExtensionZip.extract(
                sourceZip,
                snapshotRoot.resolve("original"),
            )
            val working = ExtensionZip.extract(
                sourceZip,
                snapshotRoot.resolve("working"),
            )
            ExtensionSnapshot(
                sourceZip = sourceZip,
                sourceSha256 = sha256(sourceZip),
                originalRoot = original,
                workingRoot = working,
            )
        } catch (error: Throwable) {
            deleteTree(snapshotRoot)
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        deleteTree(root)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
