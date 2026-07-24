package dev.sixbridge.core.input

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

class SourceProjectIdentityStore(
    private val registryRoot: Path,
) {
    fun resolve(resolved: ResolvedExtensionInput, source: Path): String =
        withRegistryLock {
            require(
                resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY ||
                    resolved.kind == ExtensionInputKind.ZIP,
            ) {
                "source project identity를 사용할 수 없는 입력입니다: ${resolved.kind}"
            }
            resolveSource(resolved, source)
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

    private fun resolveSource(
        resolved: ResolvedExtensionInput,
        source: Path,
    ): String {
        val marker = source.resolve(MARKER_FILE)
        val embedded = if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            readMarker(
                marker,
                requireOwnerOnly = resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY,
            )
        } else {
            null
        }
        val requested = resolved.projectIdentity?.let(::validateProjectId)
        val canonical = canonicalInputPath(resolved)
        val pathBinding = registryRoot.resolve("paths").resolve("${sha256(canonical)}.txt")
        val byPath = readProjectId(pathBinding)
        val fingerprint = contentFingerprint(source)
        val fingerprintBinding =
            registryRoot.resolve("fingerprints").resolve("$fingerprint.txt")
        val byFingerprint = readProjectId(fingerprintBinding)

        val direct = listOfNotNull(embedded, requested, byPath).distinct()
        require(direct.size <= 1) {
            "source project identity marker, 명시 identity, path sidecar가 충돌합니다."
        }
        val directIdentity = direct.singleOrNull()
        if (directIdentity != null && byFingerprint != null) {
            require(directIdentity == byFingerprint) {
                "source fingerprint sidecar가 선택한 identity와 충돌합니다."
            }
        }

        val fingerprintUsable = byFingerprint?.takeIf { identity ->
            resolved.kind == ExtensionInputKind.ZIP ||
                identityPath(identity)?.let { previous ->
                    previous == canonical || !Files.exists(Path.of(previous))
                } != false
        }
        val identity = directIdentity ?: fingerprintUsable ?: run {
            if (resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY &&
                hasMissingRegisteredSource()
            ) {
                throw IllegalArgumentException(
                    "이동과 내용 변경이 함께 감지되었습니다. --identity를 명시하세요.",
                )
            }
            newProjectId()
        }

        if (resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY) {
            claimDirectoryIdentity(identity, canonical)
        }
        if (byPath == null) writeSecure(pathBinding, identity)
        if (byFingerprint == null) {
            writeSecure(fingerprintBinding, identity)
        } else if (byFingerprint != identity) {
            require(
                resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY &&
                    directIdentity == null &&
                    fingerprintUsable == null,
            ) {
                "source fingerprint에 다른 project identity가 등록되어 있습니다."
            }
        }
        return identity
    }

    private fun claimDirectoryIdentity(identity: String, canonical: String) {
        val binding = registryRoot.resolve("identities").resolve("$identity.txt")
        val previous = identityPath(identity)
        if (previous != null && previous != canonical) {
            val previousPath = runCatching { Path.of(previous) }.getOrNull()
            require(previousPath == null || !Files.exists(previousPath)) {
                "source project identity가 다른 기존 source에서 이미 사용 중입니다: $identity"
            }
        }
        if (previous != canonical) writeSecure(binding, canonical)
    }

    private fun identityPath(identity: String): String? =
        readText(registryRoot.resolve("identities").resolve("$identity.txt"))
            ?: readText(registryRoot.resolve("paths").resolve("$identity.txt"))

    private fun hasMissingRegisteredSource(): Boolean {
        val identities = registryRoot.resolve("identities")
        if (!Files.isDirectory(identities, LinkOption.NOFOLLOW_LINKS)) return false
        return Files.list(identities).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map(::readText)
                .filter { it != null }
                .map { Path.of(it!!) }
                .anyMatch { !Files.exists(it) }
        }
    }

    private fun canonicalInputPath(resolved: ResolvedExtensionInput): String =
        Path.of(resolved.originalInput).toAbsolutePath().normalize().toRealPath().toString()

    private fun contentFingerprint(source: Path): String =
        sha256(ExtensionZip.createBytes(source))

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

    private fun sha256(value: String): String =
        sha256(value.toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
    }
}
