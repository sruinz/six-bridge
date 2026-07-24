package dev.sixbridge.core.input

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

class SourceProjectIdentityStore(
    private val registryRoot: Path,
) {
    fun resolve(resolved: ResolvedExtensionInput, source: Path): String =
        withRegistryLock {
            when (resolved.kind) {
                ExtensionInputKind.SOURCE_DIRECTORY ->
                    resolveDirectory(source, resolved.projectIdentity)
                ExtensionInputKind.ZIP ->
                    resolveZip(source, resolved.projectIdentity)
                else -> throw IllegalArgumentException(
                    "source project identity를 사용할 수 없는 입력입니다: ${resolved.kind}",
                )
            }
        }

    fun claimLegacy(legacyIdentity: String, projectIdentity: String): Boolean =
        withRegistryLock {
            require(PROJECT_ID.matches(projectIdentity)) {
                "올바르지 않은 source project identity입니다."
            }
            val path = registryRoot.resolve("legacy").resolve("$legacyIdentity.txt")
            val claimed = readProjectId(path)
            when {
                claimed == null -> {
                    writeSecure(path, projectIdentity)
                    true
                }
                claimed == projectIdentity -> true
                else -> false
            }
        }

    private fun resolveDirectory(source: Path, requested: String?): String {
        val marker = source.resolve(MARKER_FILE)
        val existing = if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            readMarker(marker, requireOwnerOnly = true)
        } else {
            null
        }
        val requestedId = requested?.let(::validateProjectId)
        require(existing == null || requestedId == null || existing == requestedId) {
            "명시한 source project identity가 marker와 일치하지 않습니다."
        }
        val identity = existing ?: requestedId ?: newProjectId()
        if (existing == null) writeMarker(marker, identity)

        val canonical = source.toRealPath().toString()
        val binding = registryRoot.resolve("paths").resolve("$identity.txt")
        val previous = readText(binding)
        if (previous != null && previous != canonical) {
            val previousPath = runCatching { Path.of(previous) }.getOrNull()
            require(previousPath == null || !Files.exists(previousPath)) {
                "source project identity가 다른 기존 source에서 이미 사용 중입니다: $identity"
            }
        }
        if (previous != canonical) writeSecure(binding, canonical)
        return identity
    }

    private fun resolveZip(source: Path, requested: String?): String {
        val marker = source.resolve(MARKER_FILE)
        val embedded = if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            readMarker(marker, requireOwnerOnly = false)
        } else {
            null
        }
        val requestedId = requested?.let(::validateProjectId)
        require(embedded == null || requestedId == null || embedded == requestedId) {
            "명시한 source project identity가 ZIP marker와 일치하지 않습니다."
        }
        val fingerprint = contentFingerprint(source)
        val binding = registryRoot.resolve("fingerprints").resolve("$fingerprint.txt")
        val registered = readProjectId(binding)
        val identity = embedded ?: requestedId ?: registered ?: newProjectId()
        require(registered == null || registered == identity) {
            "ZIP content fingerprint에 다른 source project identity가 등록되어 있습니다."
        }
        if (registered == null) writeSecure(binding, identity)
        return identity
    }

    private fun readMarker(path: Path, requireOwnerOnly: Boolean): String {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "source project identity marker는 일반 파일이어야 합니다."
        }
        if (requireOwnerOnly) verifyMarkerPermissions(path)
        val value = Files.readString(path).trim()
        val match = MARKER_FORMAT.matchEntire(value)
            ?: throw IllegalArgumentException(
                "source project identity marker 형식이 올바르지 않습니다.",
            )
        return match.groupValues[1]
    }

    private fun writeMarker(path: Path, identity: String) {
        writeSecure(path, "$MARKER_PREFIX$identity")
        verifyOwnerOnly(path)
    }

    private fun contentFingerprint(source: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val paths = Files.walk(source).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { it.fileName.toString() != MARKER_FILE }
                .filter { path ->
                    source.relativize(path).none { it.toString() in EXCLUDED_DIRECTORIES }
                }
                .sorted(compareBy { source.relativize(it).invariantSeparatorsPathString })
                .toList()
        }
        paths.forEach { path ->
            val relative = source.relativize(path).invariantSeparatorsPathString.toByteArray()
            val contents = Files.readAllBytes(path)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(relative.size).array())
            digest.update(relative)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(contents.size.toLong()).array())
            digest.update(contents)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateProjectId(value: String): String {
        require(PROJECT_ID.matches(value)) {
            "source project identity는 소문자 16진수 32자리여야 합니다."
        }
        return value
    }

    private fun readProjectId(path: Path): String? =
        readText(path)?.let(::validateProjectId)

    private fun readText(path: Path): String? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "source identity registry 항목은 일반 파일이어야 합니다: $path"
        }
        verifyOwnerOnly(path)
        return Files.readString(path).trim()
    }

    private fun writeSecure(path: Path, value: String) {
        Files.createDirectories(path.parent)
        require(Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $path"
        }
        val temporary = Files.createTempFile(
            path.parent,
            ".${path.fileName}.",
            ".tmp",
            PosixFilePermissions.asFileAttribute(OWNER_ONLY),
        )
        try {
            Files.writeString(temporary, value, StandardOpenOption.TRUNCATE_EXISTING)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Files.setPosixFilePermissions(path, OWNER_ONLY)
            verifyOwnerOnly(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyOwnerOnly(path: Path) {
        require(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            "owner-only 권한을 확인할 수 없는 파일 시스템입니다: $path"
        }
        require(Files.getPosixFilePermissions(path) == OWNER_ONLY) {
            "source identity 파일 권한은 0600이어야 합니다: $path"
        }
    }

    private fun verifyMarkerPermissions(path: Path) {
        require(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            "source project identity marker 권한을 확인할 수 없는 파일 시스템입니다: $path"
        }
        val permissions = Files.getPosixFilePermissions(path)
        require(PosixFilePermission.OWNER_READ in permissions &&
            PosixFilePermission.OWNER_WRITE in permissions &&
            PosixFilePermission.GROUP_WRITE !in permissions &&
            PosixFilePermission.OTHERS_WRITE !in permissions
        ) {
            "source project identity marker는 owner만 수정할 수 있어야 합니다: $path"
        }
    }

    private fun <T> withRegistryLock(block: () -> T): T {
        Files.createDirectories(registryRoot)
        val lockPath = registryRoot.resolve("registry.lock")
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(
                lockPath,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY),
            )
        }
        Files.setPosixFilePermissions(lockPath, OWNER_ONLY)
        FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { return block() }
        }
    }

    private fun newProjectId(): String =
        UUID.randomUUID().toString().replace("-", "")

    companion object {
        const val MARKER_FILE = ".sixbridge-project-id"
        private const val MARKER_PREFIX = "sixbridge-project-v1:"
        private val MARKER_FORMAT = Regex("$MARKER_PREFIX([0-9a-f]{32})")
        private val PROJECT_ID = Regex("[0-9a-f]{32}")
        private val OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
    }
}
