package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.SourceProjectIdentityStore
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Comparator
import java.util.IdentityHashMap

class ConversionWorkspace : AutoCloseable {
    private val root = Files.createTempDirectory("sixbridge-conversion-")
    private val ownedWorkingRoots = mutableListOf<Path>()
    private val ownedSnapshots = IdentityHashMap<ExtensionSnapshot, Unit>()
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
            ConversionWorkspaceOwnership.register(
                originalRoot = original,
                workingRoot = working,
                freshWorkspaceRoot = root,
            )
            ownedWorkingRoots.add(working)
            val snapshot = ExtensionSnapshot.create(
                sourceZip = sourceZip,
                sourceSha256 = sha256(sourceZip),
                originalRoot = original,
                workingRoot = working,
                sourceProjectIdentity = sourceProjectIdentity,
            )
            ownedSnapshots[snapshot] = Unit
            snapshot
        } catch (error: Throwable) {
            deleteTree(snapshotRoot)
            throw error
        }
    }

    fun <T> transform(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        transform: (AdapterExecutionContext) -> T,
    ): T = transformWith(
        snapshot,
        profile,
        ConversionWorkspaceTransaction(),
        transform,
    )

    internal fun <T> transformForTesting(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        moveDirectory: (Path, Path) -> Unit = { from, to ->
            Files.move(from, to, ATOMIC_MOVE)
        },
        beforePublish: () -> Unit = {},
        transform: (AdapterExecutionContext) -> T,
    ): T = transformWith(
        snapshot,
        profile,
        ConversionWorkspaceTransaction(
            moveDirectory = moveDirectory,
            beforePublish = beforePublish,
        ),
        transform,
    )

    private fun <T> transformWith(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        transaction: ConversionWorkspaceTransaction,
        transform: (AdapterExecutionContext) -> T,
    ): T {
        check(!closed) { "이미 정리된 conversion workspace입니다." }
        check(ownedSnapshots.containsKey(snapshot)) {
            "이 ConversionWorkspace가 발급한 snapshot이 아닙니다."
        }
        profile.validate()
        return try {
            transaction.run(
                originalRoot = snapshot.originalRoot,
                workingRoot = snapshot.workingRoot,
                profile = profile,
                transform = transform,
            )
        } catch (error: Error) {
            throw error
        } catch (_: Throwable) {
            throw ConversionExecutionException()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ownedWorkingRoots.forEach(ConversionWorkspaceOwnership::unregister)
        ownedSnapshots.clear()
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
