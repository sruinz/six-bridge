package dev.sixbridge.desktop.conversion

import dev.sixbridge.core.conversion.SecureConversionRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DesktopExtensionInput {
    data class ChromeStore(val url: String) : DesktopExtensionInput

    data class Local(val path: Path) : DesktopExtensionInput
}

data class DesktopWorkspace(
    val token: String,
    val root: Path,
    val inputRoot: Path,
    val artifactRoot: Path,
) {
    fun createExtractionRoot(): Path =
        Files.createDirectory(artifactRoot.resolve("extraction-${UUID.randomUUID()}"))
}

class DesktopWorkspaceLease internal constructor(
    val workspace: DesktopWorkspace,
    val secureRoot: SecureConversionRoot,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    internal val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            workspace.root.fileSystem.close()
        }
    }
}
