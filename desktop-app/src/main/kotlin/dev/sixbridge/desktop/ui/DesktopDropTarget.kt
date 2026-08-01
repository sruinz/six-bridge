@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.sixbridge.desktop.ui

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

fun normalizeDrop(paths: List<Path>): DesktopExtensionInput.Local? {
    val candidate = paths.singleOrNull()?.toAbsolutePath()?.normalize() ?: return null
    if (Files.isSymbolicLink(candidate) || !Files.exists(candidate, NOFOLLOW_LINKS)) return null
    if (!Files.isRegularFile(candidate, NOFOLLOW_LINKS) && !Files.isDirectory(candidate, NOFOLLOW_LINKS)) return null
    return DesktopExtensionInput.Local(candidate)
}

fun normalizeDrop(transferable: Transferable): DesktopExtensionInput.Local? {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null
    val values = runCatching {
        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
    }.getOrNull() ?: return null
    val files = values.filterIsInstance<File>()
    if (files.size != values.size) return null
    return normalizeDrop(files.map(File::toPath))
}

class DesktopDropTarget(
    private val onLocalPath: (Path) -> Unit,
    private val onRejected: (String) -> Unit,
) : DragAndDropTarget {
    fun accepts(event: DragAndDropEvent): Boolean =
        event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val input = normalizeDrop(event.awtTransferable)
        if (input == null) {
            onRejected("실제 로컬 파일 또는 폴더 하나만 놓을 수 있습니다.")
            return false
        }
        onLocalPath(input.path)
        return true
    }
}
