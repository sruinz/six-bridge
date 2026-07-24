package dev.sixbridge.core.conversion

import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmSynthetic

internal object PendingTempCleanup {
    private class Owner(
        @Volatile var root: Path,
        val parentPath: Path,
        @Volatile var name: Path,
        val rootFileKey: Any,
        val parentFileKey: Any,
        val parentHandle: SecureDirectoryStream<Path>,
        val token: UUID,
        val parentToken: UUID?,
    ) {
        val lock = ReentrantLock()

        @Volatile
        var pending: Boolean = false
    }

    private val owners = ConcurrentHashMap<Path, Owner>()

    @JvmSynthetic
    fun createTempDirectory(prefix: String): Path {
        retryPending()
        val created = Files.createTempDirectory(prefix)
        return registerTopLevel(created)
    }

    @JvmSynthetic
    fun createTempDirectory(parent: Path, prefix: String): Path {
        retryPending()
        val normalizedParent = parent.toAbsolutePath().normalize()
        requireRegistered(normalizedParent)
        val created = try {
            Files.createTempDirectory(normalizedParent, prefix)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
        return registerCreated(created)
    }

    @JvmSynthetic
    fun createDirectory(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val parent = requireNotNull(normalized.parent)
        val parentOwner = owners[parent]
        try {
            Files.createDirectory(normalized)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
        return if (parentOwner == null) {
            registerTopLevel(normalized)
        } else {
            registerCreated(normalized)
        }
    }

    @JvmSynthetic
    fun registerCreated(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        val parent = requireNotNull(normalized.parent)
        val parentOwner = requireRegistered(parent)
        val handle = retainedChildHandle(parentOwner)
        return try {
            register(
                root = normalized,
                parentPath = parent,
                parentHandle = handle,
                parentToken = parentOwner.token,
            )
        } catch (_: Exception) {
            runCatching { handle.close() }
            throw OwnedTempCleanupException()
        }
    }

    @JvmSynthetic
    fun move(from: Path, to: Path): Path {
        val source = from.toAbsolutePath().normalize()
        val destination = to.toAbsolutePath().normalize()
        val owner = requireRegistered(source)
        return owner.lock.withLock {
            try {
                check(destination.parent == owner.parentPath)
                check(owners[destination] == null)
                verifyOwner(owner)
                check(readAttributesOrNull(owner.parentHandle, destination.fileName) == null)
                owner.parentHandle.move(
                    owner.name,
                    owner.parentHandle,
                    destination.fileName,
                )
                val moved = readAttributes(owner.parentHandle, destination.fileName)
                check(
                    moved.isDirectory &&
                        !moved.isSymbolicLink &&
                        moved.fileKey() == owner.rootFileKey,
                )
                check(readAttributesOrNull(owner.parentHandle, owner.name) == null)
                check(owners.remove(source, owner))
                owner.root = destination
                owner.name = destination.fileName
                check(owners.putIfAbsent(destination, owner) == null)
                destination
            } catch (_: Exception) {
                throw OwnedTempCleanupException()
            }
        }
    }

    @JvmSynthetic
    fun delete(path: Path) {
        val target = path.toAbsolutePath().normalize()
        val owner = owners[target] ?: throw OwnedTempCleanupException()
        try {
            deleteRegistered(owner)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
    }

    private fun registerTopLevel(created: Path): Path {
        val root = try {
            created.toRealPath(NOFOLLOW_LINKS)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
        val parent = requireNotNull(root.parent)
        val stream = try {
            Files.newDirectoryStream(parent)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
        if (stream !is SecureDirectoryStream<Path>) {
            runCatching { stream.close() }
            throw OwnedTempCleanupException()
        }
        return try {
            register(
                root = root,
                parentPath = parent,
                parentHandle = stream,
                parentToken = null,
            )
        } catch (_: Exception) {
            runCatching { stream.close() }
            throw OwnedTempCleanupException()
        }
    }

    private fun register(
        root: Path,
        parentPath: Path,
        parentHandle: SecureDirectoryStream<Path>,
        parentToken: UUID?,
    ): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedParent = parentPath.toAbsolutePath().normalize()
        check(normalizedRoot.parent == normalizedParent)
        val pathParent = Files.readAttributes(
            normalizedParent,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val pathRoot = Files.readAttributes(
            normalizedRoot,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val handleParent = readAttributes(parentHandle)
        check(
            pathParent.isDirectory &&
                !pathParent.isSymbolicLink &&
                pathParent.fileKey() != null &&
                pathParent.fileKey() == handleParent.fileKey(),
        )
        check(
            pathRoot.isDirectory &&
                !pathRoot.isSymbolicLink &&
                pathRoot.fileKey() != null,
        )
        val name = singleName(normalizedRoot.fileName.toString())
        val rootAttributes = readAttributes(parentHandle, name)
        check(
            rootAttributes.isDirectory &&
                !rootAttributes.isSymbolicLink &&
                rootAttributes.fileKey() == pathRoot.fileKey(),
        )
        val owner = Owner(
            root = normalizedRoot,
            parentPath = normalizedParent,
            name = name,
            rootFileKey = requireNotNull(pathRoot.fileKey()),
            parentFileKey = requireNotNull(handleParent.fileKey()),
            parentHandle = parentHandle,
            token = UUID.randomUUID(),
            parentToken = parentToken,
        )
        check(owners.putIfAbsent(normalizedRoot, owner) == null)
        return normalizedRoot
    }

    private fun retainedChildHandle(owner: Owner): SecureDirectoryStream<Path> =
        owner.lock.withLock {
            try {
                verifyOwner(owner)
                owner.parentHandle.newDirectoryStream(
                    owner.name,
                    NOFOLLOW_LINKS,
                )
            } catch (_: Exception) {
                throw OwnedTempCleanupException()
            }
        }

    private fun requireRegistered(path: Path): Owner =
        owners[path.toAbsolutePath().normalize()]
            ?: throw OwnedTempCleanupException()

    private fun retryPending() {
        owners.values
            .filter(Owner::pending)
            .sortedByDescending { it.root.nameCount }
            .forEach { owner ->
                runCatching { deleteRegistered(owner) }
            }
    }

    private fun deleteRegistered(owner: Owner) {
        owner.lock.withLock {
            check(owners[owner.root] === owner)
            owner.pending = true
            childrenOf(owner)
                .sortedByDescending { it.root.nameCount }
                .forEach(::deleteRegistered)
            verifyParent(owner)
            deleteEntry(
                parent = owner.parentHandle,
                name = owner.name,
                expectedFileKey = owner.rootFileKey,
                missingIsFailure = true,
            )
            check(owners.remove(owner.root, owner))
            owner.pending = false
            runCatching { owner.parentHandle.close() }
        }
    }

    private fun childrenOf(parent: Owner): List<Owner> =
        owners.values.filter { it.parentToken == parent.token }

    private fun verifyOwner(owner: Owner) {
        verifyParent(owner)
        val attributes = readAttributes(owner.parentHandle, owner.name)
        check(
            attributes.isDirectory &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() == owner.rootFileKey,
        )
    }

    private fun verifyParent(owner: Owner) {
        val attributes = readAttributes(owner.parentHandle)
        check(
            attributes.isDirectory &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() == owner.parentFileKey,
        )
    }

    private fun deleteEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        expectedFileKey: Any? = null,
        missingIsFailure: Boolean = false,
    ) {
        repeat(MAX_DELETE_PASSES) {
            val attributes = readAttributesOrNull(parent, name)
                ?: if (missingIsFailure) {
                    throw NoSuchFileException(name.toString())
                } else {
                    return
                }
            if (expectedFileKey != null) {
                check(attributes.fileKey() == expectedFileKey)
            }
            if (attributes.isDirectory && !attributes.isSymbolicLink) {
                normalizeOwnerAccess(parent, name, directory = true)
                try {
                    parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                        child.toList()
                            .map { singleName(it.fileName.toString()) }
                            .forEach { childName ->
                                deleteEntry(child, childName)
                            }
                    }
                } catch (error: NoSuchFileException) {
                    if (missingIsFailure) throw error
                    return
                } catch (_: NotDirectoryException) {
                    return@repeat
                }
                normalizeOwnerAccess(parent, name, directory = true)
                try {
                    parent.deleteDirectory(name)
                    return
                } catch (error: NoSuchFileException) {
                    if (missingIsFailure) throw error
                    return
                } catch (_: DirectoryNotEmptyException) {
                    return@repeat
                } catch (_: NotDirectoryException) {
                    return@repeat
                } catch (_: IOException) {
                    return@repeat
                }
            } else {
                if (!attributes.isSymbolicLink) {
                    normalizeOwnerAccess(parent, name, directory = false)
                }
                try {
                    parent.deleteFile(name)
                    return
                } catch (error: NoSuchFileException) {
                    if (missingIsFailure) throw error
                    return
                } catch (_: IOException) {
                    return@repeat
                }
            }
        }
        throw IOException("bounded anchored cleanup failed")
    }

    private fun normalizeOwnerAccess(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        directory: Boolean,
    ) {
        runCatching {
            val view = parent.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ) ?: return@runCatching
            val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
            permissions += view.readAttributes().permissions()
            permissions += PosixFilePermission.OWNER_READ
            permissions += PosixFilePermission.OWNER_WRITE
            if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
            view.setPermissions(permissions)
        }
        runCatching {
            val view = parent.getFileAttributeView(
                name,
                AclFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ) ?: return@runCatching
            val owner = view.owner
            val ownerAllow = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(
                    EnumSet.allOf(AclEntryPermission::class.java),
                )
                .build()
            view.acl = listOf(ownerAllow) + view.acl.filterNot {
                it.type() == AclEntryType.DENY && it.principal() == owner
            }
        }
        runCatching {
            parent.getFileAttributeView(
                name,
                DosFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            )?.setReadOnly(false)
        }
    }

    private fun readAttributes(
        directory: SecureDirectoryStream<Path>,
    ): BasicFileAttributes =
        requireNotNull(
            directory.getFileAttributeView(
                BasicFileAttributeView::class.java,
            ),
        ).readAttributes()

    private fun readAttributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes =
        requireNotNull(
            directory.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        ).readAttributes()

    private fun readAttributesOrNull(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? =
        try {
            readAttributes(directory, name)
        } catch (_: NoSuchFileException) {
            null
        }

    private fun singleName(value: String): Path =
        Path.of(value).also { name ->
            require(
                name.nameCount == 1 &&
                    name.toString() != "." &&
                    name.toString() != ".." &&
                    !name.isAbsolute,
            )
        }

    private const val MAX_DELETE_PASSES = 8
}
