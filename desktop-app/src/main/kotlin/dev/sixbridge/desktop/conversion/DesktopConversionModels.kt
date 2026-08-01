package dev.sixbridge.desktop.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface DesktopExtensionInput {
    data class ChromeStore(val url: String) : DesktopExtensionInput

    data class Local(val path: Path) : DesktopExtensionInput
}

data class DesktopAnalysisSession(
    val token: String,
    val input: DesktopExtensionInput,
    val profile: BrowserProfile,
    val report: CompatibilityReport,
    val logicalSourceIdentity: LogicalSourceIdentityResult,
    val selectedAutomaticAdapters: Set<String>,
    val requiredApprovals: Set<String>,
)

data class DesktopDraftArtifact(
    val token: String,
    val privateFile: Path,
    val suggestedFileName: String,
    val sha256: String,
    val signerFingerprint: String,
)

class DesktopConversionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

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
    private val closeObserver: (() -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    internal val isClosed: Boolean
        get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeObserver?.invoke()
            workspace.root.fileSystem.close()
        }
    }
}
