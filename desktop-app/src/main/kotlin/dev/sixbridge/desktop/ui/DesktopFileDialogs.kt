package dev.sixbridge.desktop.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import java.nio.file.Path
import javax.swing.JFileChooser

object DesktopFileDialogs {
    fun pickArchive(): Path? = pickFile("ZIP/CRX3 선택", setOf("zip", "crx"))

    fun pickProfileJson(): Path? = pickFile("Profile JSON 선택", setOf("json"))

    fun pickBrowserApk(): Path? = pickFile("Samsung Internet APK 선택", setOf("apk"))

    fun saveDraft(defaultFileName: String): Path? {
        val dialog = FileDialog(null as Frame?, "SixDraft 저장", FileDialog.SAVE).apply {
            file = defaultFileName.takeIf { it.endsWith(".sixdraft", ignoreCase = true) }
                ?: "$defaultFileName.sixdraft"
        }
        return try {
            dialog.isVisible = true
            dialog.file?.let { selected ->
                val fileName = selected.takeIf { it.endsWith(".sixdraft", ignoreCase = true) }
                    ?: "$selected.sixdraft"
                Path.of(dialog.directory, fileName)
            }
        } finally {
            dialog.dispose()
        }
    }

    fun pickFolder(): Path? {
        val chooser = JFileChooser().apply {
            dialogTitle = "확장 프로그램 폴더 선택"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }

    private fun pickFile(title: String, extensions: Set<String>): Path? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            filenameFilter = FilenameFilter { _: File, name: String ->
                name.substringAfterLast('.', "").lowercase() in extensions
            }
        }
        return try {
            dialog.isVisible = true
            dialog.file?.let { file -> Path.of(dialog.directory, file) }
        } finally {
            dialog.dispose()
        }
    }
}
