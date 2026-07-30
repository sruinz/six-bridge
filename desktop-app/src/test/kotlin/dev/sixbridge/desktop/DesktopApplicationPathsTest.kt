package dev.sixbridge.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopApplicationPathsTest {
    @Test
    fun `macOS와 Windows는 승인된 앱 데이터 루트를 사용한다`() {
        val mac = DesktopApplicationPaths.resolve(
            osName = "Mac OS X",
            userHome = "/Users/test",
            localAppData = null,
        )
        val windows = DesktopApplicationPaths.resolve(
            osName = "Windows 11",
            userHome = "C:/Users/test",
            localAppData = "C:/Users/test/AppData/Local",
        )

        assertEquals(
            Path.of("/Users/test/Library/Application Support/SIX Bridge"),
            mac.dataRoot,
        )
        assertEquals(mac.dataRoot.resolve("work"), mac.workRoot)
        assertEquals(mac.dataRoot.resolve("draft-signing"), mac.draftKeyRoot)
        assertEquals(
            Path.of("C:/Users/test/AppData/Local/SIX Bridge"),
            windows.dataRoot,
        )
        assertEquals(windows.dataRoot.resolve("work"), windows.workRoot)
        assertEquals(windows.dataRoot.resolve("draft-signing"), windows.draftKeyRoot)
    }

    @Test
    fun `지원하지 않는 OS와 비어 있거나 상대적인 루트는 거부한다`() {
        assertFailsWith<IllegalStateException> {
            DesktopApplicationPaths.resolve("Linux", "/home/test", null)
        }
        assertFailsWith<IllegalStateException> {
            DesktopApplicationPaths.resolve("Mac OS X", "", null)
        }
        assertFailsWith<IllegalStateException> {
            DesktopApplicationPaths.resolve("Mac OS X", "Users/test", null)
        }
        assertFailsWith<IllegalStateException> {
            DesktopApplicationPaths.resolve(
                "Windows 11",
                "C:/Users/test",
                "",
            )
        }
        assertFailsWith<IllegalStateException> {
            DesktopApplicationPaths.resolve(
                "Windows 11",
                "C:/Users/test",
                "AppData/Local",
            )
        }
    }

    @Test
    fun `Windows 경로 문자열은 host와 무관하게 traversal과 비정상 segment를 거부한다`() {
        listOf(
            "C:relative",
            "C:/Users/test/../escape",
            "C:/Users/test/AppData/Local/bad:name",
            "C:/Users/test/AppData/Local/trailing.",
            "//server/share",
        ).forEach { localAppData ->
            assertFailsWith<IllegalStateException>(localAppData) {
                DesktopApplicationPaths.resolve(
                    osName = "Windows 11",
                    userHome = "C:/Users/test",
                    localAppData = localAppData,
                )
            }
        }
    }
}
