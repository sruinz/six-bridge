package dev.sixbridge.desktop.ui

import java.nio.file.Files
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSavedDraftRevealerTest {
    @Test
    fun `저장된 파일의 실제 parent directory만 연다`() {
        val parent = Files.createTempDirectory("desktop-reveal-").toRealPath()
        val saved = Files.writeString(parent.resolve("draft.sixdraft"), "draft")
        val opened = mutableListOf<java.nio.file.Path>()
        val revealer = DesktopSavedDraftRevealer { path ->
            opened.add(path)
            true
        }

        assertEquals(DesktopRevealResult.Opened, revealer.reveal(saved))
        assertEquals(1, opened.size)
        assertTrue(Files.isSameFile(parent, opened.single()))
    }

    @Test
    fun `symlink parent와 opener Throwable은 원문 없는 고정 한글 실패로 변환한다`() {
        val actual = Files.createTempDirectory("desktop-reveal-").toRealPath()
        val link = actual.parent.resolve("desktop-reveal-link-${System.nanoTime()}")
            .createSymbolicLinkPointingTo(actual)
        val linkedFile = Files.writeString(actual.resolve("draft.sixdraft"), "draft")
        val symlinkResult = DesktopSavedDraftRevealer { true }
            .reveal(link.resolve(linkedFile.fileName))
        val throwableResult = DesktopSavedDraftRevealer {
            throw IllegalStateException("/secret/raw/path")
        }.reveal(linkedFile)

        assertEquals(
            DesktopRevealResult.Failed("저장된 파일 위치를 열 수 없습니다."),
            symlinkResult,
        )
        assertEquals(
            DesktopRevealResult.Failed("저장된 파일 위치를 열 수 없습니다."),
            throwableResult,
        )
    }

    @Test
    fun `검증한 parent가 open 중 교체되면 Opened로 처리하지 않는다`() {
        val root = Files.createTempDirectory("desktop-reveal-swap-").toRealPath()
        val parent = Files.createDirectory(root.resolve("saved"))
        val saved = Files.writeString(parent.resolve("draft.sixdraft"), "draft")
        val moved = root.resolve("moved")
        val attacker = Files.createDirectory(root.resolve("attacker"))
        val result = DesktopSavedDraftRevealer { verified ->
            Files.move(verified, moved)
            verified.createSymbolicLinkPointingTo(attacker)
            true
        }.reveal(saved)

        assertEquals(
            DesktopRevealResult.Failed("저장된 파일 위치를 열 수 없습니다."),
            result,
        )
    }

    @Test
    fun `중간 path component가 symlink이면 fail closed한다`() {
        val root = Files.createTempDirectory("desktop-reveal-component-").toRealPath()
        val actual = Files.createDirectories(root.resolve("actual/child"))
        val link = root.resolve("link").createSymbolicLinkPointingTo(root.resolve("actual"))
        val saved = Files.writeString(actual.resolve("draft.sixdraft"), "draft")
        var opened = false

        val result = DesktopSavedDraftRevealer {
            opened = true
            true
        }.reveal(link.resolve("child").resolve(saved.fileName))

        assertEquals(
            DesktopRevealResult.Failed("저장된 파일 위치를 열 수 없습니다."),
            result,
        )
        assertEquals(false, opened)
    }
}
