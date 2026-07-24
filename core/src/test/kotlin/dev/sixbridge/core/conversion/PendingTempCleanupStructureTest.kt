package dev.sixbridge.core.conversion

import java.nio.charset.StandardCharsets.ISO_8859_1
import kotlin.test.Test
import kotlin.test.assertFalse

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
}
