package dev.sixbridge.core.crx

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class ExtensionKeyManager(
    private val keyRoot: Path,
) {
    fun getOrCreate(identity: String): KeyPair {
        Files.createDirectories(keyRoot)
        getExisting(identity)?.let { return it }
        val safeIdentity = safeIdentity(identity)
        val privatePath = keyRoot.resolve("$safeIdentity.pk8")
        val publicPath = keyRoot.resolve("$safeIdentity.pub")

        val generated = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        writeOwnerOnly(privatePath, generated.private.encoded)
        writeOwnerOnly(publicPath, generated.public.encoded)
        return generated
    }

    fun getExisting(identity: String): KeyPair? {
        val safeIdentity = safeIdentity(identity)
        val privatePath = keyRoot.resolve("$safeIdentity.pk8")
        val publicPath = keyRoot.resolve("$safeIdentity.pub")
        if (!privatePath.exists() && !publicPath.exists()) return null
        require(privatePath.exists() && publicPath.exists()) {
            "CRX key pair가 불완전합니다: $safeIdentity"
        }
        val factory = KeyFactory.getInstance("RSA")
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(publicPath.readBytes())),
            factory.generatePrivate(PKCS8EncodedKeySpec(privatePath.readBytes())),
        )
    }

    fun findByExtensionId(extensionId: String): KeyPair? {
        getExisting(extensionId)?.let { key ->
            if (Crx3.extensionId(key.public.encoded) == extensionId) return key
        }
        if (!Files.isDirectory(keyRoot)) return null
        Files.list(keyRoot).use { paths ->
            val iterator = paths
                .filter { it.fileName.toString().endsWith(".pub") }
                .iterator()
            while (iterator.hasNext()) {
                val publicPath = iterator.next()
                val identity = publicPath.fileName.toString().removeSuffix(".pub")
                val key = getExisting(identity) ?: continue
                if (Crx3.extensionId(key.public.encoded) == extensionId) return key
            }
        }
        return null
    }

    fun persist(identity: String, keyPair: KeyPair) {
        Files.createDirectories(keyRoot)
        val safeIdentity = safeIdentity(identity)
        val privatePath = keyRoot.resolve("$safeIdentity.pk8")
        val publicPath = keyRoot.resolve("$safeIdentity.pub")
        if (!privatePath.exists()) writeOwnerOnly(privatePath, keyPair.private.encoded)
        if (!publicPath.exists()) writeOwnerOnly(publicPath, keyPair.public.encoded)
        restrictOwner(privatePath)
        restrictOwner(publicPath)
    }

    private fun safeIdentity(identity: String): String = identity
        .lowercase()
        .replace(Regex("[^a-z0-9._-]"), "-")
        .trim('-')
        .ifEmpty { "extension" }

    private fun restrictOwner(path: Path) {
        require(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $path"
        }
        val permissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        Files.setPosixFilePermissions(path, permissions)
        require(Files.getPosixFilePermissions(path) == permissions) {
            "owner-only 권한 적용을 확인할 수 없습니다: $path"
        }
    }

    private fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        require(Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $path"
        }
        Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        Files.write(path, bytes)
        restrictOwner(path)
    }

    companion object {
        private val OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
