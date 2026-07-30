package dev.sixbridge.desktop.security

import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.desktop.DesktopApplicationPaths
import dev.sixbridge.desktop.DesktopHostOs
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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

class DesktopDraftSigningIdentityStore private constructor(
    paths: DesktopApplicationPaths,
    private val hostOs: DesktopHostOs,
    private val windowsAclAccess: WindowsAclAccess,
    private val windowsAtomicMove: WindowsAtomicMove,
) : DraftSigningIdentityProvider {
    constructor(
        paths: DesktopApplicationPaths,
        hostOs: DesktopHostOs,
    ) : this(
        paths = paths,
        hostOs = hostOs,
        windowsAclAccess = NioWindowsAclAccess,
        windowsAtomicMove = NIO_WINDOWS_ATOMIC_MOVE,
    )

    private val dataRoot = paths.dataRoot.toAbsolutePath().normalize()
    private val workRoot = paths.workRoot.toAbsolutePath().normalize()
    private val keyRoot = paths.draftKeyRoot.toAbsolutePath().normalize()

    override fun getOrCreate(): KeyPair =
        PROCESS_LOCKS.computeIfAbsent(keyRoot) { ReentrantLock() }.withLock {
            val roots = prepareRoots()
            withFileLock(roots) {
                validateContents(roots.guard)
                val state = listOf(
                    existsNoFollow(identityPath),
                    existsNoFollow(privatePath),
                    existsNoFollow(publicPath),
                )
                when {
                    state.all { it } -> readPair(roots.guard)
                    state.any { it } -> throw IllegalStateException(
                        "데스크톱 전달 signer가 부분 게시 또는 손상 상태입니다.",
                    )
                    roots.freshKeyRoot -> {
                        val pair = KeyPairGenerator.getInstance(ALGORITHM)
                            .generateKeyPair()
                        publish(pair, roots.guard)
                        pair
                    }
                    else -> throw IllegalStateException(
                        "기존 데스크톱 전달 signer 저장소에 완전한 key pair가 없습니다.",
                    )
                }
            }
        }

    private fun prepareRoots(): PreparedRoots {
        requireLayout()
        val parent = requireNotNull(dataRoot.parent) {
            "데스크톱 앱 데이터 root의 상위 경로가 없습니다."
        }
        val parentState = requireDirectoryShape(parent, "앱 데이터 상위 root")
        val expectedOwner = ownerToken(parent)
        prepareDirectory(dataRoot, expectedOwner, "앱 데이터 root")
        val dataState = secureDirectoryState(dataRoot, "앱 데이터 root")
        val dataOwner = ownerToken(dataRoot)
        prepareDirectory(workRoot, dataOwner, "작업 root")
        require(secureDirectoryState(dataRoot, "앱 데이터 root") == dataState) {
            "앱 데이터 root identity가 작업 root 준비 중 변경되었습니다."
        }
        val freshKeyRoot = prepareDirectory(keyRoot, dataOwner, "signer key root")
        require(secureDirectoryState(dataRoot, "앱 데이터 root") == dataState) {
            "앱 데이터 root identity가 signer root 준비 중 변경되었습니다."
        }
        if (
            requireDirectoryShape(
                parent,
                "앱 데이터 상위 root",
            ).fileKey() != parentState.fileKey()
        ) {
            throw IllegalStateException(
                "앱 데이터 상위 root identity가 준비 중 변경되었습니다.",
            )
        }
        return PreparedRoots(
            freshKeyRoot = freshKeyRoot,
            guard = RootsGuard(
                data = secureDirectoryState(dataRoot, "앱 데이터 root"),
                work = secureDirectoryState(workRoot, "작업 root"),
                key = secureDirectoryState(keyRoot, "signer key root"),
            ),
        )
    }

    private fun requireLayout() {
        require(dataRoot.isAbsolute && workRoot.isAbsolute && keyRoot.isAbsolute) {
            "데스크톱 signer 경로는 절대 경로여야 합니다."
        }
        require(
            workRoot.parent == dataRoot &&
                workRoot.fileName.toString() == "work",
        ) {
            "작업 root는 앱 데이터 root의 work 직접 자식이어야 합니다."
        }
        require(
            keyRoot.parent == dataRoot &&
                keyRoot.fileName.toString() == "draft-signing" &&
                keyRoot != workRoot,
        ) {
            "signer key root는 앱 데이터 root의 draft-signing 직접 자식이어야 합니다."
        }
    }

    private fun prepareDirectory(
        path: Path,
        expectedOwner: String,
        label: String,
    ): Boolean {
        var created = false
        if (!existsNoFollow(path)) {
            try {
                when (hostOs) {
                    DesktopHostOs.MAC -> Files.createDirectory(
                        path,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS),
                    )
                    DesktopHostOs.WINDOWS -> {
                        Files.createDirectory(path)
                        windowsAclAccess.applyOwnerOnly(path)
                    }
                }
                created = true
            } catch (_: FileAlreadyExistsException) {
                // 경쟁 생성은 아래 기존 항목 검증을 그대로 통과해야 한다.
            }
        }
        secureDirectoryState(path, label)
        require(ownerToken(path) == expectedOwner) {
            "$label owner가 상위 trusted root owner와 일치하지 않습니다."
        }
        return created
    }

    private fun withFileLock(
        roots: PreparedRoots,
        block: () -> KeyPair,
    ): KeyPair {
        validateRoots(roots.guard)
        if (roots.freshKeyRoot) {
            writeNewFile(lockPath, byteArrayOf(), roots.guard)
            forceKeyDirectory(roots.guard)
        } else {
            if (!existsNoFollow(lockPath)) {
                throw IllegalStateException(
                    "기존 데스크톱 전달 signer 저장소에 lock 파일이 없습니다.",
                )
            }
            requireSafeFile(lockPath, roots.guard)
        }
        val before = stableFileState(lockPath, roots.guard)
        FileChannel.open(lockPath, WRITE, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == before.size) {
                "데스크톱 signer lock 크기가 open 중 변경되었습니다."
            }
            require(stableFileState(lockPath, roots.guard) == before) {
                "데스크톱 signer lock identity가 open 중 변경되었습니다."
            }
            channel.lock().use {
                require(stableFileState(lockPath, roots.guard) == before) {
                    "데스크톱 signer lock identity가 lock 중 변경되었습니다."
                }
                return block().also {
                    validateRoots(roots.guard)
                    require(stableFileState(lockPath, roots.guard) == before) {
                        "데스크톱 signer lock identity가 사용 중 변경되었습니다."
                    }
                }
            }
        }
    }

    private fun validateContents(guard: RootsGuard) {
        validateRoots(guard)
        val allowed = setOf(
            LOCK_FILE,
            IDENTITY_FILE,
            PRIVATE_FILE,
            PUBLIC_FILE,
        )
        Files.newDirectoryStream(keyRoot).use { entries ->
            val names = entries.map { it.fileName.toString() }.toSet()
            if (!names.all(allowed::contains)) {
                throw IllegalStateException(
                    "데스크톱 전달 signer 저장소에 알 수 없는 기존 항목이 있습니다.",
                )
            }
        }
        validateRoots(guard)
    }

    private fun readPair(guard: RootsGuard): KeyPair {
        if (!readStable(identityPath, guard).contentEquals(IDENTITY_CONTENT)) {
            throw IllegalStateException(
                "데스크톱 전달 signer identity metadata가 일치하지 않습니다.",
            )
        }
        val pair = try {
            val factory = KeyFactory.getInstance(ALGORITHM)
            KeyPair(
                factory.generatePublic(
                    X509EncodedKeySpec(readStable(publicPath, guard)),
                ),
                factory.generatePrivate(
                    PKCS8EncodedKeySpec(readStable(privatePath, guard)),
                ),
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "데스크톱 전달 signer key 파일이 손상되었습니다.",
                error,
            )
        }
        validatePair(pair)
        return pair
    }

    private fun publish(pair: KeyPair, guard: RootsGuard) {
        validatePair(pair)
        if (listOf(identityPath, privatePath, publicPath).any(::existsNoFollow)) {
            throw IllegalStateException(
                "기존 데스크톱 전달 signer 상태를 덮어쓸 수 없습니다.",
            )
        }
        val suffix = ".tmp-${UUID.randomUUID()}"
        val privateTemp = keyRoot.resolve("$PRIVATE_FILE$suffix")
        val publicTemp = keyRoot.resolve("$PUBLIC_FILE$suffix")
        val identityTemp = keyRoot.resolve("$IDENTITY_FILE$suffix")
        val temps = linkedMapOf<Path, Any>()
        val published = mutableListOf<PublishedFile>()
        var complete = false
        try {
            temps[privateTemp] = writeNewFile(privateTemp, pair.private.encoded, guard)
            temps[publicTemp] = writeNewFile(publicTemp, pair.public.encoded, guard)
            temps[identityTemp] = writeNewFile(identityTemp, IDENTITY_CONTENT, guard)
            atomicPublish(privateTemp, privatePath, published, guard)
            atomicPublish(publicTemp, publicPath, published, guard)
            forceKeyDirectory(guard)
            atomicPublish(identityTemp, identityPath, published, guard)
            forceKeyDirectory(guard)
            complete = true
        } finally {
            temps.forEach { (path, fileKey) ->
                deleteIfIdentity(path, fileKey, guard)
            }
            if (!complete) {
                published.asReversed().forEach { file ->
                    deleteIfIdentity(file.path, file.fileKey, guard)
                }
                forceKeyDirectory(guard)
            }
        }
    }

    private fun writeNewFile(
        path: Path,
        bytes: ByteArray,
        guard: RootsGuard,
    ): Any {
        validateRoots(guard)
        val channel = when (hostOs) {
            DesktopHostOs.MAC -> FileChannel.open(
                path,
                setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS),
            )
            DesktopHostOs.WINDOWS -> FileChannel.open(
                path,
                CREATE_NEW,
                WRITE,
                NOFOLLOW_LINKS,
            )
        }
        channel.use {
            val created = requireRegularShape(path, "새 signer 파일")
            when (hostOs) {
                DesktopHostOs.MAC -> requireOwnerOnly(path, directory = false)
                DesktopHostOs.WINDOWS -> windowsAclAccess.applyOwnerOnly(path)
            }
            if (
                requireRegularShape(
                    path,
                    "새 signer 파일",
                ).fileKey() != created.fileKey()
            ) {
                throw IllegalStateException(
                    "새 signer 파일 identity가 권한 적용 중 변경되었습니다.",
                )
            }
            requireSafeFile(path, guard)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) {
                    throw IllegalStateException(
                        "데스크톱 전달 signer 파일을 완전히 쓰지 못했습니다.",
                    )
                }
            }
            channel.force(true)
            require(channel.size() == bytes.size.toLong()) {
                "데스크톱 전달 signer 파일 크기가 기록 결과와 다릅니다."
            }
        }
        return stableFileState(path, guard).fileKey
    }

    private fun atomicPublish(
        source: Path,
        target: Path,
        published: MutableList<PublishedFile>,
        guard: RootsGuard,
    ) {
        validateRoots(guard)
        if (existsNoFollow(target)) {
            throw IllegalStateException(
                "기존 데스크톱 전달 signer 파일을 덮어쓸 수 없습니다.",
            )
        }
        val sourceState = stableFileState(source, guard)
        try {
            windowsAtomicMove.move(source, target)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "데스크톱 전달 signer 저장소가 atomic move를 지원하지 않습니다.",
                error,
            )
        } finally {
            if (basicFileKeyOrNull(target) == sourceState.fileKey) {
                published += PublishedFile(target, sourceState.fileKey)
            }
        }
        if (existsNoFollow(source)) {
            throw IllegalStateException(
                "데스크톱 전달 signer 임시 파일이 atomic move 뒤 남았습니다.",
            )
        }
        requireSafeFile(target, guard)
        if (stableFileState(target, guard).fileKey != sourceState.fileKey) {
            throw IllegalStateException(
                "데스크톱 전달 signer 파일 identity가 게시 중 변경되었습니다.",
            )
        }
    }

    private fun readStable(path: Path, guard: RootsGuard): ByteArray {
        val before = stableFileState(path, guard)
        if (before.size > MAX_KEY_FILE_SIZE) {
            throw IllegalStateException("데스크톱 전달 signer 파일이 비정상적으로 큽니다.")
        }
        val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != before.size) {
                throw IllegalStateException(
                    "데스크톱 전달 signer 파일 크기가 open 중 변경되었습니다.",
                )
            }
            val result = ByteArray(before.size.toInt())
            val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw IllegalStateException(
                        "데스크톱 전달 signer 파일을 완전히 읽지 못했습니다.",
                    )
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw IllegalStateException(
                    "데스크톱 전달 signer 파일이 읽는 동안 커졌습니다.",
                )
            }
            result
        }
        if (stableFileState(path, guard) != before) {
            throw IllegalStateException(
                "데스크톱 전달 signer 파일이 읽는 동안 변경되었습니다.",
            )
        }
        return bytes
    }

    private fun validatePair(pair: KeyPair) {
        val challenge = "sixbridge-draft-key-pair-proof".toByteArray(Charsets.UTF_8)
        val signature = try {
            Signature.getInstance(ALGORITHM).run {
                initSign(pair.private)
                update(challenge)
                sign()
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "데스크톱 전달 signer 개인 키가 Ed25519 형식이 아닙니다.",
                error,
            )
        }
        val matches = runCatching {
            Signature.getInstance(ALGORITHM).run {
                initVerify(pair.public)
                update(challenge)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!matches) {
            throw IllegalStateException(
                "데스크톱 전달 signer 공개 키와 개인 키가 서로 대응하지 않습니다.",
            )
        }
    }

    private fun validateRoots(guard: RootsGuard) {
        if (
            secureDirectoryState(dataRoot, "앱 데이터 root") != guard.data ||
            secureDirectoryState(workRoot, "작업 root") != guard.work ||
            secureDirectoryState(keyRoot, "signer key root") != guard.key
        ) {
            throw IllegalStateException(
                "데스크톱 전달 signer root identity가 사용 중 변경되었습니다.",
            )
        }
    }

    private fun secureDirectoryState(path: Path, label: String): DirectoryState {
        val attributes = requireDirectoryShape(path, label)
        requireOwnerOnly(path, directory = true)
        return DirectoryState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "$label identity를 확인할 수 없습니다."
            },
            owner = ownerToken(path),
        )
    }

    private fun stableFileState(path: Path, guard: RootsGuard): FileState {
        requireSafeFile(path, guard)
        val attributes = requireRegularShape(path, "signer 파일")
        return FileState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "데스크톱 전달 signer 파일 identity를 확인할 수 없습니다."
            },
            size = attributes.size(),
            modified = attributes.lastModifiedTime().toMillis(),
            owner = ownerToken(path),
        )
    }

    private fun requireSafeFile(path: Path, guard: RootsGuard) {
        validateRoots(guard)
        requireRegularShape(path, "signer 파일")
        requireOwnerOnly(path, directory = false)
        if (ownerToken(path) != guard.key.owner) {
            throw IllegalStateException(
                "데스크톱 전달 signer 파일 owner가 key root owner와 다릅니다.",
            )
        }
        if (hostOs == DesktopHostOs.MAC) {
            val links = (
                Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number
                ).toLong()
            if (links != 1L) {
                throw IllegalStateException(
                    "데스크톱 전달 signer 파일 hardlink는 허용되지 않습니다.",
                )
            }
        }
        validateRoots(guard)
    }

    private fun requireOwnerOnly(path: Path, directory: Boolean) {
        when (hostOs) {
            DesktopHostOs.MAC -> {
                val expected = if (directory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS
                val actual = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                if (actual != expected) {
                    throw IllegalStateException(
                        "기존 데스크톱 signer 경로 권한이 owner-only가 아닙니다: $path",
                    )
                }
            }
            DesktopHostOs.WINDOWS -> windowsAclAccess.requireOwnerOnly(path)
        }
    }

    private fun requireDirectoryShape(
        path: Path,
        label: String,
    ): BasicFileAttributes {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (
            !attributes.isDirectory ||
            attributes.isSymbolicLink ||
            attributes.isOther ||
            attributes.fileKey() == null
        ) {
            throw IllegalStateException("$label 경로는 실제 디렉터리여야 합니다: $path")
        }
        return attributes
    }

    private fun requireRegularShape(
        path: Path,
        label: String,
    ): BasicFileAttributes {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (
            !attributes.isRegularFile ||
            attributes.isSymbolicLink ||
            attributes.isOther ||
            attributes.fileKey() == null
        ) {
            throw IllegalStateException("$label 경로는 실제 일반 파일이어야 합니다: $path")
        }
        return attributes
    }

    private fun forceKeyDirectory(guard: RootsGuard) {
        validateRoots(guard)
        if (hostOs == DesktopHostOs.MAC) {
            FileChannel.open(keyRoot, READ, NOFOLLOW_LINKS).use { it.force(true) }
        }
        validateRoots(guard)
    }

    private fun deleteIfIdentity(path: Path, fileKey: Any, guard: RootsGuard) {
        if (!existsNoFollow(path)) return
        if (basicFileKeyOrNull(path) != fileKey) {
            throw IllegalStateException(
                "데스크톱 전달 signer 정리 대상 identity가 변경되었습니다.",
            )
        }
        requireSafeFile(path, guard)
        Files.delete(path)
        validateRoots(guard)
    }

    private fun basicFileKeyOrNull(path: Path): Any? = runCatching {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        ).fileKey()
    }.getOrNull()

    private fun ownerToken(path: Path): String = runCatching {
        "uid:${(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()}"
    }.getOrElse {
        "principal:${Files.getOwner(path, NOFOLLOW_LINKS).name}"
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)

    private val lockPath: Path
        get() = keyRoot.resolve(LOCK_FILE)
    private val identityPath: Path
        get() = keyRoot.resolve(IDENTITY_FILE)
    private val privatePath: Path
        get() = keyRoot.resolve(PRIVATE_FILE)
    private val publicPath: Path
        get() = keyRoot.resolve(PUBLIC_FILE)

    private data class PreparedRoots(
        val freshKeyRoot: Boolean,
        val guard: RootsGuard,
    )

    private data class RootsGuard(
        val data: DirectoryState,
        val work: DirectoryState,
        val key: DirectoryState,
    )

    private data class DirectoryState(
        val fileKey: Any,
        val owner: String,
    )

    private data class FileState(
        val fileKey: Any,
        val size: Long,
        val modified: Long,
        val owner: String,
    )

    private data class PublishedFile(
        val path: Path,
        val fileKey: Any,
    )

    companion object {
        private val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val DIRECTORY_PERMISSIONS =
            FILE_PERMISSIONS + PosixFilePermission.OWNER_EXECUTE
        private val IDENTITY_CONTENT =
            "sixbridge-draft-transfer-ed25519-v1".toByteArray(Charsets.UTF_8)
        private const val ALGORITHM = "Ed25519"
        private const val LOCK_FILE = ".sixbridge-draft-key.lock"
        private const val IDENTITY_FILE = "draft-transfer.identity"
        private const val PRIVATE_FILE = "draft-transfer.pk8"
        private const val PUBLIC_FILE = "draft-transfer.pub"
        private const val MAX_KEY_FILE_SIZE = 1024 * 1024L

        internal fun testingWindows(
            paths: DesktopApplicationPaths,
            aclAccess: WindowsAclAccess,
            atomicMove: WindowsAtomicMove,
        ): DesktopDraftSigningIdentityStore = DesktopDraftSigningIdentityStore(
            paths = paths,
            hostOs = DesktopHostOs.WINDOWS,
            windowsAclAccess = aclAccess,
            windowsAtomicMove = atomicMove,
        )
    }
}
