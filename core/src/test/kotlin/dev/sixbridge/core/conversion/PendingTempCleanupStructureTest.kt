package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import java.nio.charset.StandardCharsets.ISO_8859_1
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingTempCleanupStructureTest {
    @Test
    fun `process owner has no shutdown cleanup hook or unanchored path registry`() {
        val resource = requireNotNull(
            PendingTempCleanup::class.java.getResourceAsStream(
                "PendingTempCleanup.class",
            ),
        )
        val classFile = resource.use { it.readAllBytes() }
            .toString(ISO_8859_1)

        assertFalse(classFile.contains("addShutdownHook"))
        assertFalse(classFile.contains("unanchoredPending"))
    }

    @Test
    fun `owner retains its root handle and has bounded close retry state`() {
        val ownerClass = requireNotNull(
            PendingTempCleanup::class.java.getResourceAsStream(
                "PendingTempCleanup\$Owner.class",
            ),
        ).use { it.readAllBytes() }.toString(ISO_8859_1)
        val cleanupClass = requireNotNull(
            PendingTempCleanup::class.java.getResourceAsStream(
                "PendingTempCleanup.class",
            ),
        ).use { it.readAllBytes() }.toString(ISO_8859_1)

        assertTrue(ownerClass.contains("rootHandle"))
        assertTrue(cleanupClass.contains("closeRetryOwners"))
        assertTrue(cleanupClass.contains("MAX_ACTIVE_OWNERS"))
        assertFalse(cleanupClass.contains("registerCreated"))
    }

    @Test
    fun `owner population helpers are hidden from ordinary Java calls`() {
        val extractInto = ExtensionZip::class.java.declaredMethods.filter {
            it.name == "extractInto"
        }
        val copyInto = NativeWorkspaceTree::class.java.declaredMethods.single {
            it.name == "copyInto"
        }

        assertTrue(extractInto.isNotEmpty())
        assertTrue(extractInto.all { it.isSynthetic })
        assertTrue(copyInto.isSynthetic)
        assertTrue(OwnedTempDirectory::class.java.isSealed)
        assertTrue(OwnedTempDirectory::class.java.declaredConstructors.isEmpty())
    }
}
