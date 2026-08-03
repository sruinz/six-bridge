package dev.sixbridge.cli

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.conversion.SecureConversionRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

internal object CliSecureWorkspace {
    private val fileSystem by lazy {
        Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build(),
        )
    }

    val root: SecureConversionRoot by lazy {
        val path = fileSystem.getPath("/sixbridge-cli")
        Files.createDirectory(path)
        SecureConversionRoot.verify(path)
    }

    fun createExtractionRoot(): Path = Files.createDirectory(
        root.path.resolve("extension-${UUID.randomUUID()}"),
    )
}
