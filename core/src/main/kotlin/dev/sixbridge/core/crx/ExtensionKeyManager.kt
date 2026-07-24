package dev.sixbridge.core.crx

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmSynthetic

class ExtensionKeyManager private constructor(
    keyRoot: Path,
    trustedRoot: Path,
) {
    constructor(keyRoot: Path) : this(
        keyRoot,
        requireNotNull(keyRoot.toAbsolutePath().normalize().parent),
    )

    private val keyRoot = keyRoot.toAbsolutePath().normalize()
    private val trustedRoot = trustedRoot.toAbsolutePath().normalize()
    private val activeGuard = ThreadLocal<RootGuard?>()

    fun getOrCreateStoreConversionKey(
        originalExtensionId: String,
        profileMajorVersion: Int,
    ): KeyPair = getOrCreate(
        storeConversionIdentity(originalExtensionId, profileMajorVersion),
    )

    fun getOrCreate(identity: String): KeyPair {
        validateIdentity(identity)
        return withRegistryLock {
            loadCanonical(identity)?.let { return@withRegistryLock it }
            loadRecognizedLegacy(identity)?.let { legacy ->
                publish(identity, legacy)
                return@withRegistryLock legacy
            }
            val generated = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            publish(identity, generated)
            generated
        }
    }

    fun getExisting(identity: String): KeyPair? {
        validateIdentity(identity)
        return withRegistryLock {
            loadCanonical(identity)
                ?: loadRecognizedLegacy(identity)?.also { publish(identity, it) }
        }
    }

    fun findByExtensionId(extensionId: String): KeyPair? {
        require(EXTENSION_ID.matches(extensionId)) {
            "CRX extension ID 형식이 올바르지 않습니다."
        }
        return withRegistryLock {
            loadCanonical(extensionId)?.takeIf {
                Crx3.extensionId(it.public.encoded) == extensionId
            }?.let { return@withRegistryLock it }
            loadRecognizedLegacy(extensionId)?.takeIf {
                Crx3.extensionId(it.public.encoded) == extensionId
            }?.also {
                publish(extensionId, it)
            }?.let { return@withRegistryLock it }

            val guard = requireNotNull(activeGuard.get())
            verifyRootGuard(guard)
            for (identityPath in guard.handle) {
                if (!identityPath.fileName.toString().endsWith(".identity")) continue
                requireSafeRegular(identityPath)
                val identity = readStable(identityPath).toString(Charsets.UTF_8)
                val expectedName = "${canonicalBase(identity)}.identity"
                require(identityPath.fileName.toString() == expectedName) {
                    "CRX key identity metadata 이름과 내용이 일치하지 않습니다."
                }
                val pair = loadCanonical(identity)
                    ?: throw IllegalStateException(
                        "CRX key identity metadata에 대응하는 key pair가 없습니다.",
                    )
                if (Crx3.extensionId(pair.public.encoded) == extensionId) {
                    return@withRegistryLock pair
                }
            }
            verifyRootGuard(guard)
            null
        }
    }

    fun persist(identity: String, keyPair: KeyPair) {
        validateIdentity(identity)
        validatePair(keyPair)
        withRegistryLock {
            val existing = loadCanonical(identity)
            if (existing != null) {
                require(
                    existing.public.encoded.contentEquals(keyPair.public.encoded) &&
                        existing.private.encoded.contentEquals(keyPair.private.encoded),
                ) {
                    "동일한 CRX key identity에 다른 key pair를 저장할 수 없습니다."
                }
            } else {
                publish(identity, keyPair)
            }
        }
    }

    private fun loadCanonical(identity: String): KeyPair? {
        val files = files(identity)
        val identityExists = existsNoFollow(files.identity)
        val privateExists = existsNoFollow(files.privateKey)
        val publicExists = existsNoFollow(files.publicKey)

        if (!identityExists) {
            if (privateExists || publicExists) {
                recoverUncommittedPair(files)
            }
            return null
        }
        requireSafeRegular(files.identity)
        require(privateExists && publicExists) {
            "CRX key pair가 불완전합니다: ${files.base}"
        }
        val storedIdentity = readStable(files.identity).toString(Charsets.UTF_8)
        require(storedIdentity == identity) {
            "CRX key identity metadata가 요청한 논리 identity와 다릅니다."
        }
        return readPair(files.privateKey, files.publicKey)
    }

    private fun loadRecognizedLegacy(identity: String): KeyPair? {
        if (!isRecognizedLegacyIdentity(identity)) return null
        val base = legacySafeIdentity(identity)
        val privatePath = keyRoot.resolve("$base.pk8")
        val publicPath = keyRoot.resolve("$base.pub")
        val privateExists = existsNoFollow(privatePath)
        val publicExists = existsNoFollow(publicPath)
        if (!privateExists && !publicExists) return null
        require(privateExists && publicExists) {
            "legacy CRX key pair가 불완전합니다: $base"
        }
        return readPair(privatePath, publicPath)
    }

    private fun publish(identity: String, keyPair: KeyPair) {
        validatePair(keyPair)
        val files = files(identity)
        check(!existsNoFollow(files.identity)) {
            "이미 commit된 CRX key identity를 덮어쓸 수 없습니다."
        }
        check(!existsNoFollow(files.privateKey) && !existsNoFollow(files.publicKey)) {
            "미완료 CRX key pair를 정리하지 못했습니다."
        }
        val suffix = ".tmp-${UUID.randomUUID()}"
        val privateTemp = keyRoot.resolve("${files.base}.pk8$suffix")
        val publicTemp = keyRoot.resolve("${files.base}.pub$suffix")
        val identityTemp = keyRoot.resolve("${files.base}.identity$suffix")
        val temps = listOf(privateTemp, publicTemp, identityTemp)
        try {
            writeOwnerOnly(privateTemp, keyPair.private.encoded)
            writeOwnerOnly(publicTemp, keyPair.public.encoded)
            writeOwnerOnly(identityTemp, identity.toByteArray(Charsets.UTF_8))
            atomicPublish(privateTemp, files.privateKey)
            atomicPublish(publicTemp, files.publicKey)
            forceDirectory()
            ExtensionKeyManagerFaultInjection.afterPairPublish()
            atomicPublish(identityTemp, files.identity)
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

    private fun recoverUncommittedPair(files: KeyFiles) {
        listOf(files.privateKey, files.publicKey).forEach { path ->
            if (existsNoFollow(path)) {
                requireSafeRegular(path)
                deleteAnchored(path)
            }
        }
        forceDirectory()
    }

    private fun readPair(privatePath: Path, publicPath: Path): KeyPair {
        requireSafeRegular(privatePath)
        requireSafeRegular(publicPath)
        val factory = KeyFactory.getInstance("RSA")
        val pair = KeyPair(
            factory.generatePublic(X509EncodedKeySpec(readStable(publicPath))),
            factory.generatePrivate(PKCS8EncodedKeySpec(readStable(privatePath))),
        )
        validatePair(pair)
        return pair
    }

    private fun validatePair(pair: KeyPair) {
        val challenge = "sixbridge-key-pair-proof".toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(challenge)
            sign()
        }
        require(
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pair.public)
                update(challenge)
                verify(signature)
            },
        ) {
            "CRX 공개 키와 개인 키가 서로 대응하지 않습니다."
        }
    }

    private fun <T> withRegistryLock(block: () -> T): T {
        ensureRoot()
        val processLock = PROCESS_LOCKS.computeIfAbsent(keyRoot) {
            ReentrantLock()
        }
        return processLock.withLock {
            validateRoot()
            secureRoot().use { rootHandle ->
                val rootKey = requireNotNull(readAttributes(rootHandle).fileKey())
                val guard = RootGuard(rootHandle, rootKey)
                activeGuard.set(guard)
                try {
                    verifyRootGuard(guard)
                    val lockName = Path.of(REGISTRY_LOCK)
                    if (readAttributesOrNull(rootHandle, lockName) == null) {
                        try {
                            rootHandle.newByteChannel(
                                lockName,
                                setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                            ).use { }
                            setOwnerOnly(rootHandle, lockName)
                        } catch (_: FileAlreadyExistsException) {
                            // 다른 프로세스가 먼저 생성했으므로 아래에서 다시 검증한다.
                        }
                    }
                    requireSafeRegular(keyRoot.resolve(REGISTRY_LOCK))
                    val channel = rootHandle.newByteChannel(
                        lockName,
                        setOf(WRITE, NOFOLLOW_LINKS),
                    ) as? FileChannel ?: throw IllegalStateException(
                        "CRX key registry lock을 FileChannel로 열 수 없습니다.",
                    )
                    channel.use {
                        it.lock().use {
                            ExtensionKeyManagerFaultInjection.afterRootLock()
                            verifyRootGuard(guard)
                            block().also { verifyRootGuard(guard) }
                        }
                    }
                } finally {
                    activeGuard.remove()
                }
            }
        }
    }

    private fun ensureRoot() {
        require(keyRoot.startsWith(trustedRoot) && keyRoot != trustedRoot) {
            "CRX key root는 trusted root 내부여야 합니다."
        }
        require(
            Files.isDirectory(trustedRoot, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(trustedRoot),
        ) {
            "trusted local root는 symlink가 아닌 디렉터리여야 합니다."
        }
        var current = trustedRoot
        trustedRoot.relativize(keyRoot).forEach { component ->
            current = current.resolve(component.toString())
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(
                        current,
                        PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS),
                    )
                } catch (_: FileAlreadyExistsException) {
                    // 경쟁 생성은 다음 검사에서 확인한다.
                }
            }
            require(
                Files.isDirectory(current, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(current),
            ) {
                "CRX key root ancestor는 symlink가 아닌 디렉터리여야 합니다: $current"
            }
        }
        validateRoot()
    }

    private fun validateRoot() {
        require(!Files.isSymbolicLink(keyRoot) && Files.isDirectory(keyRoot, NOFOLLOW_LINKS)) {
            "CRX key root는 symlink가 아닌 디렉터리여야 합니다: $keyRoot"
        }
        require(Files.getFileStore(keyRoot).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $keyRoot"
        }
        Files.setPosixFilePermissions(keyRoot, DIRECTORY_PERMISSIONS)
        require(Files.getPosixFilePermissions(keyRoot, NOFOLLOW_LINKS) == DIRECTORY_PERMISSIONS) {
            "CRX key root의 owner-only 권한을 확인할 수 없습니다."
        }
    }

    private fun requireSafeRegular(path: Path) {
        activeGuard.get()?.let(::verifyRootGuard)
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            "CRX key 파일은 symlink가 아닌 일반 파일이어야 합니다: $path"
        }
        require(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == FILE_PERMISSIONS) {
            "CRX key 파일은 owner-only 권한이어야 합니다: $path"
        }
        val links = ExtensionKeyManagerFaultInjection.unixLinkCount(path) {
            (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
        }
        require(links == 1L) {
            "CRX key 파일의 hard link는 허용되지 않습니다: $path"
        }
        activeGuard.get()?.let(::verifyRootGuard)
    }

    private fun readStable(path: Path): ByteArray {
        requireSafeRegular(path)
        val before = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val guard = requireNotNull(activeGuard.get())
        val bytes = guard.handle.newByteChannel(
            relativeName(path),
            setOf(READ, NOFOLLOW_LINKS),
        ).use { channel ->
            require(channel.size() <= MAX_KEY_FILE_BYTES) {
                "CRX key 파일이 비정상적으로 큽니다: $path"
            }
            val result = ByteArray(channel.size().toInt())
            val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
            require(!buffer.hasRemaining()) {
                "CRX key 파일을 완전히 읽지 못했습니다: $path"
            }
            result
        }
        val after = Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(
            before.fileKey() == after.fileKey() &&
                before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime(),
        ) {
            "CRX key 파일이 읽는 동안 변경되었습니다: $path"
        }
        return bytes
    }

    private fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        val guard = requireNotNull(activeGuard.get())
        verifyRootGuard(guard)
        guard.handle.newByteChannel(
            relativeName(path),
            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("CRX key 파일을 fsync할 수 없습니다.")
        }
        setOwnerOnly(guard.handle, relativeName(path))
        requireSafeRegular(path)
    }

    private fun atomicPublish(source: Path, target: Path) {
        val guard = requireNotNull(activeGuard.get())
        verifyRootGuard(guard)
        guard.handle.move(
            relativeName(source),
            guard.handle,
            relativeName(target),
        )
        verifyRootGuard(guard)
        requireSafeRegular(target)
    }

    private fun forceDirectory() {
        val guard = requireNotNull(activeGuard.get())
        verifyRootGuard(guard)
        ExtensionKeyManagerFaultInjection.beforeDirectoryFsync()
        FileChannel.open(keyRoot, READ, NOFOLLOW_LINKS).use { it.force(true) }
        verifyRootGuard(guard)
    }

    private fun files(identity: String): KeyFiles {
        val base = canonicalBase(identity)
        return KeyFiles(
            base = base,
            identity = keyRoot.resolve("$base.identity"),
            privateKey = keyRoot.resolve("$base.pk8"),
            publicKey = keyRoot.resolve("$base.pub"),
        )
    }

    private fun canonicalBase(identity: String): String =
        "id-${sha256(identity.toByteArray(Charsets.UTF_8))}"

    private fun legacySafeIdentity(identity: String): String = identity
        .lowercase()
        .replace(Regex("[^a-z0-9._-]"), "-")
        .trim('-')
        .ifEmpty { "extension" }

    private fun isRecognizedLegacyIdentity(identity: String): Boolean =
        EXTENSION_ID.matches(identity) ||
            PROJECT_IDENTITY.matches(identity) ||
            MANIFEST_IDENTITY.matches(identity) ||
            SOURCE_IDENTITY.matches(identity) ||
            STORE_IDENTITY.matches(identity)

    private fun validateIdentity(identity: String) {
        require(identity.isNotBlank() && identity.length <= MAX_IDENTITY_LENGTH) {
            "CRX key identity가 비어 있거나 너무 깁니다."
        }
        require(!identity.any(Char::isISOControl)) {
            "CRX key identity에 제어 문자를 사용할 수 없습니다."
        }
    }

    private fun existsNoFollow(path: Path): Boolean {
        activeGuard.get()?.let(::verifyRootGuard)
        val exists = Files.exists(path, NOFOLLOW_LINKS)
        activeGuard.get()?.let(::verifyRootGuard)
        return exists
    }

    private fun secureRoot(): SecureDirectoryStream<Path> {
        val stream = Files.newDirectoryStream(keyRoot)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            throw IllegalStateException(
                "CRX key 저장소가 secure directory stream을 지원하지 않습니다.",
            )
        }
        return stream
    }

    private fun verifyRootGuard(guard: RootGuard) {
        val pathAttributes = Files.readAttributes(
            keyRoot,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val handleAttributes = readAttributes(guard.handle)
        require(
            pathAttributes.isDirectory &&
                !pathAttributes.isSymbolicLink &&
                pathAttributes.fileKey() == guard.fileKey &&
                handleAttributes.fileKey() == guard.fileKey,
        ) {
            "CRX key root identity가 lock 생명주기 중 변경되었습니다."
        }
    }

    private fun readAttributes(
        handle: SecureDirectoryStream<Path>,
    ): BasicFileAttributes = requireNotNull(
        handle.getFileAttributeView(BasicFileAttributeView::class.java),
    ).readAttributes()

    private fun readAttributesOrNull(
        handle: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? = try {
        requireNotNull(
            handle.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        ).readAttributes()
    } catch (_: NoSuchFileException) {
        null
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
        require(view.readAttributes().permissions() == FILE_PERMISSIONS) {
            "CRX key 파일 owner-only 권한을 확인할 수 없습니다."
        }
    }

    private fun deleteAnchored(path: Path) {
        val guard = requireNotNull(activeGuard.get())
        verifyRootGuard(guard)
        guard.handle.deleteFile(relativeName(path))
        verifyRootGuard(guard)
    }

    private fun relativeName(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == keyRoot) {
            "CRX key 파일은 key root의 직접 자식이어야 합니다."
        }
        return Path.of(normalized.fileName.toString())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class KeyFiles(
        val base: String,
        val identity: Path,
        val privateKey: Path,
        val publicKey: Path,
    )

    private data class RootGuard(
        val handle: SecureDirectoryStream<Path>,
        val fileKey: Any,
    )

    companion object {
        @JvmSynthetic
        internal fun create(
            trustedRoot: Path,
            keyRoot: Path,
        ): ExtensionKeyManager = ExtensionKeyManager(keyRoot, trustedRoot)

        fun storeConversionIdentity(
            originalExtensionId: String,
            profileMajorVersion: Int,
        ): String {
            require(EXTENSION_ID.matches(originalExtensionId)) {
                "원본 CRX extension ID 형식이 올바르지 않습니다."
            }
            require(profileMajorVersion > 0) {
                "브라우저 profile 주 버전은 양수여야 합니다."
            }
            return "store:$originalExtensionId:profile-$profileMajorVersion"
        }

        private val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val DIRECTORY_PERMISSIONS = FILE_PERMISSIONS +
            PosixFilePermission.OWNER_EXECUTE
        private val EXTENSION_ID = Regex("[a-p]{32}")
        private val PROJECT_IDENTITY = Regex("project-[0-9a-f]{32}")
        private val MANIFEST_IDENTITY = Regex("manifest-[0-9a-f]{32}")
        private val SOURCE_IDENTITY = Regex("source-[0-9a-f]{24}")
        private val STORE_IDENTITY =
            Regex("store:[a-p]{32}:profile-[1-9][0-9]*")
        private const val REGISTRY_LOCK = ".sixbridge-key-registry.lock"
        private const val MAX_IDENTITY_LENGTH = 512
        private const val MAX_KEY_FILE_BYTES = 1024 * 1024L
    }
}

internal object ExtensionKeyManagerFaultInjection {
    private val afterPairPublish = ThreadLocal<(() -> Unit)?>()
    private val afterRootLock = ThreadLocal<(() -> Unit)?>()
    private val directoryFsync = ThreadLocal<(() -> Unit)?>()
    private val failNlink = ThreadLocal<Boolean?>()

    @JvmSynthetic
    fun <T> failAfterPairPublish(block: () -> T): T {
        check(afterPairPublish.get() == null)
        afterPairPublish.set {
            throw IllegalStateException("injected failure after key pair publish")
        }
        return try {
            block()
        } finally {
            afterPairPublish.remove()
        }
    }

    @JvmSynthetic
    fun afterPairPublish() {
        afterPairPublish.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> replaceRootAfterLock(
        action: () -> Unit,
        block: () -> T,
    ): T {
        check(afterRootLock.get() == null)
        afterRootLock.set(action)
        return try {
            block()
        } finally {
            afterRootLock.remove()
        }
    }

    @JvmSynthetic
    fun afterRootLock() {
        afterRootLock.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> failNextDirectoryFsync(block: () -> T): T {
        check(directoryFsync.get() == null)
        directoryFsync.set {
            directoryFsync.remove()
            throw IllegalStateException("injected directory fsync failure")
        }
        return try {
            block()
        } finally {
            directoryFsync.remove()
        }
    }

    @JvmSynthetic
    fun beforeDirectoryFsync() {
        directoryFsync.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> failUnixLinkCount(block: () -> T): T {
        check(failNlink.get() == null)
        failNlink.set(true)
        return try {
            block()
        } finally {
            failNlink.remove()
        }
    }

    @JvmSynthetic
    fun unixLinkCount(path: Path, read: () -> Long): Long {
        if (failNlink.get() == true) {
            throw IllegalStateException("injected unix:nlink unsupported: $path")
        }
        return read()
    }
}
