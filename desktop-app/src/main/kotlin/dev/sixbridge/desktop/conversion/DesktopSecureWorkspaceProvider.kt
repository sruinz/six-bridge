package dev.sixbridge.desktop.conversion

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.conversion.SecureConversionRoot
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

class DesktopSecureWorkspaceProvider {
    fun create(): DesktopWorkspaceLease {
        val fileSystem = Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build(),
        )
        try {
            val token = UUID.randomUUID().toString()
            val root = fileSystem.getPath("/sixbridge").resolve(token)
            val inputRoot = root.resolve("input")
            val artifactRoot = root.resolve("artifacts")
            Files.createDirectories(inputRoot)
            Files.createDirectory(artifactRoot)
            Files.writeString(
                root.resolve(OWNER_MARKER_FILE),
                OWNER_MARKER,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            val secureRoot = SecureConversionRoot.verify(root)
            return DesktopWorkspaceLease(
                workspace = DesktopWorkspace(
                    token = token,
                    root = root,
                    inputRoot = inputRoot,
                    artifactRoot = artifactRoot,
                ),
                secureRoot = secureRoot,
            )
        } catch (error: Throwable) {
            fileSystem.close()
            throw error
        }
    }

    private companion object {
        const val OWNER_MARKER_FILE = ".sixbridge-owner"
        const val OWNER_MARKER = "sixbridge-desktop-work-v1"
    }
}
