package dev.sixbridge.desktop.ui

import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDropTargetTest {
    @Test
    fun `drop은 실제 로컬 파일 또는 폴더 하나만 허용한다`() {
        val root = Files.createTempDirectory("sixbridge-drop-")
        val archive = root.resolve("extension.zip").createFile()
        val folder = root.resolve("source").createDirectory()

        assertEquals(
            DesktopExtensionInput.Local(archive.toAbsolutePath().normalize()),
            normalizeDrop(listOf(archive)),
        )
        assertEquals(
            DesktopExtensionInput.Local(folder.toAbsolutePath().normalize()),
            normalizeDrop(listOf(folder)),
        )
        assertNull(normalizeDrop(listOf(archive, folder)))
        assertNull(normalizeDrop(listOf(root.resolve("missing.zip"))))
        assertNull(normalizeDrop(listOf(Path.of("https://example.com"))))
    }

    @Test
    fun `drop은 symlink와 URI text를 거부한다`() {
        val root = Files.createTempDirectory("sixbridge-drop-")
        val target = root.resolve("extension.zip").createFile()
        val link = Files.createSymbolicLink(root.resolve("link.zip"), target)

        assertNull(normalizeDrop(listOf(link)))
        assertNull(normalizeDrop(StringSelection("file://${target.toAbsolutePath()}")))
    }

    @Test
    fun `java file list flavor는 로컬 파일 하나로 정규화한다`() {
        val archive = Files.createTempFile("sixbridge-drop-", ".crx")
        val transferable = FileListTransferable(listOf(archive.toFile()))

        assertEquals(
            DesktopExtensionInput.Local(archive.toAbsolutePath().normalize()),
            normalizeDrop(transferable),
        )
    }

    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            require(isDataFlavorSupported(flavor))
            return files
        }
    }
}
