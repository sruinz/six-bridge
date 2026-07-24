package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.SourceProjectIdentityStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator

class ConversionWorkspace : AutoCloseable {
    private val root = Files.createTempDirectory("sixbridge-conversion-")
    private val ownedWorkingRoots = mutableListOf<Path>()
    private var closed = false

    fun snapshot(
        source: Path,
        requireOwnerOnlyMarker: Boolean = true,
    ): ExtensionSnapshot {
        check(!closed) { "이미 정리된 conversion workspace입니다." }
        val snapshotRoot = Files.createTempDirectory(root, "snapshot-")
        return try {
            val sourceProjectIdentity = SourceProjectIdentityStore.readSourceMarker(
                source,
                requireOwnerOnlyMarker,
            )
            val sourceZip = ExtensionZip.createBytes(
                sourceRoot = source,
                includeProjectMarker = true,
            )
            require(
                sourceProjectIdentity == SourceProjectIdentityStore.readSourceMarker(
                    source,
                    requireOwnerOnlyMarker,
                ),
            ) {
                "snapshot 중 source project identity marker가 변경되었습니다."
            }
            val original = ExtensionZip.extract(
                sourceZip,
                snapshotRoot.resolve("original"),
            )
            val working = ExtensionZip.extract(
                sourceZip,
                snapshotRoot.resolve("working"),
            )
            ConversionWorkspaceOwnership.register(working)
            ownedWorkingRoots.add(working)
            ExtensionSnapshot(
                sourceZip = sourceZip,
                sourceSha256 = sha256(sourceZip),
                originalRoot = original,
                workingRoot = working,
                sourceProjectIdentity = sourceProjectIdentity,
            )
        } catch (error: Throwable) {
            deleteTree(snapshotRoot)
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ownedWorkingRoots.forEach(ConversionWorkspaceOwnership::unregister)
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
