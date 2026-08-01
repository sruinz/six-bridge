package dev.sixbridge.desktop.conversion

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopDraftSaverTest {
    @Test
    fun `기존 목적 파일을 덮어쓰지 않고 임시 파일을 남기지 않는다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { privateFileSystem ->
            val source = source(privateFileSystem)
            Files.writeString(source, "signed-draft")
            val destinationDirectory = Files.createTempDirectory("sixbridge-saver-")
            val destination = destinationDirectory.resolve("draft.sixdraft")
            Files.writeString(destination, "user-data")
            val artifact = artifact(source, "signed-draft")

            assertFailsWith<Exception> { DesktopDraftSaver().save(artifact, destination) }

            assertEquals("user-data", Files.readString(destination))
            assertTrue(
                Files.list(destinationDirectory).use { paths ->
                    paths.noneMatch { it.fileName.toString().startsWith(".sixbridge-save-") }
                },
            )
        }
    }

    @Test
    fun `Jimfs 초안을 host 목적지에 hash 검증 뒤 공개한다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { privateFileSystem ->
            val source = source(privateFileSystem)
            Files.writeString(source, "signed-draft")
            val destination = Files.createTempDirectory("sixbridge-saver-").resolve("draft.sixdraft")

            DesktopDraftSaver().save(artifact(source, "signed-draft"), destination)

            assertEquals("signed-draft", Files.readString(destination))
        }
    }

    @Test
    fun `hash 검증 실패는 자신이 만든 임시 파일을 삭제한다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { privateFileSystem ->
            val source = source(privateFileSystem)
            Files.writeString(source, "signed-draft")
            val directory = Files.createTempDirectory("sixbridge-saver-")

            assertFailsWith<IllegalStateException> {
                DesktopDraftSaver().save(artifact(source, "different"), directory.resolve("draft.sixdraft"))
            }

            assertTrue(Files.list(directory).use { it.noneMatch { path -> path.fileName.toString().startsWith(".sixbridge-save-") } })
        }
    }

    @Test
    fun `ATOMIC_MOVE는 기존 목적 파일을 덮어쓸 수 있다`() {
        val directory = Files.createTempDirectory("sixbridge-atomic-move-")
        val temporary = directory.resolve("temporary")
        val destination = directory.resolve("destination")
        Files.writeString(temporary, "replacement")
        Files.writeString(destination, "user-data")

        Files.move(temporary, destination, ATOMIC_MOVE)

        assertEquals("replacement", Files.readString(destination))
    }

    @Test
    fun `workspace 밖 또는 심볼릭 링크 초안은 공개하지 않는다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val source = source(fileSystem)
            Files.writeString(source, "signed-draft")
            val link = source.parent.resolve("link.sixdraft")
            Files.createSymbolicLink(link, source)

            assertFailsWith<DesktopDraftSaveException> {
                DesktopDraftSaver().save(artifact(link, "signed-draft"), Files.createTempDirectory("sixbridge-saver-").resolve("draft"))
            }
        }
    }

    @Test
    fun `hard link 미지원 목적지는 복사 전에 명확히 거부한다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val source = source(fileSystem)
            Files.writeString(source, "signed-draft")
            val destination = Files.createTempDirectory("sixbridge-saver-").resolve("draft")

            assertFailsWith<DesktopDraftSaveException> {
                DesktopDraftSaver(DesktopDraftSaverTestHooks(createLink = { _, _ ->
                    throw UnsupportedOperationException("hard link 없음")
                })).save(artifact(source, "signed-draft"), destination)
            }

            assertTrue(Files.list(destination.parent).use { it.noneMatch { path -> path.fileName.toString().startsWith(".sixbridge-save-") } })
        }
    }

    @Test
    fun `취소된 복사는 임시 파일을 남기지 않는다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val source = source(fileSystem)
            Files.writeString(source, "signed-draft")
            val destination = Files.createTempDirectory("sixbridge-saver-").resolve("draft")

            assertFailsWith<CancellationException> {
                DesktopDraftSaver(DesktopDraftSaverTestHooks(beforeCopy = {
                    throw CancellationException("cancel")
                })).save(artifact(source, "signed-draft"), destination)
            }
            assertTrue(Files.list(destination.parent).use { it.noneMatch { path -> path.fileName.toString().startsWith(".sixbridge-save-") } })
        }
    }

    private fun artifact(source: java.nio.file.Path, contents: String) = DesktopDraftArtifact(
        token = "00000000-0000-0000-0000-000000000001",
        privateFile = source,
        suggestedFileName = "draft.sixdraft",
        sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(contents.encodeToByteArray())
            .joinToString("") { "%02x".format(it) },
        signerFingerprint = "test",
    )

    private fun source(fileSystem: java.nio.file.FileSystem): java.nio.file.Path =
        fileSystem.getPath("/sixbridge/00000000-0000-0000-0000-000000000001/artifacts/draft.sixdraft")
            .also { Files.createDirectories(it.parent) }
}
