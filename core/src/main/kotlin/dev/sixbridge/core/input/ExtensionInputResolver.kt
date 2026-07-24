package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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

class ExtensionInputResolver(
    private val chromeDownloader: (URI, String) -> ByteArray = ::downloadChromeCrx,
) {
    fun resolve(input: String): ResolvedExtensionInput {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return resolveUrl(URI(input))
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

    private fun resolveUrl(uri: URI): ResolvedExtensionInput {
        if (uri.host.equals("microsoftedge.microsoft.com", ignoreCase = true)) {
            throw UnsupportedInputException(
                "Edge 스토어 URL 자동 다운로드는 후속 단계입니다. 소스, ZIP 또는 CRX3를 사용하세요.",
            )
        }
        if (!uri.host.equals("chromewebstore.google.com", ignoreCase = true)) {
            throw UnsupportedInputException("Chrome 웹 스토어 URL만 자동 다운로드할 수 있습니다.")
        }
        val extensionId = CHROME_EXTENSION_ID.find(uri.path)?.value
            ?: throw UnsupportedInputException("Chrome 확장 ID를 URL에서 찾지 못했습니다.")
        val bytes = chromeDownloader(uri, extensionId)
        if (bytes.size > 3) {
            val parsed = Crx3.parse(bytes)
            if (parsed.extensionId != extensionId) {
                throw UnsupportedInputException(
                    "다운로드한 CRX ID(${parsed.extensionId})가 요청 ID($extensionId)와 다릅니다.",
                )
            }
        }
        return ResolvedExtensionInput(
            kind = ExtensionInputKind.CHROME_WEB_STORE,
            originalInput = uri.toString(),
            sourceRoot = if (bytes.size > 3) {
                ExtensionZip.extract(
                    Crx3.parse(bytes).zipBytes,
                    Files.createTempDirectory("sixbridge-extension-"),
                )
            } else {
                null
            },
            crxBytes = bytes,
            expectedExtensionId = extensionId,
        )
    }

    companion object {
        private val CHROME_EXTENSION_ID = Regex("""[a-p]{32}""")

        private fun downloadChromeCrx(@Suppress("UNUSED_PARAMETER") pageUri: URI, id: String): ByteArray {
            val downloadUri = URI(
                "https://clients2.google.com/service/update2/crx" +
                    "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                    "&x=id%3D$id%26uc",
            )
            val request = HttpRequest.newBuilder(downloadUri)
                .header("User-Agent", "SIX-Bridge/0.1")
                .GET()
                .build()
            val response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) {
                throw UnsupportedInputException(
                    "Chrome 웹 스토어 CRX 다운로드 실패: HTTP ${response.statusCode()}",
                )
            }
            return response.body()
        }
    }
}
