package dev.sixbridge.desktop.profile

import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DesktopProfileJsonReaderTest {
    @Test
    fun `profile JSON은 MAX bytes만 읽고 MAX plus one은 즉시 거부한다`() {
        val exact = Files.createTempFile("profile-exact-", ".json")
        val oversized = Files.createTempFile("profile-oversized-", ".json")
        val exactBytes = ByteArray(DesktopProfileJsonReader.MAX_BYTES) { 0x31 }
        Files.write(exact, exactBytes)
        Files.write(oversized, ByteArray(DesktopProfileJsonReader.MAX_BYTES + 1) { 0x32 })
        val reader = DesktopProfileJsonReader()

        assertContentEquals(exactBytes, reader.read(exact))
        val error = assertFailsWith<DesktopProfileJsonReadException> { reader.read(oversized) }
        assertFalse(error.message.orEmpty().contains(oversized.toString()))
    }

    @Test
    fun `channel open 후 파일이 커져도 MAX plus one에서 거부한다`() {
        val growing = Files.createTempFile("profile-growing-", ".json")
        Files.writeString(growing, "{}")
        val reader = DesktopProfileJsonReader.testing(afterFirstSnapshot = { path ->
            Files.newByteChannel(path, WRITE, TRUNCATE_EXISTING).use { output ->
                output.write(java.nio.ByteBuffer.wrap(ByteArray(DesktopProfileJsonReader.MAX_BYTES + 1)))
            }
        })

        assertFailsWith<DesktopProfileJsonReadException> { reader.read(growing) }
    }

    @Test
    fun `profile JSON symlink와 open 후 path 교체를 거부한다`() {
        val root = Files.createTempDirectory("profile-reader-")
        val target = root.resolve("target.json")
        Files.writeString(target, "{}")
        val link = Files.createSymbolicLink(root.resolve("link.json"), target)
        assertFailsWith<DesktopProfileJsonReadException> {
            DesktopProfileJsonReader().read(link)
        }

        val input = root.resolve("input.json")
        Files.writeString(input, "{}")
        val replacingReader = DesktopProfileJsonReader.testing(afterFirstSnapshot = { path ->
            val replacement = Files.createTempFile(path.parent, "replacement-", ".json")
            Files.writeString(replacement, "{\"changed\":true}")
            Files.move(replacement, path, ATOMIC_MOVE, REPLACE_EXISTING)
        })
        assertFailsWith<DesktopProfileJsonReadException> { replacingReader.read(input) }
    }

    @Test
    fun `첫 snapshot 뒤 동일 inode 내용을 같은 길이로 바꾸고 mtime을 복원해도 거부한다`() {
        val input = Files.createTempFile("profile-same-inode-", ".json")
        val original = "{\"a\":1}"
        val changed = "{\"b\":2}"
        check(original.length == changed.length)
        Files.writeString(input, original)
        val originalTime = Files.getLastModifiedTime(input)
        val reader = DesktopProfileJsonReader.testing(afterFirstSnapshot = { path ->
            Files.writeString(path, changed, WRITE, TRUNCATE_EXISTING)
            Files.setLastModifiedTime(path, originalTime)
        })

        assertFailsWith<DesktopProfileJsonReadException> { reader.read(input) }
    }
}
