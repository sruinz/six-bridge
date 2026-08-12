package dev.sixbridge.desktop.ui

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopBrandResourceTest {
    @Test
    fun `desktop 시작 화면은 승인된 SIX Bridge PNG를 사용한다`() {
        val bytes = assertNotNull(
            javaClass.classLoader.getResourceAsStream("images/six-bridge.png"),
        ).use { it.readBytes() }

        assertEquals(
            "f2bf70cd267e31dc48800161fa5c359e97c51a2650a8a0437713ab1f65e57960",
            bytes.sha256(),
        )
    }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }
