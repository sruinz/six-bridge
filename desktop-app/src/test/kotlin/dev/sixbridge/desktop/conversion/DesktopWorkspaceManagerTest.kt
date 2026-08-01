package dev.sixbridge.desktop.conversion

import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopWorkspaceManagerTest {
    @Test
    fun `workspace는 Jimfs SDS provider와 canonical UUID marker를 사용한다`() {
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            assertNotNull(UUID.fromString(lease.workspace.token))
            Files.newDirectoryStream(lease.workspace.root).use {
                assertIs<SecureDirectoryStream<*>>(it)
            }
            assertEquals(
                "sixbridge-desktop-work-v1",
                Files.readString(lease.workspace.root.resolve(".sixbridge-owner")),
            )
            assertTrue(Files.isDirectory(lease.workspace.inputRoot))
            assertTrue(Files.isDirectory(lease.workspace.artifactRoot))
            assertEquals(
                lease.workspace.root.fileSystem.provider(),
                lease.secureRoot.path.fileSystem.provider(),
            )
        }
    }

    @Test
    fun `cleanup은 자신이 만든 exact token만 닫는다`() {
        val manager = DesktopWorkspaceManager()
        val first = manager.create()
        val second = manager.create()

        assertTrue(manager.cleanup(first.workspace.token))
        assertFalse(first.workspace.root.fileSystem.isOpen)
        assertTrue(second.workspace.root.fileSystem.isOpen)
        assertFalse(manager.cleanup("not-a-token"))
        assertTrue(second.workspace.root.fileSystem.isOpen)
        second.close()
    }

    @Test
    fun `lease close는 정확히 한 번만 닫고 orphan cleanup은 닫힌 lease만 제거한다`() {
        val manager = DesktopWorkspaceManager()
        val closed = manager.create()
        val active = manager.create()

        closed.close()
        closed.close()

        assertEquals(1, manager.cleanupOrphans())
        assertFalse(closed.workspace.root.fileSystem.isOpen)
        assertTrue(active.workspace.root.fileSystem.isOpen)
        assertEquals(0, DesktopWorkspaceManager().cleanupOrphans())
        active.close()
    }

    @Test
    fun `extraction root는 같은 workspace 안에서 매번 새 빈 디렉터리다`() {
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val first = lease.workspace.createExtractionRoot()
            val second = lease.workspace.createExtractionRoot()

            assertNotEquals(first, second)
            assertTrue(Files.isDirectory(first))
            assertTrue(Files.isDirectory(second))
            assertTrue(Files.list(first).use { it.findAny().isEmpty })
            assertTrue(Files.list(second).use { it.findAny().isEmpty })
            assertEquals(
                lease.workspace.root.fileSystem.provider(),
                first.fileSystem.provider(),
            )
        }
    }
}
