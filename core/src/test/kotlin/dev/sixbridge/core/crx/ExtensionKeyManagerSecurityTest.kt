package dev.sixbridge.core.crx

import java.nio.file.Files
import java.nio.file.Path
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
    private fun manager(root: Path): ExtensionKeyManager =
        ExtensionKeyManager(requireNotNull(root.parent), root)

    @Test
    fun `public API requires an explicit trusted root and key root`() {
        val publicConstructors = ExtensionKeyManager::class.java.constructors.toList()

        assertTrue(publicConstructors.none { it.parameterCount == 1 })
        assertTrue(
            publicConstructors.any { constructor ->
                constructor.parameterTypes.toList() == listOf(Path::class.java, Path::class.java)
            },
        )
    }

    @Test
    fun `trusted root symlink ancestor is rejected`() {
        val trustedParent = Files.createTempDirectory("six-key-trusted-parent")
        val outside = Files.createTempDirectory("six-key-trusted-outside")
        val linkedLocal = trustedParent.resolve(".local")
        Files.createSymbolicLink(linkedLocal, outside)

        assertFails {
            ExtensionKeyManager(
                trustedRoot = trustedParent,
                keyRoot = linkedLocal.resolve("keystore/crx"),
            ).getOrCreate("project-${"e".repeat(32)}")
        }
        assertTrue(outside.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `root replacement after anchored lock is rejected without writing replacement`() {
        val parent = Files.createTempDirectory("six-key-root-race")
        val root = parent.resolve("keys")
        val backup = parent.resolve("keys-backup")

        assertFails {
            ExtensionKeyManagerFaultInjection.replaceRootAfterLock(
                action = {
                    Files.move(root, backup)
                    Files.createDirectory(root)
                },
                block = {
                    manager(root)
                        .getOrCreate("project-${"f".repeat(32)}")
                },
            )
        }

        assertTrue(root.listDirectoryEntries().isEmpty())
        assertTrue(backup.listDirectoryEntries().all {
            it.fileName.toString() == ".sixbridge-key-registry.lock"
        })
    }

    @Test
    fun `directory fsync failure never commits metadata and recovers incomplete pair`() {
        val root = Files.createTempDirectory("six-key-fsync")
        val identity = "project-${"1".repeat(32)}"
        val manager = manager(root)

        assertFails {
            ExtensionKeyManagerFaultInjection.failNextDirectoryFsync {
                manager.getOrCreate(identity)
            }
        }
        assertTrue(root.listDirectoryEntries("*.identity").isEmpty())

        val recovered = manager.getOrCreate(identity)
        assertEquals(
            Crx3.extensionId(recovered.public.encoded),
            Crx3.extensionId(manager.getOrCreate(identity).public.encoded),
        )
    }

    @Test
    fun `committed metadata with a missing pair is corruption not normal recovery`() {
        val root = Files.createTempDirectory("six-key-committed-missing")
        val identity = "project-${"2".repeat(32)}"
        val manager = manager(root)
        manager.getOrCreate(identity)
        Files.delete(root.listDirectoryEntries("*.pub").single())

        assertFails {
            manager.getOrCreate(identity)
        }
        assertEquals(1, root.listDirectoryEntries("*.identity").size)
    }

    @Test
    fun `unavailable unix link count fails closed`() {
        val root = Files.createTempDirectory("six-key-nlink")

        assertFails {
            ExtensionKeyManagerFaultInjection.failUnixLinkCount {
                manager(root)
                    .getOrCreate("project-${"3".repeat(32)}")
            }
        }
        assertTrue(root.listDirectoryEntries("*.identity").isEmpty())
    }

    @Test
    fun `identities that collided under the legacy safe name get distinct canonical keys`() {
        val root = Files.createTempDirectory("six-key-collision")
        val manager = manager(root)

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
            manager(root).getOrCreate(identity)
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
                        manager(root).getOrCreate(identity)
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
        val manager = manager(root)

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

        val migrated = manager(root).getOrCreate(legacyIdentity)
        val colliding = manager(root).getOrCreate(
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
            val generated = ExtensionKeyManager(
                requireNotNull(staging.parent),
                staging,
            ).getOrCreate("seed")
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
