package dev.sixbridge.desktop.ui

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal sealed interface DesktopRevealResult {
    data object Opened : DesktopRevealResult
    data class Failed(val message: String) : DesktopRevealResult
}

internal fun interface DesktopFolderOpener {
    fun open(parent: Path): Boolean
}

internal class DesktopSavedDraftRevealer(
    private val opener: DesktopFolderOpener = DesktopFolderOpener { parent ->
        if (!Desktop.isDesktopSupported()) return@DesktopFolderOpener false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) return@DesktopFolderOpener false
        desktop.open(parent.toFile())
        true
    },
) {
    fun reveal(savedPath: Path): DesktopRevealResult = try {
        val parent = requireNotNull(savedPath.toAbsolutePath().normalize().parent)
        if (Files.isSymbolicLink(parent)) return failure()
        val realParent = parent.toRealPath(NOFOLLOW_LINKS)
        val attributes = Files.readAttributes(
            realParent,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isDirectory || attributes.isSymbolicLink) return failure()
        if (opener.open(realParent)) DesktopRevealResult.Opened else failure()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        failure()
    }

    private fun failure() = DesktopRevealResult.Failed(REVEAL_FAILURE_MESSAGE)

    private companion object {
        const val REVEAL_FAILURE_MESSAGE = "저장된 파일 위치를 열 수 없습니다."
    }
}
