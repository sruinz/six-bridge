package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPairGenerator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionInputResolverTest {
    @Test
    fun `같은 추출 root는 첫 추출 실패 뒤에도 재사용하지 않는다`() {
        val source = Files.createTempDirectory("six-input-reused-root-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Reused root","version":"1"}""",
        )
        val validZip = source.parent.resolve("reused-root-valid.zip")
        ExtensionZip.create(source, validZip)
        val invalidZip = source.parent.resolve("reused-root-invalid.zip")
        ZipOutputStream(Files.newOutputStream(invalidZip)).use { output ->
            output.putNextEntry(ZipEntry("../manifest.json"))
            output.write("""{"name":"escape"}""".toByteArray())
            output.closeEntry()
        }
        val root = Files.createTempDirectory("six-input-reused-root")
        val resolver = ExtensionInputResolver(extractionRootFactory = { root })

        val first = assertFailsWith<IllegalArgumentException> {
            resolver.resolve(invalidZip.toString())
        }
        val second = assertFailsWith<IllegalArgumentException> {
            resolver.resolve(validZip.toString())
        }

        assertTrue(first.message.orEmpty().contains("ZIP 경로 탈출"))
        assertTrue(Files.list(root).use { it.findAny().isEmpty })
        assertTrue(second.message.orEmpty().contains("이미 사용한"))
    }

    @Test
    fun `검증 직후 교체된 추출 root와 원래 root를 수정하지 않는다`() {
        val source = Files.createTempDirectory("six-input-replaced-root-source")
        assumeTrue(
            supportsSecureDirectoryStream(requireNotNull(source.parent)),
            "SecureDirectoryStream을 제공하는 파일시스템에서 실행하는 보안 게이트",
        )
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Replaced root","version":"1"}""",
        )
        val zip = source.parent.resolve("replaced-root.zip")
        ExtensionZip.create(source, zip)
        val root = Files.createTempDirectory("six-input-replaced-root")
        val moved = root.resolveSibling("${root.fileName}-moved")
        @Suppress("DEPRECATION")
        val resolver = ExtensionInputResolver(
            extractionRootFactory = { root },
            extractionRootValidatedHook = { validated ->
                Files.move(validated, moved, ATOMIC_MOVE)
                Files.createDirectory(validated)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(zip.toString())
        }

        assertTrue(Files.list(root).use { it.findAny().isEmpty })
        assertTrue(Files.list(moved).use { it.findAny().isEmpty })
    }

    @Test
    fun `검증 직후 삽입된 sentinel을 수정하지 않고 추출을 거부한다`() {
        val source = Files.createTempDirectory("six-input-sentinel-source")
        assumeTrue(
            supportsSecureDirectoryStream(requireNotNull(source.parent)),
            "SecureDirectoryStream을 제공하는 파일시스템에서 실행하는 보안 게이트",
        )
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Sentinel","version":"1"}""",
        )
        val zip = source.parent.resolve("sentinel.zip")
        ExtensionZip.create(source, zip)
        val root = Files.createTempDirectory("six-input-sentinel-root")
        val sentinel = root.resolve("sentinel.txt")
        val resolver = ExtensionInputResolver.withExtractionRootValidatedHook(
            extractionRootFactory = { root },
            extractionRootValidatedHook = {
                sentinel.writeText("keep")
            },
        )

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(zip.toString())
        }

        assertEquals("keep", Files.readString(sentinel))
        assertEquals(
            listOf("sentinel.txt"),
            Files.list(root).use { paths ->
                paths.map { it.fileName.toString() }.sorted().toList()
            },
        )
    }

    @Test
    fun `주입된 추출 root factory를 ZIP CRX 스토어 입력마다 사용한다`() {
        val source = Files.createTempDirectory("six-input-root-source")
        assumeTrue(
            supportsSecureDirectoryStream(requireNotNull(source.parent)),
            "SecureDirectoryStream을 제공하는 파일시스템에서 실행하는 통합 게이트",
        )
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Root","version":"1"}""",
        )
        val zip = source.parent.resolve("root.zip")
        ExtensionZip.create(source, zip)
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
        )
        val crx = source.parent.resolve("root.crx")
        crx.writeBytes(packed.bytes)
        val roots = mutableListOf<java.nio.file.Path>()
        val factory = {
            Files.createTempDirectory("six-input-root-factory").also(roots::add)
        }
        val resolver = ExtensionInputResolver(
            chromeDownloader = ChromeCrxDownloader { packed.bytes },
            extractionRootFactory = factory,
        )

        val inputs = listOf(
            zip.toString(),
            crx.toString(),
            "https://chromewebstore.google.com/detail/name/${packed.extensionId}",
        )
        val resolved = inputs.map(resolver::resolve)

        assertEquals(3, roots.size)
        resolved.zip(roots).forEach { (result, root) ->
            assertTrue(Files.isDirectory(root, NOFOLLOW_LINKS))
            assertFalse(Files.isSymbolicLink(root))
            assertTrue(requireNotNull(result.sourceRoot).startsWith(root))
        }
        assertEquals(3, roots.map(::fileKey).toSet().size)
    }

    @Test
    fun `추출 root factory는 실제 비어 있는 일반 디렉터리만 허용한다`() {
        val source = Files.createTempDirectory("six-input-invalid-root-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Invalid root","version":"1"}""",
        )
        val zip = source.parent.resolve("invalid-root.zip")
        ExtensionZip.create(source, zip)

        val regularFile = Files.createTempFile("six-input-root-file", ".tmp")
        assertFailsWith<IllegalArgumentException> {
            ExtensionInputResolver(extractionRootFactory = { regularFile }).resolve(zip.toString())
        }
        val nonEmpty = Files.createTempDirectory("six-input-root-nonempty")
        nonEmpty.resolve("existing").writeText("not empty")
        assertFailsWith<IllegalArgumentException> {
            ExtensionInputResolver(extractionRootFactory = { nonEmpty }).resolve(zip.toString())
        }
        val target = Files.createTempDirectory("six-input-root-symlink-target")
        val symlink = target.parent.resolve("six-input-root-symlink-${System.nanoTime()}")
        Files.createSymbolicLink(symlink, target)
        assertFailsWith<IllegalArgumentException> {
            ExtensionInputResolver(extractionRootFactory = { symlink }).resolve(zip.toString())
        }
    }

    @Test
    fun `accepts directories zip files and CRX3 files`() {
        val source = Files.createTempDirectory("six-input")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Input","version":"1"}""",
        )
        val zip = source.parent.resolve("input.zip")
        ExtensionZip.create(source, zip)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crxBytes = Crx3.pack(source, keyPair).bytes
        val crx = source.parent.resolve("input.crx")
        crx.writeBytes(crxBytes)
        val resolver = ExtensionInputResolver()

        val directoryInput = resolver.resolve(source.toString())
        val zipInput = resolver.resolve(zip.toString())
        val crxInput = resolver.resolve(crx.toString())

        assertEquals(ExtensionInputKind.SOURCE_DIRECTORY, directoryInput.kind)
        assertEquals(ExtensionInputKind.ZIP, zipInput.kind)
        assertTrue(zipInput.sourceRoot?.resolve("manifest.json")?.toFile()?.isFile == true)
        assertEquals(ExtensionInputKind.CRX3, crxInput.kind)
        assertContentEquals(crxBytes, crxInput.crxBytes)
    }

    @Test
    fun `injected downloader receives parsed Chrome ID and rejects mismatched CRX`() {
        val source = Files.createTempDirectory("six-input-store")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Store","version":"1"}""",
        )
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
        val resolver = ExtensionInputResolver(
            ChromeCrxDownloader { address ->
                packed.bytes.also {
                    assertEquals(packed.extensionId, address.extensionId)
                }
            },
        )

        val chrome = resolver.resolve(
            "https://chromewebstore.google.com/detail/video-download-to-nas/" +
                "${packed.extensionId}?hl=ko",
        )

        assertEquals(ExtensionInputKind.CHROME_WEB_STORE, chrome.kind)
        assertEquals(packed.extensionId, chrome.expectedExtensionId)
        assertContentEquals(packed.bytes, chrome.crxBytes)

        val mismatch = ExtensionInputResolver(
            ChromeCrxDownloader {
                val otherSource = Files.createTempDirectory("six-input-other-store")
                otherSource.resolve("manifest.json").writeText(
                    """{"manifest_version":3,"name":"Other","version":"1"}""",
                )
                Crx3.pack(
                    otherSource,
                    KeyPairGenerator.getInstance("RSA")
                        .apply { initialize(2048) }
                        .generateKeyPair(),
                ).bytes
            },
        )
        assertFailsWith<UnsupportedInputException> {
            mismatch.resolve(
                "https://chromewebstore.google.com/detail/video-download-to-nas/" +
                    "${packed.extensionId}?hl=ko",
            )
        }
        assertFailsWith<UnsupportedInputException> {
            resolver.resolve("https://microsoftedge.microsoft.com/addons/detail/example/abcdefghijkl")
        }
    }

    @Test
    fun `downloader가 없으면 URL만 거부하고 로컬 ZIP과 CRX는 처리한다`() {
        val source = Files.createTempDirectory("six-input-local-only")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Local","version":"1"}""",
        )
        val zip = source.parent.resolve("local-only.zip")
        ExtensionZip.create(source, zip)
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
        val crx = source.parent.resolve("local-only.crx")
        crx.writeBytes(packed.bytes)
        val resolver = ExtensionInputResolver()

        assertEquals(ExtensionInputKind.ZIP, resolver.resolve(zip.toString()).kind)
        assertEquals(ExtensionInputKind.CRX3, resolver.resolve(crx.toString()).kind)
        assertFailsWith<UnsupportedInputException> {
            resolver.resolve(
                "https://chromewebstore.google.com/detail/name/${packed.extensionId}",
            )
        }
    }

    @Test
    fun `rejects zip entries whose compression ratio exceeds the extraction limit`() {
        val zip = Files.createTempFile("six-zip-bomb", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(ByteArray(1024 * 1024))
            output.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> {
            ExtensionInputResolver().resolve(zip.toString())
        }
    }

    @Test
    fun `rejects high compression byte array extraction`() {
        val zip = Files.createTempFile("six-byte-zip-bomb", ".zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(ByteArray(1024 * 1024))
            output.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> {
            ExtensionZip.extract(
                Files.readAllBytes(zip),
                Files.createTempDirectory("six-byte-zip-output"),
            )
        }
    }

    private fun supportsSecureDirectoryStream(directory: java.nio.file.Path): Boolean =
        Files.newDirectoryStream(directory).use { it is SecureDirectoryStream<*> }

    private fun fileKey(path: java.nio.file.Path): Any = requireNotNull(
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey(),
    )

}
