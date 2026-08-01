package dev.sixbridge.desktop.ui

import java.nio.file.Files
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSavedDraftRevealerTest {
    @Test
    fun `저장된 파일의 실제 parent directory만 연다`() {
        val parent = Files.createTempDirectory("desktop-reveal-")
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
        val actual = Files.createTempDirectory("desktop-reveal-")
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
}
