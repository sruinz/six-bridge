package dev.sixbridge.core.draft

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DraftSignatureTest {
    @Test
    fun `signature uses canonical name size hash lines and direct Ed25519 bytes`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val entries = linkedMapOf(
            "z.json" to "last".encodeToByteArray(),
            "a.json" to "first".encodeToByteArray(),
        )
        val payload = DraftSignature.canonicalPayload(entries)
        val signature = DraftSignature.sign(entries, keyPair)

        assertEquals(
            "a.json\t5\t${DraftSignature.sha256("first".encodeToByteArray())}\n" +
                "z.json\t4\t${DraftSignature.sha256("last".encodeToByteArray())}\n",
            payload.decodeToString(),
        )
        assertTrue(DraftSignature.verify(entries, signature))
    }

    @Test
    fun `signature entry is excluded from canonical payload`() {
        val without = mapOf("draft.json" to byteArrayOf(1))
        val withSignature = without + ("draft-signature.json" to byteArrayOf(9))

        assertContentEquals(
            DraftSignature.canonicalPayload(without),
            DraftSignature.canonicalPayload(withSignature),
        )
    }

    @Test
    fun `malformed and non Ed25519 X509 public keys are rejected`() {
        val entries = mapOf("draft.json" to "payload".encodeToByteArray())
        val valid = DraftSignature.sign(
            entries,
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        )
        val invalidKeys = listOf(
            byteArrayOf(1, 2, 3),
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair().public.encoded,
        )

        invalidKeys.forEach { publicKey ->
            assertFails { DraftSignature.fingerprint(publicKey) }
            assertFalse(
                DraftSignature.verify(
                    entries,
                    DraftSignatureRecord.create(
                        schemaVersion = DraftSignature.SCHEMA_VERSION,
                        algorithm = DraftSignature.ALGORITHM,
                        keyId = DraftSignature.sha256(publicKey),
                        publicKey = publicKey,
                        signature = valid.signature,
                    ),
                ),
            )
        }
    }

    @Test
    fun `public API requires explicit trusted and key roots`() {
        val constructors = DraftSigningKeyStore::class.java.constructors.toList()

        assertTrue(constructors.none { it.parameterCount == 0 || it.parameterCount == 1 })
        assertTrue(
            constructors.any {
                it.parameterTypes.toList() == listOf(Path::class.java, Path::class.java)
            },
        )
    }

    @Test
    fun `key store creates stable separated owner-only Ed25519 pair`() {
        val trusted = Files.createTempDirectory("six-draft-key")
        val root = trusted.resolve("draft-transfer")
        val store = DraftSigningKeyStore(trusted, root)

        val first = store.getOrCreate()
        val second = store.getOrCreate()

        assertEquals("EdDSA", first.public.algorithm)
        assertContentEquals(first.public.encoded, second.public.encoded)
        assertContentEquals(first.private.encoded, second.private.encoded)
        assertEquals(1, root.listDirectoryEntries("*.pub").size)
        assertEquals(1, root.listDirectoryEntries("*.pk8").size)
        assertEquals(1, root.listDirectoryEntries("*.identity").size)
        root.listDirectoryEntries().forEach {
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(it),
            )
        }
    }

    @Test
    fun `existing over permission and corrupted keys fail without regeneration`() {
        val trusted = Files.createTempDirectory("six-draft-key-corrupt")
        val root = trusted.resolve("draft-transfer")
        val store = DraftSigningKeyStore(trusted, root)
        val original = store.getOrCreate()
        val publicPath = root.listDirectoryEntries("*.pub").single()
        val privatePath = root.listDirectoryEntries("*.pk8").single()

        Files.setPosixFilePermissions(
            publicPath,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            ),
        )
        assertFails { store.getOrCreate() }
        assertContentEquals(original.private.encoded, Files.readAllBytes(privatePath))

        Files.setPosixFilePermissions(
            publicPath,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
        Files.write(publicPath, byteArrayOf(1, 2, 3))
        assertFails { store.getOrCreate() }
        assertContentEquals(original.private.encoded, Files.readAllBytes(privatePath))
    }

    @Test
    fun `mismatched public and private pair is rejected`() {
        val firstTrusted = Files.createTempDirectory("six-draft-pair-first")
        val firstRoot = firstTrusted.resolve("keys")
        DraftSigningKeyStore(firstTrusted, firstRoot).getOrCreate()
        val secondTrusted = Files.createTempDirectory("six-draft-pair-second")
        val secondRoot = secondTrusted.resolve("keys")
        DraftSigningKeyStore(secondTrusted, secondRoot).getOrCreate()

        Files.copy(
            secondRoot.listDirectoryEntries("*.pub").single(),
            firstRoot.listDirectoryEntries("*.pub").single(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )

        assertFails {
            DraftSigningKeyStore(firstTrusted, firstRoot).getOrCreate()
        }
    }

    @Test
    fun `symlink and hardlink key files are rejected`() {
        val trusted = Files.createTempDirectory("six-draft-key-links")
        val root = trusted.resolve("keys")
        val store = DraftSigningKeyStore(trusted, root)
        store.getOrCreate()
        val publicPath = root.listDirectoryEntries("*.pub").single()
        val outside = trusted.resolve("outside.pub")
        Files.move(publicPath, outside)
        Files.createSymbolicLink(publicPath, outside)

        assertFails { store.getOrCreate() }
        assertTrue(Files.isSymbolicLink(publicPath))

        Files.delete(publicPath)
        Files.createLink(publicPath, outside)
        assertFails { store.getOrCreate() }
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `trusted root symlink ancestor is rejected without outside writes`() {
        val parent = Files.createTempDirectory("six-draft-trusted-parent")
        val outside = Files.createTempDirectory("six-draft-trusted-outside")
        val linked = parent.resolve(".local")
        Files.createSymbolicLink(linked, outside)

        assertFails {
            DraftSigningKeyStore(
                trustedRoot = parent,
                keyRoot = linked.resolve("keystore/draft-transfer"),
            ).getOrCreate()
        }
        assertTrue(outside.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `concurrent creation returns one complete key pair`() {
        val trusted = Files.createTempDirectory("six-draft-concurrent")
        val root = trusted.resolve("keys")
        val pool = Executors.newFixedThreadPool(8)
        val futures = try {
            (1..16).map {
                pool.submit(
                    Callable {
                        DraftSigningKeyStore(trusted, root).getOrCreate()
                    },
                )
            }
        } finally {
            pool.shutdown()
        }
        val pairs = futures.map { it.get() }

        pairs.drop(1).forEach {
            assertContentEquals(pairs.first().public.encoded, it.public.encoded)
            assertContentEquals(pairs.first().private.encoded, it.private.encoded)
        }
        assertEquals(1, root.listDirectoryEntries("*.identity").size)
        assertFalse(root.listDirectoryEntries().any {
            it.fileName.toString().contains(".tmp-")
        })
    }
}
