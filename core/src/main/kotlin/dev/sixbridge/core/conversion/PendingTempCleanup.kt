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
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmSynthetic

internal object PendingTempCleanup {
    private data class Owner(
        val root: Path,
        val parent: Path,
        val name: Path,
        val fileKey: Any,
    )

    private val owners = ConcurrentHashMap<Path, Owner>()
    private val pending = ConcurrentHashMap.newKeySet<Path>()
    private val unanchoredPending = ConcurrentHashMap.newKeySet<Path>()

    init {
        runCatching {
            Runtime.getRuntime().addShutdownHook(
                Thread(
                    {
                        repeat(SHUTDOWN_RETRY_PASSES) {
                            if (pending.isEmpty() && unanchoredPending.isEmpty()) {
                                return@Thread
                            }
                            retryPending()
                        }
                    },
                    "sixbridge-temp-cleanup",
                ),
            )
        }
    }

    @JvmSynthetic
    fun createTempDirectory(prefix: String): Path {
        retryPending()
        val created = Files.createTempDirectory(prefix)
        return try {
            registerRoot(created)
        } catch (_: Exception) {
            unanchoredPending.add(created.toAbsolutePath().normalize())
            throw OwnedTempCleanupException()
        }
    }

    @JvmSynthetic
    fun delete(path: Path) {
        val target = path.toAbsolutePath().normalize()
        val owner = ownerFor(target) ?: throw OwnedTempCleanupException()
        pending.add(target)
        try {
            deleteAnchored(owner, target)
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
        pending.remove(target)
        if (target == owner.root) {
            owners.remove(owner.root, owner)
            pending.removeIf { it.startsWith(owner.root) }
        }
    }

    private fun registerRoot(created: Path): Path {
        val root = created.toRealPath(NOFOLLOW_LINKS)
        val parent = requireNotNull(root.parent)
        val name = root.fileName
        val owner = openSecure(parent) { directory ->
            val attributes = readAttributes(directory, name)
            require(attributes.isDirectory && !attributes.isSymbolicLink)
            Owner(
                root = root,
                parent = parent,
                name = name,
                fileKey = requireNotNull(attributes.fileKey()),
            )
        }
        check(owners.putIfAbsent(root, owner) == null)
        return root
    }

    private fun ownerFor(target: Path): Owner? =
        owners.values
            .filter { target.startsWith(it.root) }
            .maxByOrNull { it.root.nameCount }

    private fun retryPending() {
        unanchoredPending.toList().forEach { created ->
            runCatching {
                val root = registerRoot(created)
                unanchoredPending.remove(created)
                pending.add(root)
            }
        }
        pending.toList().forEach { target ->
            runCatching { delete(target) }
        }
    }

    private fun deleteAnchored(owner: Owner, target: Path) {
        val relative = owner.root.relativize(target)
        openSecure(owner.parent) { parent ->
            val rootAttributes = readAttributesOrNull(parent, owner.name)
            if (rootAttributes == null) return@openSecure
            check(
                rootAttributes.isDirectory &&
                    !rootAttributes.isSymbolicLink &&
                    rootAttributes.fileKey() == owner.fileKey,
            )
            if (relative.toString().isEmpty()) {
                deleteEntry(parent, owner.name, owner.fileKey)
            } else {
                parent.newDirectoryStream(owner.name, NOFOLLOW_LINKS).use { root ->
                    withParent(
                        directory = root,
                        components = relative.map { it.toString() },
                        index = 0,
                    ) { targetParent, targetName ->
                        deleteEntry(targetParent, targetName)
                    }
                }
            }
        }
    }

    private fun withParent(
        directory: SecureDirectoryStream<Path>,
        components: List<String>,
        index: Int,
        action: (SecureDirectoryStream<Path>, Path) -> Unit,
    ) {
        val name = singleName(components[index])
        if (index == components.lastIndex) {
            action(directory, name)
            return
        }
        directory.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            withParent(child, components, index + 1, action)
        }
    }

    private fun deleteEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        expectedFileKey: Any? = null,
    ) {
        repeat(MAX_DELETE_PASSES) {
            val attributes = readAttributesOrNull(parent, name) ?: return
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
                } catch (_: NoSuchFileException) {
                    return
                } catch (_: NotDirectoryException) {
                    return@repeat
                }
                normalizeOwnerAccess(parent, name, directory = true)
                try {
                    parent.deleteDirectory(name)
                    return
                } catch (_: NoSuchFileException) {
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
                } catch (_: NoSuchFileException) {
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

    private fun <T> openSecure(
        parent: Path,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T =
        Files.newDirectoryStream(parent).use { stream ->
            require(stream is SecureDirectoryStream<Path>)
            block(stream)
        }

    private const val MAX_DELETE_PASSES = 8
    private const val SHUTDOWN_RETRY_PASSES = 3
}
