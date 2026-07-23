package dev.sixbridge.core.crx

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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
        val safeIdentity = safeIdentity(identity)
        val privatePath = keyRoot.resolve("$safeIdentity.pk8")
        val publicPath = keyRoot.resolve("$safeIdentity.pub")
        if (privatePath.exists() && publicPath.exists()) {
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicPath.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privatePath.readBytes())),
            )
        }

        val generated = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        privatePath.writeBytes(generated.private.encoded)
        publicPath.writeBytes(generated.public.encoded)
        restrictOwner(privatePath)
        restrictOwner(publicPath)
        return generated
    }

    fun persist(identity: String, keyPair: KeyPair) {
        Files.createDirectories(keyRoot)
        val safeIdentity = safeIdentity(identity)
        val privatePath = keyRoot.resolve("$safeIdentity.pk8")
        val publicPath = keyRoot.resolve("$safeIdentity.pub")
        if (!privatePath.exists()) privatePath.writeBytes(keyPair.private.encoded)
        if (!publicPath.exists()) publicPath.writeBytes(keyPair.public.encoded)
        restrictOwner(privatePath)
        restrictOwner(publicPath)
    }

    private fun safeIdentity(identity: String): String = identity
        .lowercase()
        .replace(Regex("[^a-z0-9._-]"), "-")
        .trim('-')
        .ifEmpty { "extension" }

    private fun restrictOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }
}
