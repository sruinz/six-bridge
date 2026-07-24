package dev.sixbridge.core.conversion

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
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
    private enum class OwnerState {
        ACTIVE,
        PENDING,
        CLOSE_RETRY,
        ABANDON_CLOSE_RETRY,
    }

    private class Owner(
        @Volatile var root: Path,
        val parentPath: Path,
        @Volatile var name: Path,
        val rootFileKey: Any,
        val parentFileKey: Any,
        val rootHandle: SecureDirectoryStream<Path>,
        val parentHandle: SecureDirectoryStream<Path>,
        val token: UUID,
        val parentToken: UUID?,
        val treeToken: UUID,
    ) {
        val lock = ReentrantLock()

        @Volatile
        var state: OwnerState = OwnerState.ACTIVE

        var rootHandleClosed: Boolean = false
        var parentHandleClosed: Boolean = false
    }

    private val owners = ConcurrentHashMap<Path, Owner>()
    private val ownersByToken = ConcurrentHashMap<UUID, Owner>()
    private val closeRetryOwners = ConcurrentHashMap.newKeySet<UUID>()
    private val registryLock = ReentrantLock()

    @JvmSynthetic
    fun createTempDirectory(prefix: String): OwnedTempDirectory {
        retryPending()
        return registryLock.withLock {
            requireCapacity(parent = null)
            val created = Files.createTempDirectory(prefix)
                .toAbsolutePath()
                .normalize()
            sealTopLevel(created)
        }
    }

    @JvmSynthetic
    fun createTempDirectory(
        parent: OwnedTempDirectory,
        prefix: String,
    ): OwnedTempDirectory {
        retryPending()
        val parentOwner = requireRegistered(parent)
        return createNested(parentOwner) {
            Files.createTempDirectory(parentOwner.root, prefix)
                .toAbsolutePath()
                .normalize()
        }
    }

    @JvmSynthetic
    fun createNamed(
        parent: OwnedTempDirectory,
        name: String,
    ): OwnedTempDirectory =
        createNamed(requireRegistered(parent), name)

    @JvmSynthetic
    fun createNamed(parent: Path, name: String): OwnedTempDirectory =
        createNamed(requireRegistered(parent), name)

    @JvmSynthetic
    fun createAt(path: Path): OwnedTempDirectory {
        retryPending()
        return registryLock.withLock {
            requireCapacity(parent = null)
            val normalized = path.toAbsolutePath().normalize()
            val created = try {
                Files.createDirectory(normalized)
            } catch (_: Exception) {
                throw OwnedTempCleanupException()
            }
            check(created == normalized)
            sealTopLevel(created)
        }
    }

    @JvmSynthetic
    fun requireOwned(path: Path) {
        requireRegistered(path)
    }

    @JvmSynthetic
    fun move(from: Path, to: Path): Path {
        val source = from.toAbsolutePath().normalize()
        val destination = to.toAbsolutePath().normalize()
        val owner = requireRegistered(source)
        return owner.lock.withLock {
            registryLock.withLock {
                try {
                    check(owner.state == OwnerState.ACTIVE)
                    check(owner.root == source)
                    check(destination.parent == owner.parentPath)
                    check(owners[destination] == null)
                    verifyLocation(owner)
                    check(
                        readAttributesOrNull(
                            owner.parentHandle,
                            destination.fileName,
                        ) == null,
                    )
                    try {
                        owner.parentHandle.move(
                            owner.name,
                            owner.parentHandle,
                            destination.fileName,
                        )
                        val moved = readAttributes(
                            owner.parentHandle,
                            destination.fileName,
                        )
                        check(
                            moved.isDirectory &&
                                !moved.isSymbolicLink &&
                                moved.fileKey() == owner.rootFileKey,
                        )
                    } catch (moveError: Throwable) {
                        recoverMoveLocation(
                            owner = owner,
                            sourceName = source.fileName,
                            destinationName = destination.fileName,
                        )
                        throw moveError
                    }
                    transitionLocation(owner, destination.fileName)
                    destination
                } catch (_: Throwable) {
                    throw OwnedTempCleanupException()
                }
            }
        }
    }

    @JvmSynthetic
    fun delete(owner: OwnedTempDirectory) {
        deleteRegistered(requireRegistered(owner))
    }

    @JvmSynthetic
    fun delete(path: Path) {
        deleteRegistered(requireRegistered(path))
    }

    @JvmSynthetic
    fun abandon(owner: OwnedTempDirectory) {
        abandonRegistered(requireRegistered(owner))
    }

    @JvmSynthetic
    fun abandon(path: Path) {
        abandonRegistered(requireRegistered(path))
    }

    private fun createNamed(
        parentOwner: Owner,
        name: String,
    ): OwnedTempDirectory {
        retryPending()
        val childName = singleName(name)
        return createNested(parentOwner) {
            val created = parentOwner.root.resolve(childName.toString())
            Files.createDirectory(created)
                .toAbsolutePath()
                .normalize()
        }
    }

    private fun createNested(
        parentOwner: Owner,
        create: () -> Path,
    ): OwnedTempDirectory =
        parentOwner.lock.withLock {
            registryLock.withLock {
                try {
                    check(parentOwner.state == OwnerState.ACTIVE)
                    verifyLocation(parentOwner)
                    requireCapacity(parentOwner)
                    val parentHandle = parentOwner.rootHandle.newDirectoryStream(
                        Path.of("."),
                        NOFOLLOW_LINKS,
                    )
                    val created = try {
                        create()
                    } catch (_: Throwable) {
                        closeQuietly(parentHandle)
                        throw OwnedTempCleanupException()
                    }
                    sealCreated(
                        created = created,
                        parentHandle = parentHandle,
                        parentPath = parentOwner.root,
                        parentToken = parentOwner.token,
                        treeToken = parentOwner.treeToken,
                    )
                } catch (error: OwnedTempCleanupException) {
                    throw error
                } catch (_: Throwable) {
                    throw OwnedTempCleanupException()
                }
            }
        }

    private fun sealTopLevel(created: Path): OwnedTempDirectory {
        val normalized = created.toAbsolutePath().normalize()
        val capturedFileKey = captureCreatedFileKey(normalized)
        val parent = requireNotNull(normalized.parent)
        val stream = try {
            Files.newDirectoryStream(parent)
        } catch (_: Throwable) {
            cleanupWithoutHandle(normalized, capturedFileKey)
            throw OwnedTempCleanupException()
        }
        if (stream !is SecureDirectoryStream<Path>) {
            closeQuietly(stream)
            cleanupWithoutHandle(normalized, capturedFileKey)
            throw OwnedTempCleanupException()
        }
        return sealCreated(
            created = normalized,
            parentHandle = stream,
            parentPath = parent,
            parentToken = null,
            treeToken = null,
            capturedFileKey = capturedFileKey,
        )
    }

    private fun sealCreated(
        created: Path,
        parentHandle: SecureDirectoryStream<Path>,
        parentPath: Path,
        parentToken: UUID?,
        treeToken: UUID?,
        capturedFileKey: Any = captureCreatedFileKey(created),
    ): OwnedTempDirectory {
        val normalizedRoot = created.toAbsolutePath().normalize()
        val normalizedParent = parentPath.toAbsolutePath().normalize()
        val name = singleName(normalizedRoot.fileName.toString())
        var rootHandle: SecureDirectoryStream<Path>? = null
        try {
            check(normalizedRoot.parent == normalizedParent)
            val pathParent = Files.readAttributes(
                normalizedParent,
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
            val relativeRoot = readAttributes(parentHandle, name)
            check(
                relativeRoot.isDirectory &&
                    !relativeRoot.isSymbolicLink &&
                    relativeRoot.fileKey() == capturedFileKey,
            )
            rootHandle = parentHandle.newDirectoryStream(
                name,
                NOFOLLOW_LINKS,
            )
            val openedRoot = readAttributes(rootHandle)
            check(
                openedRoot.isDirectory &&
                    !openedRoot.isSymbolicLink &&
                    openedRoot.fileKey() == capturedFileKey,
            )
            val token = UUID.randomUUID()
            verifyMarkerToken(
                root = normalizedRoot,
                rootHandle = rootHandle,
                token = token,
                expectedFileKey = capturedFileKey,
            )
            val owner = Owner(
                root = normalizedRoot,
                parentPath = normalizedParent,
                name = name,
                rootFileKey = capturedFileKey,
                parentFileKey = requireNotNull(handleParent.fileKey()),
                rootHandle = rootHandle,
                parentHandle = parentHandle,
                token = token,
                parentToken = parentToken,
                treeToken = treeToken ?: token,
            )
            check(owners.putIfAbsent(normalizedRoot, owner) == null)
            check(ownersByToken.putIfAbsent(token, owner) == null)
            return OwnedTempDirectory(normalizedRoot, token)
        } catch (_: Throwable) {
            cleanupRegistrationFailure(
                root = normalizedRoot,
                name = name,
                capturedFileKey = capturedFileKey,
                parentHandle = parentHandle,
                rootHandle = rootHandle,
            )
            throw OwnedTempCleanupException()
        }
    }

    private fun captureCreatedFileKey(path: Path): Any {
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        } catch (_: Throwable) {
            throw OwnedTempCleanupException()
        }
        check(attributes.isDirectory && !attributes.isSymbolicLink)
        return attributes.fileKey() ?: throw OwnedTempCleanupException()
    }

    private fun verifyMarkerToken(
        root: Path,
        rootHandle: SecureDirectoryStream<Path>,
        token: UUID,
        expectedFileKey: Any,
    ) {
        val marker = singleName(".sixbridge-owner-probe-$token")
        val bytes = token.toString().toByteArray(Charsets.UTF_8)
        rootHandle.newByteChannel(
            marker,
            MARKER_CREATE_OPTIONS,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        val anchored = rootHandle.newByteChannel(
            marker,
            setOf(READ, NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.allocate(bytes.size)
            while (buffer.hasRemaining()) {
                check(channel.read(buffer) >= 0)
            }
            buffer.array()
        }
        val pathRoot = Files.readAttributes(
            root,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        check(
            pathRoot.isDirectory &&
                !pathRoot.isSymbolicLink &&
                pathRoot.fileKey() == expectedFileKey,
        )
        val absolute = Files.readAllBytes(root.resolve(marker.toString()))
        check(bytes.contentEquals(anchored) && bytes.contentEquals(absolute))
        rootHandle.deleteFile(marker)
    }

    private fun cleanupRegistrationFailure(
        root: Path,
        name: Path,
        capturedFileKey: Any,
        parentHandle: SecureDirectoryStream<Path>,
        rootHandle: SecureDirectoryStream<Path>?,
    ) {
        if (rootHandle == null) {
            closeQuietly(parentHandle)
            cleanupWithoutHandle(root, capturedFileKey)
            return
        }
        runCatching {
            deleteContents(rootHandle)
            val attributes = readAttributesOrNull(parentHandle, name)
            if (
                attributes != null &&
                attributes.isDirectory &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() == capturedFileKey
            ) {
                parentHandle.deleteDirectory(name)
            }
        }
        closeQuietly(rootHandle)
        closeQuietly(parentHandle)
    }

    private fun cleanupWithoutHandle(root: Path, capturedFileKey: Any) {
        runCatching {
            val before = Files.readAttributes(
                root,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            check(
                before.isDirectory &&
                    !before.isSymbolicLink &&
                    before.fileKey() == capturedFileKey,
            )
            check(Files.list(root).use { it.findAny().isEmpty })
            val after = Files.readAttributes(
                root,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            check(
                after.isDirectory &&
                    !after.isSymbolicLink &&
                    after.fileKey() == capturedFileKey,
            )
            Files.deleteIfExists(root)
        }
    }

    private fun requireCapacity(parent: Owner?) {
        if (ownersByToken.size >= MAX_ACTIVE_OWNERS) {
            throw OwnedTempCleanupException()
        }
        if (parent != null) {
            val treeOwners = ownersByToken.values.count {
                it.treeToken == parent.treeToken
            }
            if (treeOwners >= OwnedTempTree.MAX_OWNERS_PER_TREE) {
                throw OwnedTempCleanupException()
            }
        }
    }

    private fun requireRegistered(owner: OwnedTempDirectory): Owner =
        ownersByToken[owner.token]
            ?.takeIf { it.root == owner.rootPath }
            ?: throw OwnedTempCleanupException()

    private fun requireRegistered(path: Path): Owner =
        owners[path.toAbsolutePath().normalize()]
            ?: throw OwnedTempCleanupException()

    private fun retryPending() {
        closeRetryOwners.toList().forEach { token ->
            ownersByToken[token]?.let { owner ->
                runCatching {
                    owner.lock.withLock { finishClose(owner) }
                }
            }
        }
        ownersByToken.values
            .filter { it.state == OwnerState.PENDING }
            .sortedByDescending { it.root.nameCount }
            .forEach { owner ->
                runCatching { deleteRegistered(owner) }
            }
    }

    private fun deleteRegistered(owner: Owner) {
        try {
            owner.lock.withLock {
                if (
                    owner.state == OwnerState.CLOSE_RETRY ||
                    owner.state == OwnerState.ABANDON_CLOSE_RETRY
                ) {
                    finishClose(owner)
                    return
                }
                check(
                    owner.state == OwnerState.ACTIVE ||
                        owner.state == OwnerState.PENDING,
                )
                owner.state = OwnerState.PENDING
                childrenOf(owner)
                    .sortedByDescending { it.root.nameCount }
                    .forEach(::deleteRegistered)
                deleteRoot(owner)
                owner.state = OwnerState.CLOSE_RETRY
                closeRetryOwners += owner.token
                finishClose(owner)
            }
        } catch (_: Throwable) {
            throw OwnedTempCleanupException()
        }
    }

    private fun deleteRoot(owner: Owner) {
        repeat(MAX_DELETE_PASSES) {
            normalizeOwnerAccess(owner.rootHandle)
            deleteContents(owner.rootHandle)
            verifyParent(owner)
            check(ownersByToken[owner.token] === owner)
            check(owners[owner.root] === owner)
            val attributes = readAttributes(owner.parentHandle, owner.name)
            check(
                attributes.isDirectory &&
                    !attributes.isSymbolicLink &&
                    attributes.fileKey() == owner.rootFileKey,
            )
            normalizeOwnerAccess(
                owner.parentHandle,
                owner.name,
                directory = true,
            )
            try {
                owner.parentHandle.deleteDirectory(owner.name)
                return
            } catch (_: DirectoryNotEmptyException) {
                return@repeat
            }
        }
        throw IOException("bounded anchored root cleanup failed")
    }

    private fun abandonRegistered(owner: Owner) {
        try {
            owner.lock.withLock {
                childrenOf(owner)
                    .sortedByDescending { it.root.nameCount }
                    .forEach(::abandonRegistered)
                owner.state = OwnerState.ABANDON_CLOSE_RETRY
                closeRetryOwners += owner.token
                finishClose(owner)
            }
        } catch (_: Throwable) {
            throw OwnedTempCleanupException()
        }
    }

    private fun finishClose(owner: Owner) {
        repeat(MAX_CLOSE_PASSES) {
            if (!owner.rootHandleClosed) {
                owner.rootHandleClosed = runCatching {
                    owner.rootHandle.close()
                }.isSuccess
            }
            if (!owner.parentHandleClosed) {
                owner.parentHandleClosed = runCatching {
                    owner.parentHandle.close()
                }.isSuccess
            }
            if (owner.rootHandleClosed && owner.parentHandleClosed) {
                registryLock.withLock {
                    owners.remove(owner.root, owner)
                    ownersByToken.remove(owner.token, owner)
                    closeRetryOwners.remove(owner.token)
                }
                return
            }
        }
        closeRetryOwners += owner.token
        throw IOException("retained handle close retry failed")
    }

    private fun childrenOf(parent: Owner): List<Owner> =
        ownersByToken.values.filter { it.parentToken == parent.token }

    private fun verifyLocation(owner: Owner) {
        verifyParent(owner)
        val opened = readAttributes(owner.rootHandle)
        val relative = readAttributes(owner.parentHandle, owner.name)
        check(
            opened.isDirectory &&
                !opened.isSymbolicLink &&
                opened.fileKey() == owner.rootFileKey &&
                relative.isDirectory &&
                !relative.isSymbolicLink &&
                relative.fileKey() == owner.rootFileKey &&
                ownersByToken[owner.token] === owner &&
                owners[owner.root] === owner,
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

    private fun recoverMoveLocation(
        owner: Owner,
        sourceName: Path,
        destinationName: Path,
    ) {
        var actual = locateOwnerName(owner)
        if (actual != null && actual != sourceName) {
            val source = readAttributesOrNull(owner.parentHandle, sourceName)
            if (source == null) {
                runCatching {
                    owner.parentHandle.move(
                        actual,
                        owner.parentHandle,
                        sourceName,
                    )
                }
            }
        }
        actual = locateOwnerName(owner)
        if (actual == null) throw IOException("moved owner inode was not found")
        if (actual != sourceName && actual != destinationName) {
            val destination = readAttributesOrNull(
                owner.parentHandle,
                destinationName,
            )
            if (destination == null) {
                runCatching {
                    owner.parentHandle.move(
                        actual,
                        owner.parentHandle,
                        destinationName,
                    )
                }
            }
            actual = locateOwnerName(owner)
                ?: throw IOException("moved owner inode was not found")
        }
        transitionLocation(owner, actual)
    }

    private fun locateOwnerName(owner: Owner): Path? =
        listNames(owner.parentHandle)
            .firstOrNull { name ->
                readAttributesOrNull(owner.parentHandle, name)
                    ?.fileKey() == owner.rootFileKey
            }

    private fun transitionLocation(owner: Owner, name: Path) {
        val previous = owner.root
        val next = owner.parentPath.resolve(name.toString()).normalize()
        owners.remove(previous, owner)
        owner.root = next
        owner.name = name
        owners[next] = owner
    }

    private fun deleteContents(directory: SecureDirectoryStream<Path>) {
        repeat(MAX_DELETE_PASSES) {
            val names = listNames(directory)
            if (names.isEmpty()) return
            names.forEach { name -> deleteEntry(directory, name) }
        }
        throw IOException("bounded anchored cleanup failed")
    }

    private fun listNames(
        directory: SecureDirectoryStream<Path>,
    ): List<Path> =
        directory.newDirectoryStream(Path.of("."), NOFOLLOW_LINKS).use { scan ->
            scan.toList().map { singleName(it.fileName.toString()) }
        }

    private fun deleteEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ) {
        repeat(MAX_DELETE_PASSES) {
            val attributes = readAttributesOrNull(parent, name) ?: return
            if (attributes.isDirectory && !attributes.isSymbolicLink) {
                normalizeOwnerAccess(parent, name, directory = true)
                try {
                    parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
                        deleteContents(child)
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
        directory: SecureDirectoryStream<Path>,
    ) {
        runCatching {
            val view = directory.getFileAttributeView(
                PosixFileAttributeView::class.java,
            ) ?: return@runCatching
            val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
            permissions += view.readAttributes().permissions()
            permissions += PosixFilePermission.OWNER_READ
            permissions += PosixFilePermission.OWNER_WRITE
            permissions += PosixFilePermission.OWNER_EXECUTE
            view.setPermissions(permissions)
        }
        runCatching {
            directory.getFileAttributeView(
                DosFileAttributeView::class.java,
            )?.setReadOnly(false)
        }
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

    private fun closeQuietly(closeable: AutoCloseable) {
        repeat(MAX_CLOSE_PASSES) {
            if (runCatching { closeable.close() }.isSuccess) return
        }
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

    private val MARKER_CREATE_OPTIONS = setOf<OpenOption>(
        CREATE_NEW,
        WRITE,
        NOFOLLOW_LINKS,
    )
    private const val MAX_DELETE_PASSES = 8
    private const val MAX_CLOSE_PASSES = 3
    private const val MAX_ACTIVE_OWNERS = OwnedTempTree.MAX_ACTIVE_OWNERS
}
