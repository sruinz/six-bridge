package dev.sixbridge.cli

import dev.sixbridge.core.input.ChromeCrxDownloader
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.UnsupportedInputException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class DesktopChromeCrxDownloader(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build(),
) : ChromeCrxDownloader {
    override fun download(address: ChromeWebStoreAddress): ByteArray {
        val request = HttpRequest.newBuilder(updateUri(address))
            .header("User-Agent", "SIX-Bridge/0.1")
            .GET()
            .build()
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (response.statusCode() !in 200..299) {
            throw UnsupportedInputException(
                "Chrome 웹 스토어 CRX 다운로드 실패: HTTP ${response.statusCode()}",
            )
        }
        return response.body()
    }

    companion object {
        @JvmSynthetic
        internal fun updateUri(address: ChromeWebStoreAddress): URI = URI(
            "https://clients2.google.com/service/update2/crx" +
                "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                "&x=id%3D${address.extensionId}%26uc",
        )
    }
}
