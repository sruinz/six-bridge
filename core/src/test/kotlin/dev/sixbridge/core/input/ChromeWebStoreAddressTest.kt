package dev.sixbridge.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChromeWebStoreAddressTest {
    @Test
    fun `정확한 Chrome 상세 HTTPS 주소에서 ID만 보존한다`() {
        val address = ChromeWebStoreAddress.parse(
            "https://chromewebstore.google.com/detail/video-download-to-nas/" +
                "fchehlladkkanoekpjffcfffpfbdaalj?hl=ko",
        )

        assertEquals("fchehlladkkanoekpjffcfffpfbdaalj", address.extensionId)
        assertFalse(address.toString().contains("video-download-to-nas"))
        assertFalse(address.toString().contains("hl=ko"))
    }

    @Test
    fun `비정확하거나 모호한 Chrome 주소를 거부한다`() {
        listOf(
            "http://chromewebstore.google.com/detail/name/fchehlladkkanoekpjffcfffpfbdaalj",
            "https://example.com/detail/name/fchehlladkkanoekpjffcfffpfbdaalj",
            "https://user@chromewebstore.google.com/detail/name/fchehlladkkanoekpjffcfffpfbdaalj",
            "https://chromewebstore.google.com/detail/name/fchehlladkkanoekpjffcfffpfbdaalj#reviews",
            "https://chromewebstore.google.com/detail/fchehlladkkanoekpjffcfffpfbdaalj",
            "https://chromewebstore.google.com/detail/name",
            "https://chromewebstore.google.com/detail/name/" +
                "fchehlladkkanoekpjffcfffpfbdaalj/" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ).forEach { input ->
            assertFailsWith<UnsupportedInputException>(input) {
                ChromeWebStoreAddress.parse(input)
            }
        }
    }

    @Test
    fun `core class 출력은 java net http에 결합되지 않는다`() {
        val resourceName = "/" + ExtensionInputResolver::class.java.name
            .replace('.', '/') + ".class"
        val bytes = requireNotNull(
            ExtensionInputResolver::class.java.getResourceAsStream(resourceName),
        ).use { it.readBytes() }

        assertFalse(
            bytes.toString(Charsets.ISO_8859_1).contains("java/net/http"),
        )
    }
}
