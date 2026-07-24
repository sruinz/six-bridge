package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.util.Comparator

internal fun applyAdapterToSource(
    source: Path,
    profile: BrowserProfile,
    adapter: MobileAdapter,
    selected: SelectedAdapter,
): AdapterDecision {
    lateinit var output: ByteArray
    val result = ConversionWorkspace().use { workspace ->
        val snapshot = workspace.snapshot(source)
        workspace.transform(snapshot, profile) { context ->
            adapter.apply(context, selected)
        }.also {
            output = ExtensionZip.createBytes(
                snapshot.workingRoot,
                includeProjectMarker = true,
            )
        }
    }
    replaceSourceTree(source, output)
    return result
}

private fun replaceSourceTree(source: Path, bytes: ByteArray) {
    val extractedParent = Files.createTempDirectory("sixbridge-adapter-test-output-")
    try {
        val extracted = ExtensionZip.extract(bytes, extractedParent.resolve("tree"))
        Files.walk(source).use { paths ->
            paths.filter { it != source }
                .sorted(Comparator.reverseOrder())
                .forEach(Files::delete)
        }
        Files.walk(extracted).use { paths ->
            paths.sorted().forEach { input ->
                if (input == extracted) return@forEach
                val output = source.resolve(extracted.relativize(input).toString())
                if (Files.isDirectory(input)) {
                    Files.createDirectories(output)
                } else {
                    Files.copy(input, output, COPY_ATTRIBUTES)
                }
            }
        }
    } finally {
        Files.walk(extractedParent).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
