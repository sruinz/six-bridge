package dev.sixbridge.core.conversion

import java.nio.file.Path
import java.util.UUID

internal class ConversionTempTreeFactory(
    private val secureRoot: SecureConversionRoot? = null,
    private val createdRootObserver: (Path) -> Unit = {},
) {
    fun create(prefix: String): OwnedTempDirectory {
        val owner = secureRoot?.let { root ->
            val path = root.path.resolve("$prefix${UUID.randomUUID()}")
            OwnedTempTree.createAt(path)
        } ?: OwnedTempTree.create(prefix)
        createdRootObserver(owner.rootPath)
        return owner
    }
}
