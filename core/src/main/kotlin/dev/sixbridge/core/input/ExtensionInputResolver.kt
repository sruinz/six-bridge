package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

enum class ExtensionInputKind {
    SOURCE_DIRECTORY,
    ZIP,
    CRX3,
    CHROME_WEB_STORE,
}

data class ResolvedExtensionInput(
    val kind: ExtensionInputKind,
    val originalInput: String,
    val sourceRoot: Path? = null,
    val crxBytes: ByteArray? = null,
    val expectedExtensionId: String? = null,
    val projectIdentity: String? = null,
)

class UnsupportedInputException(message: String) : IllegalArgumentException(message)

fun interface ChromeCrxDownloader {
    fun download(address: ChromeWebStoreAddress): ByteArray
}

class ExtensionInputResolver(
    private val chromeDownloader: ChromeCrxDownloader? = null,
) {
    fun resolve(input: String): ResolvedExtensionInput {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return resolveUrl(input)
        }

        val path = Path.of(input).toAbsolutePath().normalize()
        return when {
            path.isDirectory() -> ResolvedExtensionInput(
                kind = ExtensionInputKind.SOURCE_DIRECTORY,
                originalInput = input,
                sourceRoot = path,
            )
            path.isRegularFile() && path.extension.equals("zip", ignoreCase = true) -> {
                val extracted = Files.createTempDirectory("sixbridge-extension-")
                val root = ExtensionZip.extract(path, extracted)
                ResolvedExtensionInput(
                    kind = ExtensionInputKind.ZIP,
                    originalInput = input,
                    sourceRoot = root,
                )
            }
            path.isRegularFile() && path.extension.equals("crx", ignoreCase = true) -> {
                val bytes = path.readBytes()
                val parsed = Crx3.parse(bytes)
                ResolvedExtensionInput(
                    kind = ExtensionInputKind.CRX3,
                    originalInput = input,
                    sourceRoot = ExtensionZip.extract(
                        parsed.zipBytes,
                        Files.createTempDirectory("sixbridge-extension-"),
                    ),
                    crxBytes = bytes,
                    expectedExtensionId = parsed.extensionId,
                )
            }
            else -> throw UnsupportedInputException("지원하지 않는 입력입니다: $input")
        }
    }

    private fun resolveUrl(input: String): ResolvedExtensionInput {
        val address = ChromeWebStoreAddress.parse(input)
        val downloader = chromeDownloader ?: throw UnsupportedInputException(
            "Chrome 웹 스토어 URL 다운로드 기능이 주입되지 않았습니다.",
        )
        val bytes = downloader.download(address)
        val parsed = Crx3.parse(bytes)
        if (parsed.extensionId != address.extensionId) {
            throw UnsupportedInputException(
                "다운로드한 CRX ID(${parsed.extensionId})가 요청 ID(${address.extensionId})와 다릅니다.",
            )
        }
        return ResolvedExtensionInput(
            kind = ExtensionInputKind.CHROME_WEB_STORE,
            originalInput = input,
            sourceRoot = ExtensionZip.extract(
                parsed.zipBytes,
                Files.createTempDirectory("sixbridge-extension-"),
            ),
            crxBytes = bytes,
            expectedExtensionId = address.extensionId,
        )
    }
}
