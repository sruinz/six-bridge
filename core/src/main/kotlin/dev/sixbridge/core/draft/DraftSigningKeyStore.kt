package dev.sixbridge.core.draft

import dev.sixbridge.core.conversion.WorkspacePermissionPolicy
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFileAttributes
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DraftSigningKeyStore(
    trustedRoot: Path,
    keyRoot: Path,
) {
    private val trustedRoot = trustedRoot.toAbsolutePath().normalize()
    private val keyRoot = keyRoot.toAbsolutePath().normalize()
    private val activeGuard = ThreadLocal<StoreGuard?>()

    fun getOrCreate(): KeyPair = withStoreLock {
        val state = existingState()
        when {
            state.all { it } -> readPair()
            state.any { it } -> throw IllegalStateException(
                "SixDraft 전달 key 저장소가 부분 게시 또는 손상 상태입니다.",
            )
            else -> {
                val generated = KeyPairGenerator.getInstance(ALGORITHM)
                    .generateKeyPair()
                publish(generated)
                generated
            }
        }
    }

    private fun existingState(): List<Boolean> = listOf(
        existsNoFollow(identityPath),
        existsNoFollow(privatePath),
        existsNoFollow(publicPath),
    )

    private fun readPair(): KeyPair {
        requireSafeRegular(identityPath)
        require(
            readStable(identityPath).contentEquals(IDENTITY_CONTENT),
        ) {
            "SixDraft 전달 key identity metadata가 일치하지 않습니다."
        }
        requireSafeRegular(privatePath)
        requireSafeRegular(publicPath)
        val factory = KeyFactory.getInstance(ALGORITHM)
        val pair = try {
            KeyPair(
                factory.generatePublic(X509EncodedKeySpec(readStable(publicPath))),
                factory.generatePrivate(PKCS8EncodedKeySpec(readStable(privatePath))),
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "SixDraft 전달 key 파일이 손상되었습니다.",
                error,
            )
        }
        validatePair(pair)
        return pair
    }

    private fun publish(pair: KeyPair) {
        validatePair(pair)
        require(existingState().none { it }) {
            "기존 SixDraft 전달 key 상태를 덮어쓸 수 없습니다."
        }
        val suffix = ".tmp-${UUID.randomUUID()}"
        val privateTemp = keyRoot.resolve("$PRIVATE_FILE$suffix")
        val publicTemp = keyRoot.resolve("$PUBLIC_FILE$suffix")
        val identityTemp = keyRoot.resolve("$IDENTITY_FILE$suffix")
        val temps = listOf(privateTemp, publicTemp, identityTemp)
        try {
            writeOwnerOnly(privateTemp, pair.private.encoded)
            writeOwnerOnly(publicTemp, pair.public.encoded)
            writeOwnerOnly(identityTemp, IDENTITY_CONTENT)
            atomicPublish(privateTemp, privatePath)
            atomicPublish(publicTemp, publicPath)
            forceDirectory()
            atomicPublish(identityTemp, identityPath)
            forceDirectory()
        } finally {
            temps.forEach { temp ->
                if (existsNoFollow(temp)) {
                    requireSafeRegular(temp)
                    deleteAnchored(temp)
                }
            }
        }
    }

    private fun validatePair(pair: KeyPair) {
        val challenge = "sixbridge-draft-key-pair-proof"
            .toByteArray(Charsets.UTF_8)
        val signed = try {
            Signature.getInstance(ALGORITHM).run {
                initSign(pair.private)
                update(challenge)
                sign()
            }
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "SixDraft 전달 개인 키가 Ed25519 형식이 아닙니다.",
                error,
            )
        }
        require(
            try {
                Signature.getInstance(ALGORITHM).run {
                    initVerify(pair.public)
                    update(challenge)
                    verify(signed)
                }
            } catch (_: Exception) {
                false
            },
        ) {
            "SixDraft 전달 공개 키와 개인 키가 서로 대응하지 않습니다."
        }
    }

    private fun <T> withStoreLock(block: () -> T): T {
        preflightWithoutMutation()
        ensureRoot()
        return PROCESS_LOCKS.computeIfAbsent(keyRoot) { ReentrantLock() }
            .withLock {
                validateRoot()
                val ancestorGuards = captureAncestorGuards()
                secureRoot().use { rootHandle ->
                    val rootState = stableDirectoryState(keyRoot)
                    require(readAttributes(rootHandle).fileKey() == rootState.fileKey) {
                        "SixDraft key root handle identity를 확인할 수 없습니다."
                    }
                    val guard = StoreGuard(rootHandle, rootState, ancestorGuards)
                    activeGuard.set(guard)
                    try {
                        verifyGuard(guard)
                        ensureLockFile(guard)
                        val lockBefore = stableFileState(lockPath)
                        val channel = guard.handle.newByteChannel(
                            Path.of(LOCK_FILE),
                            setOf(WRITE, NOFOLLOW_LINKS),
                        ) as? FileChannel ?: throw IllegalStateException(
                            "SixDraft key lock을 FileChannel로 열 수 없습니다.",
                        )
                        channel.use {
                            val opened = readAttributes(
                                guard.handle,
                                Path.of(LOCK_FILE),
                            )
                            require(
                                opened.fileKey() == lockBefore.fileKey &&
                                    channel.size() == lockBefore.size &&
                                    stableFileState(lockPath) == lockBefore,
                            ) {
                                "SixDraft key lock identity가 open 중 변경되었습니다."
                            }
                            val result = it.lock().use {
                                require(stableFileState(lockPath) == lockBefore) {
                                    "SixDraft key lock identity가 lock 중 변경되었습니다."
                                }
                                verifyGuard(guard)
                                block().also { verifyGuard(guard) }
                            }
                            require(stableFileState(lockPath) == lockBefore) {
                                "SixDraft key lock identity가 사용 중 변경되었습니다."
                            }
                            result
                        }
                    } finally {
                        activeGuard.remove()
                    }
                }
            }
    }

    private fun preflightWithoutMutation() {
        require(keyRoot.startsWith(trustedRoot) && keyRoot != trustedRoot) {
            "SixDraft key root는 trusted root 내부여야 합니다."
        }
        requireDirectory(trustedRoot, "trusted root")
        if (!Files.exists(keyRoot, NOFOLLOW_LINKS)) return
        validateRoot()
        val allowedNames = setOf(
            IDENTITY_FILE,
            PRIVATE_FILE,
            PUBLIC_FILE,
            LOCK_FILE,
        )
        Files.newDirectoryStream(keyRoot).use { stream ->
            val names = stream.map { it.fileName.toString() }.toSet()
            require(names.all(allowedNames::contains)) {
                "SixDraft 전달 key 저장소에 알 수 없는 기존 항목이 있습니다."
            }
        }
        val state = existingState()
        check(state.all { it } || state.none { it }) {
            "SixDraft 전달 key 저장소가 부분 게시 또는 손상 상태입니다."
        }
        if (state.all { it }) {
            requireSafeRegularWithoutGuard(identityPath)
            requireSafeRegularWithoutGuard(privatePath)
            requireSafeRegularWithoutGuard(publicPath)
        }
        if (Files.exists(lockPath, NOFOLLOW_LINKS)) {
            requireSafeRegularWithoutGuard(lockPath)
        }
    }

    private fun ensureRoot() {
        require(keyRoot.startsWith(trustedRoot) && keyRoot != trustedRoot) {
            "SixDraft key root는 trusted root 내부여야 합니다."
        }
        requireDirectory(trustedRoot, "trusted root")
        var current = trustedRoot
        trustedRoot.relativize(keyRoot).forEach { component ->
            current = current.resolve(component.toString())
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                try {
                    val parent = requireNotNull(current.parent)
                    Files.createDirectory(
                        current,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS),
                    )
                    Files.getFileAttributeView(
                        current,
                        AclFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    )?.setAcl(emptyList())
                    WorkspacePermissionPolicy.requirePrivate(current, trustedRoot)
                    forceDirectoryPath(parent)
                } catch (_: FileAlreadyExistsException) {
                    // 경쟁 생성은 아래 NOFOLLOW 검증을 통과해야 한다.
                }
            }
            requireDirectory(current, "key root ancestor")
        }
        validateRoot()
    }

    private fun validateRoot() {
        requireDirectory(keyRoot, "key root")
        require(Files.getFileStore(keyRoot).supportsFileAttributeView("posix")) {
            "SixDraft key 저장소는 POSIX owner-only 권한을 지원해야 합니다."
        }
        require(
            Files.getPosixFilePermissions(keyRoot, NOFOLLOW_LINKS) ==
                DIRECTORY_PERMISSIONS,
        ) {
            "기존 SixDraft key root 권한이 owner-only가 아닙니다."
        }
        requireSameOwner(keyRoot)
        WorkspacePermissionPolicy.requirePrivate(keyRoot, trustedRoot)
    }

    private fun requireDirectory(path: Path, label: String) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "SixDraft $label 경로는 symlink가 아닌 디렉터리여야 합니다: $path"
        }
        requireNotNull(attributes.fileKey()) {
            "SixDraft $label identity를 확인할 수 없습니다: $path"
        }
    }

    private fun captureAncestorGuards(): List<PathGuard> {
        val result = mutableListOf<PathGuard>()
        var current = trustedRoot
        result += pathGuard(current)
        trustedRoot.relativize(keyRoot).forEach { component ->
            current = current.resolve(component.toString())
            result += pathGuard(current)
        }
        return result
    }

    private fun pathGuard(path: Path): PathGuard {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "SixDraft key ancestor가 실제 디렉터리가 아닙니다: $path"
        }
        return PathGuard(
            path,
            requireNotNull(attributes.fileKey()) {
                "SixDraft key ancestor identity를 확인할 수 없습니다: $path"
            },
        )
    }

    private fun ensureLockFile(guard: StoreGuard) {
        val lockName = Path.of(LOCK_FILE)
        if (readAttributesOrNull(guard.handle, lockName) == null) {
            try {
                guard.handle.newByteChannel(
                    lockName,
                    setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS),
                ).use { channel ->
                    (channel as? FileChannel)?.force(true)
                        ?: throw IllegalStateException(
                            "SixDraft key lock을 fsync할 수 없습니다.",
                        )
                }
                setOwnerOnly(guard.handle, lockName)
                forceDirectory()
            } catch (_: FileAlreadyExistsException) {
                // 다른 프로세스가 먼저 생성했으므로 아래에서 검증한다.
            }
        }
        requireSafeRegular(lockPath)
    }

    private fun verifyGuard(guard: StoreGuard) {
        guard.ancestors.forEach { expected ->
            val attributes = Files.readAttributes(
                expected.path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(
                attributes.isDirectory &&
                    !attributes.isSymbolicLink &&
                    attributes.fileKey() == expected.fileKey,
            ) {
                "SixDraft key ancestor identity가 lock 중 변경되었습니다."
            }
        }
        val handleAttributes = readAttributes(guard.handle)
        val currentRootState = stableDirectoryState(keyRoot)
        require(
            handleAttributes.isDirectory &&
                !handleAttributes.isSymbolicLink &&
                handleAttributes.fileKey() == guard.rootState.fileKey &&
                currentRootState == guard.rootState,
        ) {
            "SixDraft key root handle identity가 lock 중 변경되었습니다."
        }
    }

    private fun requireSafeRegular(path: Path) {
        activeGuard.get()?.let(::verifyGuard)
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "SixDraft key 파일은 symlink가 아닌 일반 파일이어야 합니다: $path"
        }
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == FILE_PERMISSIONS) {
            "SixDraft key 파일은 owner-only 권한이어야 합니다: $path"
        }
        val links = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number)
            .toLong()
        require(links == 1L) {
            "SixDraft key 파일 hardlink는 허용되지 않습니다: $path"
        }
        requireSameOwner(path)
        WorkspacePermissionPolicy.requirePrivate(path, trustedRoot)
        activeGuard.get()?.let(::verifyGuard)
    }

    private fun requireSafeRegularWithoutGuard(path: Path) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(
            attributes.isRegularFile &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() != null,
        ) {
            "SixDraft key 파일은 identity가 확인되는 일반 파일이어야 합니다: $path"
        }
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == FILE_PERMISSIONS) {
            "SixDraft key 파일은 owner-only 권한이어야 합니다: $path"
        }
        require(
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number)
                .toLong() == 1L,
        ) {
            "SixDraft key 파일 hardlink는 허용되지 않습니다: $path"
        }
        requireSameOwner(path)
        WorkspacePermissionPolicy.requirePrivate(path, trustedRoot)
    }

    private fun readStable(path: Path): ByteArray {
        requireSafeRegular(path)
        val guard = requireNotNull(activeGuard.get())
        val name = relativeName(path)
        val before = stableFileState(path)
        val bytes = guard.handle.newByteChannel(
            name,
            setOf(READ, NOFOLLOW_LINKS),
        ).use { channel ->
            val opened = readAttributes(guard.handle, name)
            require(opened.fileKey() != null && opened.fileKey() == before.fileKey) {
                "SixDraft key 파일 identity가 open 중 변경되었습니다."
            }
            val size = channel.size()
            require(size == before.size && size <= MAX_KEY_FILE_SIZE) {
                "SixDraft key 파일이 비정상적으로 큽니다."
            }
            val result = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) {
                require(channel.read(buffer) >= 0) {
                    "SixDraft key 파일을 완전히 읽지 못했습니다."
                }
            }
            require(channel.read(ByteBuffer.allocate(1)) < 0) {
                "SixDraft key 파일이 읽는 동안 커졌습니다."
            }
            require(stableFileState(path) == before) {
                "SixDraft key 파일이 읽는 동안 변경되었습니다."
            }
            result
        }
        require(stableFileState(path) == before) {
            "SixDraft key 파일이 읽는 동안 변경되었습니다."
        }
        verifyGuard(guard)
        return bytes
    }

    private fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        val guard = requireNotNull(activeGuard.get())
        verifyGuard(guard)
        val name = relativeName(path)
        guard.handle.newByteChannel(
            name,
            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("SixDraft key 파일을 fsync할 수 없습니다.")
        }
        setOwnerOnly(guard.handle, name)
        requireSafeRegular(path)
    }

    private fun atomicPublish(source: Path, target: Path) {
        val guard = requireNotNull(activeGuard.get())
        verifyGuard(guard)
        guard.handle.move(
            relativeName(source),
            guard.handle,
            relativeName(target),
        )
        verifyGuard(guard)
        requireSafeRegular(target)
    }

    private fun forceDirectory() {
        val guard = requireNotNull(activeGuard.get())
        verifyGuard(guard)
        FileChannel.open(keyRoot, READ, NOFOLLOW_LINKS).use { it.force(true) }
        verifyGuard(guard)
    }

    private fun forceDirectoryPath(path: Path) {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun requireSameOwner(path: Path) {
        val expected = ownerToken(trustedRoot)
        val actual = ownerToken(path)
        require(expected == actual) {
            "SixDraft key 경로 owner가 trusted root owner와 일치하지 않습니다: $path"
        }
    }

    private fun ownerToken(path: Path): String = runCatching {
        "uid:${(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()}"
    }.getOrElse {
        val owner = Files.readAttributes(
            path,
            PosixFileAttributes::class.java,
            NOFOLLOW_LINKS,
        ).owner()
        "principal:${owner.name}"
    }

    private fun stableFileState(path: Path): StableFileState {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        return StableFileState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "SixDraft key 파일 identity를 확인할 수 없습니다."
            },
            size = attributes.size(),
            modified = attributes.lastModifiedTime().toMillis(),
            permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS),
            owner = ownerToken(path),
            device = (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number)
                .toLong(),
            inode = (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number)
                .toLong(),
            links = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number)
                .toLong(),
        )
    }

    private fun stableDirectoryState(path: Path): StableDirectoryState {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "SixDraft key directory identity를 확인할 수 없습니다."
        }
        return StableDirectoryState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "SixDraft key directory identity를 확인할 수 없습니다."
            },
            permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS),
            owner = ownerToken(path),
            device = (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number)
                .toLong(),
            inode = (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number)
                .toLong(),
        )
    }

    private fun setOwnerOnly(
        handle: SecureDirectoryStream<Path>,
        name: Path,
    ) {
        val view = requireNotNull(
            handle.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        )
        view.setPermissions(FILE_PERMISSIONS)
        handle.getFileAttributeView(
            name,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )?.setAcl(emptyList())
        require(view.readAttributes().permissions() == FILE_PERMISSIONS) {
            "SixDraft key 파일 owner-only 권한을 확인할 수 없습니다."
        }
    }

    private fun existsNoFollow(path: Path): Boolean {
        activeGuard.get()?.let(::verifyGuard)
        val result = Files.exists(path, NOFOLLOW_LINKS)
        activeGuard.get()?.let(::verifyGuard)
        return result
    }

    private fun deleteAnchored(path: Path) {
        val guard = requireNotNull(activeGuard.get())
        verifyGuard(guard)
        guard.handle.deleteFile(relativeName(path))
        verifyGuard(guard)
    }

    private fun secureRoot(): SecureDirectoryStream<Path> {
        val stream = Files.newDirectoryStream(keyRoot)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            throw IllegalStateException(
                "SixDraft key 저장소가 secure directory stream을 지원하지 않습니다.",
            )
        }
        return stream
    }

    private fun relativeName(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == keyRoot) {
            "SixDraft key 파일은 key root의 직접 자식이어야 합니다."
        }
        return Path.of(normalized.fileName.toString())
    }

    private fun readAttributes(
        handle: SecureDirectoryStream<Path>,
    ): BasicFileAttributes = requireNotNull(
        handle.getFileAttributeView(BasicFileAttributeView::class.java),
    ).readAttributes()

    private fun readAttributes(
        handle: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes = requireNotNull(
        handle.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ),
    ).readAttributes()

    private fun readAttributesOrNull(
        handle: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? = try {
        readAttributes(handle, name)
    } catch (_: NoSuchFileException) {
        null
    }

    private val identityPath: Path
        get() = keyRoot.resolve(IDENTITY_FILE)
    private val privatePath: Path
        get() = keyRoot.resolve(PRIVATE_FILE)
    private val publicPath: Path
        get() = keyRoot.resolve(PUBLIC_FILE)
    private val lockPath: Path
        get() = keyRoot.resolve(LOCK_FILE)

    private data class PathGuard(
        val path: Path,
        val fileKey: Any,
    )

    private data class StoreGuard(
        val handle: SecureDirectoryStream<Path>,
        val rootState: StableDirectoryState,
        val ancestors: List<PathGuard>,
    )

    private data class StableFileState(
        val fileKey: Any,
        val size: Long,
        val modified: Long,
        val permissions: Set<PosixFilePermission>,
        val owner: String,
        val device: Long,
        val inode: Long,
        val links: Long,
    )

    private data class StableDirectoryState(
        val fileKey: Any,
        val permissions: Set<PosixFilePermission>,
        val owner: String,
        val device: Long,
        val inode: Long,
    )

    companion object {
        private val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS +
            PosixFilePermission.OWNER_EXECUTE
        private val IDENTITY_CONTENT =
            "sixbridge-draft-transfer-ed25519-v1".toByteArray(Charsets.UTF_8)
        private const val ALGORITHM = "Ed25519"
        private const val IDENTITY_FILE = "draft-transfer.identity"
        private const val PRIVATE_FILE = "draft-transfer.pk8"
        private const val PUBLIC_FILE = "draft-transfer.pub"
        private const val LOCK_FILE = ".sixbridge-draft-key.lock"
        private const val MAX_KEY_FILE_SIZE = 1024 * 1024L
    }
}
