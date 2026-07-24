package dev.sixbridge.core.conversion

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
}
