package dev.sixbridge.desktop.input

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeCrxDownloader
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.conversion.DesktopWorkspaceManager
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopInputSealerTest {
    @Test
    fun `Windows host relative path는 Jimfs Unix separator로 보존한다`() {
        Jimfs.newFileSystem(Configuration.windows()).use { fileSystem ->
            val relative = fileSystem.getPath("scripts", "nested", "worker.js")

            assertEquals(
                "scripts/nested/worker.js",
                DesktopInputSealer.portableRelativeForTesting(relative),
            )
        }
    }

    @Test
    fun `Chrome URL은 한 번만 다운로드하고 CRX를 workspace에 봉인한다`() {
        val validCrx = validCrx()
        val downloader = CountingDownloader(validCrx.bytes)
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val resolved = DesktopInputSealer(downloader).seal(
                DesktopExtensionInput.ChromeStore(storeUrl(validCrx.extensionId)),
                lease.workspace,
                AnalysisControl(),
            )

            assertEquals(1, downloader.calls.get())
            assertEquals(ExtensionInputKind.CHROME_WEB_STORE, resolved.kind)
            assertSame(
                lease.workspace.root.fileSystem.provider(),
                requireNotNull(resolved.sourceRoot).fileSystem.provider(),
            )
            assertSame(
                lease.workspace.root.fileSystem.provider(),
                requireNotNull(resolved.archivePath).fileSystem.provider(),
            )
            assertEquals(validCrx.extensionId, resolved.expectedExtensionId)
            assertTrue(
                requireNotNull(resolved.sourceRoot)
                    .resolve("manifest.json")
                    .readText()
                    .contains("Fixture"),
            )
        }
    }

    @Test
    fun `Chrome downloader buffer가 바뀌어도 봉인된 CRX 결과는 변하지 않는다`() {
        val validCrx = validCrx()
        val sharedBuffer = validCrx.bytes.copyOf()
        val expected = sharedBuffer.copyOf()
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val resolved = DesktopInputSealer(
                ChromeCrxDownloader { sharedBuffer },
            ).seal(
                DesktopExtensionInput.ChromeStore(storeUrl(validCrx.extensionId)),
                lease.workspace,
                AnalysisControl(),
            )
            sharedBuffer.fill(0)

            assertContentEquals(expected, requireNotNull(resolved.crxBytes))
        }
    }

    @Test
    fun `로컬 ZIP과 CRX는 호스트 파일을 한 번 봉인한 뒤 Jimfs 사본만 사용한다`() {
        val source = extensionSource()
        val zip = Files.createTempFile("desktop-sealer-", ".zip")
        Files.write(zip, ExtensionZip.createBytes(source))
        val crx = validCrx(source)
        val crxPath = Files.createTempFile("desktop-sealer-", ".crx")
        Files.write(crxPath, crx.bytes)
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val sealer = DesktopInputSealer(CountingDownloader(byteArrayOf()))
            val zipResolved = sealer.seal(
                DesktopExtensionInput.Local(zip),
                lease.workspace,
                AnalysisControl(),
            )
            val crxResolved = sealer.seal(
                DesktopExtensionInput.Local(crxPath),
                lease.workspace,
                AnalysisControl(),
            )
            Files.delete(zip)
            Files.delete(crxPath)

            assertEquals(ExtensionInputKind.ZIP, zipResolved.kind)
            assertEquals(ExtensionInputKind.CRX3, crxResolved.kind)
            assertEquals(crx.extensionId, crxResolved.expectedExtensionId)
            listOf(zipResolved, crxResolved).forEach { resolved ->
                assertSame(
                    lease.workspace.root.fileSystem.provider(),
                    requireNotNull(resolved.archivePath).fileSystem.provider(),
                )
                assertTrue(
                    requireNotNull(resolved.sourceRoot)
                        .resolve("manifest.json")
                        .readText()
                        .contains("Fixture"),
                )
            }
        }
    }

    @Test
    fun `source는 marker를 포함한 canonical ZIP으로 한 번 봉인한다`() {
        val source = extensionSource()
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val resolved = DesktopInputSealer(CountingDownloader(byteArrayOf())).seal(
                DesktopExtensionInput.Local(source),
                lease.workspace,
                AnalysisControl(),
            )

            val projectIdentity = assertNotNull(resolved.projectIdentity)
            assertTrue(Regex("[0-9a-f]{32}").matches(projectIdentity))
            assertEquals(ExtensionInputKind.SOURCE_DIRECTORY, resolved.kind)
            assertEquals(
                "sixbridge-project-v1:$projectIdentity",
                requireNotNull(resolved.sourceRoot)
                    .resolve(".sixbridge-project-id")
                    .readText(),
            )
            val archiveNames = ZipInputStream(
                Files.newInputStream(requireNotNull(resolved.archivePath)),
            ).use { zip ->
                generateSequence { zip.nextEntry }.map { it.name }.toList()
            }
            assertEquals(archiveNames.sorted(), archiveNames)
            assertTrue(".sixbridge-project-id" in archiveNames)
        }
    }

    @Test
    fun `읽는 동안 identity가 바뀐 파일은 부분 snapshot 없이 거부한다`() {
        val source = extensionSource()
        val input = Files.createTempFile("desktop-sealer-secret-", ".zip")
        Files.write(input, ExtensionZip.createBytes(source))
        val originalPath = input.toString()
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val sealer = DesktopInputSealer.testing(
                chromeDownloader = CountingDownloader(byteArrayOf()),
                afterLocalRead = { path ->
                    val replacement = Files.createTempFile(path.parent, "replacement-", ".zip")
                    Files.write(replacement, Files.readAllBytes(path))
                    Files.move(replacement, path, ATOMIC_MOVE, REPLACE_EXISTING)
                },
            )

            val error = assertFailsWith<DesktopInputChangedException> {
                sealer.seal(
                    DesktopExtensionInput.Local(input),
                    lease.workspace,
                    AnalysisControl(),
                )
            }

            assertFalse(error.message.orEmpty().contains(originalPath))
            assertTrue(Files.list(lease.workspace.inputRoot).use { it.findAny().isEmpty })
        }
    }

    @Test
    fun `source 첫 inventory 뒤 파일이 교체되면 두 번째 inventory가 거부한다`() {
        val source = extensionSource()
        val original = source.resolve("background.js")
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val sealer = DesktopInputSealer.testing(
                chromeDownloader = CountingDownloader(byteArrayOf()),
                afterLocalRead = {},
                afterSourceRead = { root ->
                    val replacement = Files.createTempFile(root, "replacement-", ".js")
                    replacement.writeText("changed but same size")
                    Files.move(replacement, original, ATOMIC_MOVE, REPLACE_EXISTING)
                },
            )

            assertFailsWith<DesktopInputChangedException> {
                sealer.seal(
                    DesktopExtensionInput.Local(source),
                    lease.workspace,
                    AnalysisControl(),
                )
            }
            assertTrue(Files.list(lease.workspace.inputRoot).use { it.findAny().isEmpty })
        }
    }

    @Test
    fun `source mtime의 밀리초 이하 변경도 identity 변경으로 거부한다`() {
        val source = extensionSource()
        val target = source.resolve("background.js")
        val originalTime = Files.getLastModifiedTime(target)
        val manager = DesktopWorkspaceManager()

        manager.create().use { lease ->
            val sealer = DesktopInputSealer.testing(
                chromeDownloader = CountingDownloader(byteArrayOf()),
                afterLocalRead = {},
                afterSourceRead = {
                    target.writeText("chrome.runtime.getURL('y')")
                    Files.setLastModifiedTime(
                        target,
                        FileTime.from(originalTime.toInstant().plusNanos(500_000)),
                    )
                },
            )

            assertFailsWith<DesktopInputChangedException> {
                sealer.seal(
                    DesktopExtensionInput.Local(source),
                    lease.workspace,
                    AnalysisControl(),
                )
            }
            assertTrue(Files.list(lease.workspace.inputRoot).use { it.findAny().isEmpty })
        }
    }

    @Test
    fun `source symlink와 duplicate file key는 거부하고 입력 child를 남기지 않는다`() {
        val outside = Files.createTempFile("desktop-sealer-outside-", ".js")
        outside.writeText("outside")
        val linkedSource = extensionSource()
        Files.createSymbolicLink(linkedSource.resolve("linked.js"), outside)
        val hardLinkedSource = extensionSource()
        hardLinkedSource.resolve("first.js").writeText("same inode")
        Files.createLink(
            hardLinkedSource.resolve("second.js"),
            hardLinkedSource.resolve("first.js"),
        )
        val manager = DesktopWorkspaceManager()

        listOf(linkedSource, hardLinkedSource).forEach { input ->
            manager.create().use { lease ->
                assertFailsWith<IllegalArgumentException> {
                    DesktopInputSealer(CountingDownloader(byteArrayOf())).seal(
                        DesktopExtensionInput.Local(input),
                        lease.workspace,
                        AnalysisControl(),
                    )
                }
                assertTrue(Files.list(lease.workspace.inputRoot).use { it.findAny().isEmpty })
            }
        }
    }

    @Test
    fun `source 특수 파일과 group writable entry는 거부한다`() {
        val permissionProbe = extensionSource()
        if (!Files.getFileStore(permissionProbe).supportsFileAttributeView("posix")) return
        val specialSource = extensionSource(Path.of("/tmp"))
        val socketPath = specialSource.resolve("extension.sock")
        val socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        socket.bind(UnixDomainSocketAddress.of(socketPath))
        val writableSource = extensionSource()
        val writable = writableSource.resolve("writable.js")
        writable.writeText("shared")
        val permissions = Files.getPosixFilePermissions(writable).toMutableSet()
        permissions += PosixFilePermission.GROUP_WRITE
        Files.setPosixFilePermissions(writable, permissions)
        val manager = DesktopWorkspaceManager()

        try {
            listOf(specialSource, writableSource).forEach { input ->
                manager.create().use { lease ->
                    assertFailsWith<IllegalArgumentException> {
                        DesktopInputSealer(CountingDownloader(byteArrayOf())).seal(
                            DesktopExtensionInput.Local(input),
                            lease.workspace,
                            AnalysisControl(),
                        )
                    }
                    assertTrue(Files.list(lease.workspace.inputRoot).use {
                        it.findAny().isEmpty
                    })
                }
            }
        } finally {
            socket.close()
        }
    }

    @Test
    fun `archive 크기와 source path depth budget을 초과하면 읽기 전에 거부한다`() {
        val oversized = Files.createTempFile("desktop-sealer-oversized-", ".zip")
        Files.newByteChannel(
            oversized,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(DesktopInputSealer.MAX_ARCHIVE_BYTES)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(1)))
        }
        val deepSource = extensionSource()
        var deep = deepSource
        repeat(DesktopInputSealer.MAX_PATH_DEPTH + 1) { index ->
            deep = Files.createDirectory(deep.resolve("d$index"))
        }
        deep.resolve("too-deep.js").writeText("deep")
        val manager = DesktopWorkspaceManager()

        listOf(oversized, deepSource).forEach { input ->
            manager.create().use { lease ->
                assertFailsWith<IllegalArgumentException> {
                    DesktopInputSealer(CountingDownloader(byteArrayOf())).seal(
                        DesktopExtensionInput.Local(input),
                        lease.workspace,
                        AnalysisControl(),
                    )
                }
                assertTrue(Files.list(lease.workspace.inputRoot).use { it.findAny().isEmpty })
            }
        }
    }

    private fun extensionSource(parent: Path? = null): Path =
        (
            parent?.let { Files.createTempDirectory(it, "six-source-") }
                ?: Files.createTempDirectory("desktop-sealer-source-")
            ).also { source ->
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Fixture","version":"1.0"}""",
            )
            source.resolve("background.js").writeText("chrome.runtime.getURL('x')")
        }

    private fun validCrx(source: Path = extensionSource()) =
        Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
        )

    private fun storeUrl(extensionId: String): String =
        "https://chromewebstore.google.com/detail/fixture/$extensionId"

    private class CountingDownloader(
        private val bytes: ByteArray,
    ) : ChromeCrxDownloader {
        val calls = AtomicInteger()

        override fun download(address: dev.sixbridge.core.input.ChromeWebStoreAddress): ByteArray {
            calls.incrementAndGet()
            return bytes.copyOf()
        }
    }
}
