package dev.sixbridge.core.input

import java.net.URI

class ChromeWebStoreAddress private constructor(
    val extensionId: String,
) {
    override fun toString(): String = extensionId

    companion object {
        private val EXTENSION_ID = Regex("[a-p]{32}")
        private val DETAIL_PATH = Regex("""/detail/[^/]+/([a-p]{32})""")

        fun parse(value: String): ChromeWebStoreAddress {
            val uri = try {
                URI(value)
            } catch (_: IllegalArgumentException) {
                throw UnsupportedInputException(
                    "Chrome 웹 스토어 상세 주소 형식이 올바르지 않습니다.",
                )
            }
            if (uri.scheme != "https" ||
                !uri.host.equals("chromewebstore.google.com", ignoreCase = true) ||
                uri.userInfo != null ||
                uri.port != -1 ||
                uri.fragment != null
            ) {
                throw UnsupportedInputException(
                    "정확한 Chrome 웹 스토어 HTTPS 상세 주소만 사용할 수 있습니다.",
                )
            }
            val match = DETAIL_PATH.matchEntire(uri.rawPath)
                ?: throw UnsupportedInputException(
                    "Chrome 웹 스토어 상세 주소에서 확장 ID를 확인할 수 없습니다.",
                )
            val extensionId = match.groupValues[1]
            if (!EXTENSION_ID.matches(extensionId)) {
                throw UnsupportedInputException("Chrome 확장 ID 형식이 올바르지 않습니다.")
            }
            return ChromeWebStoreAddress(extensionId)
        }
    }
}
