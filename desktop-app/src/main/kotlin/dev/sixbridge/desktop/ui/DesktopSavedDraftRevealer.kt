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
        val identity = captureDirectoryChain(parent)
        if (!verifyDirectoryChain(identity)) return failure()

        // AWT Desktop.open은 검증한 directory descriptor를 받지 않으므로 마지막
        // 검증과 OS path 해석 사이의 micro-window를 완전히 결속할 수 없다. 호출
        // 전후 identity를 모두 확인해 교체를 성공으로 보고하지 않는 fail-closed
        // 경계를 유지한다.
        val opened = opener.open(identity.last().path)
        if (opened && verifyDirectoryChain(identity)) DesktopRevealResult.Opened else failure()
    } catch (error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        failure()
    }

    private fun failure() = DesktopRevealResult.Failed(REVEAL_FAILURE_MESSAGE)

    private fun captureDirectoryChain(parent: Path): List<DirectoryIdentity> {
        val absolute = parent.toAbsolutePath().normalize()
        val root = requireNotNull(absolute.root)
        val paths = buildList {
            var current = root
            add(current)
            root.relativize(absolute).forEach { name ->
                current = current.resolve(name)
                add(current)
            }
        }
        return paths.map { path ->
            if (Files.isSymbolicLink(path)) throw IllegalArgumentException()
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            val fileKey = requireNotNull(attributes.fileKey())
            if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw IllegalArgumentException()
            }
            DirectoryIdentity(path, fileKey)
        }
    }

    private fun verifyDirectoryChain(expected: List<DirectoryIdentity>): Boolean = try {
        val actual = captureDirectoryChain(expected.last().path)
        actual == expected
    } catch (_: Exception) {
        false
    }

    private data class DirectoryIdentity(val path: Path, val fileKey: Any)

    private companion object {
        const val REVEAL_FAILURE_MESSAGE = "저장된 파일 위치를 열 수 없습니다."
    }
}
