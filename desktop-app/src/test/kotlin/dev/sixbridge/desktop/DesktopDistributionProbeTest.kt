package dev.sixbridge.desktop

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDistributionProbeTest {
    @Test
    fun `distribution probe는 Jimfs 생성 성공과 안정 signer fingerprint만 출력한다`() {
        val paths = isolatedMacPaths()

        val first = DesktopDistributionProbe.run(paths, DesktopHostOs.MAC)
        val second = DesktopDistributionProbe.run(paths, DesktopHostOs.MAC)

        assertTrue(PROBE_OUTPUT.matches(first))
        assertEquals(first, second)
        assertFalse(first.contains(paths.dataRoot.toString()))
        assertFalse(first.contains("pk8"))
        assertFalse(first.contains("private", ignoreCase = true))
        assertTrue(Files.isDirectory(paths.draftKeyRoot))
    }

    @Test
    fun `distribution probe mode는 정확한 단일 argument에서만 선택된다`() {
        assertTrue(DesktopDistributionProbe.isRequested(arrayOf("--distribution-probe")))
        assertFalse(DesktopDistributionProbe.isRequested(emptyArray()))
        assertFalse(
            DesktopDistributionProbe.isRequested(
                arrayOf("--distribution-probe", "unexpected"),
            ),
        )
    }

    private fun isolatedMacPaths(): DesktopApplicationPaths {
        val home = Files.createTempDirectory("sixbridge-distribution-probe")
        val library = Files.createDirectory(home.resolve("Library"))
        val applicationSupport = Files.createDirectory(library.resolve("Application Support"))
        listOf(home, library, applicationSupport).forEach { path ->
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        return DesktopApplicationPaths.resolve(
            osName = "Mac OS X",
            userHome = home.toString(),
            localAppData = null,
        )
    }

    private companion object {
        val PROBE_OUTPUT = Regex(
            "^SIXBRIDGE_DISTRIBUTION_PROBE_V1 jimfs=OK signer=[0-9a-f]{64}$",
        )
    }
}
