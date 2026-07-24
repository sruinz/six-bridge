package dev.sixbridge.core.crx

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ExtensionKeyManagerSecurityTest {
    @Test
    fun `identities that collided under the legacy safe name get distinct canonical keys`() {
        val root = Files.createTempDirectory("six-key-collision")
        val manager = ExtensionKeyManager(root)

        val colon = manager.getOrCreate("store:abc:profile-30")
        val dash = manager.getOrCreate("store-abc-profile-30")

        assertNotEquals(
            Crx3.extensionId(colon.public.encoded),
            Crx3.extensionId(dash.public.encoded),
        )
        val identities = root.listDirectoryEntries("*.identity")
            .map { it.readText() }
        assertEquals(
            setOf("store:abc:profile-30", "store-abc-profile-30"),
            identities.toSet(),
        )
    }

    @Test
    fun `symlink key path is rejected without changing its target`() {
        val root = Files.createTempDirectory("six-key-symlink")
        val outside = Files.createTempFile("six-key-outside", ".bin")
        Files.writeString(outside, "outside")
        val identity = "project-${"a".repeat(32)}"
        val base = canonicalBase(identity)
        Files.createSymbolicLink(root.resolve("$base.pk8"), outside)

        assertFails {
            ExtensionKeyManager(root).getOrCreate(identity)
        }
        assertEquals("outside", Files.readString(outside))
    }

    @Test
    fun `concurrent creation publishes exactly one complete pair`() {
        val root = Files.createTempDirectory("six-key-concurrent")
        val identity = "project-${"b".repeat(32)}"
        val pool = Executors.newFixedThreadPool(8)
        val futures = try {
            (1..16).map {
                pool.submit(
                    Callable {
                        ExtensionKeyManager(root).getOrCreate(identity)
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
        assertEquals(1, root.listDirectoryEntries("*.pk8").size)
        assertEquals(1, root.listDirectoryEntries("*.pub").size)
    }

    @Test
    fun `incomplete pair left before identity commit is recovered`() {
        val root = Files.createTempDirectory("six-key-recovery")
        val identity = "project-${"c".repeat(32)}"
        val manager = ExtensionKeyManager(root)

        assertFails {
            ExtensionKeyManagerFaultInjection.failAfterPairPublish {
                manager.getOrCreate(identity)
            }
        }
        val recovered = manager.getOrCreate(identity)

        assertEquals(
            Crx3.extensionId(recovered.public.encoded),
            Crx3.extensionId(manager.getOrCreate(identity).public.encoded),
        )
        assertEquals(1, root.listDirectoryEntries("*.identity").size)
        assertTrue(root.listDirectoryEntries().none {
            it.fileName.toString().contains(".tmp-")
        })
    }

    @Test
    fun `recognized legacy identity migrates without cross logical reuse`() {
        val root = Files.createTempDirectory("six-key-legacy")
        val legacyIdentity = "manifest-${"d".repeat(32)}"
        val oldManager = LegacyKeyWriter(root)
        val legacy = oldManager.write(legacyIdentity)

        val migrated = ExtensionKeyManager(root).getOrCreate(legacyIdentity)
        val colliding = ExtensionKeyManager(root).getOrCreate(
            legacyIdentity.replace("manifest-", "manifest:"),
        )

        assertContentEquals(legacy.public.encoded, migrated.public.encoded)
        assertNotEquals(
            Crx3.extensionId(migrated.public.encoded),
            Crx3.extensionId(colliding.public.encoded),
        )
    }

    private fun canonicalBase(identity: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "id-$hash"
    }

    private class LegacyKeyWriter(
        private val root: java.nio.file.Path,
    ) {
        fun write(identity: String): java.security.KeyPair {
            val staging = Files.createTempDirectory("six-key-legacy-staging")
            val generated = ExtensionKeyManager(staging).getOrCreate("seed")
            Files.copy(
                staging.listDirectoryEntries("*.pk8").single(),
                root.resolve("$identity.pk8"),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            Files.copy(
                staging.listDirectoryEntries("*.pub").single(),
                root.resolve("$identity.pub"),
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            return generated
        }
    }
}
