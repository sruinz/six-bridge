package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
    fun `주입된 추출 root factory를 ZIP CRX 스토어 입력마다 사용한다`() {
        val source = Files.createTempDirectory("six-input-root-source")
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
        val resolved = inputs.map { input ->
            runCatching { resolver.resolve(input) }
        }

        assertEquals(3, roots.size)
        resolved.zip(roots).forEach { (result, root) ->
            assertTrue(Files.isDirectory(root, NOFOLLOW_LINKS))
            assertFalse(Files.isSymbolicLink(root))
            result.getOrNull()?.let { input ->
                assertTrue(requireNotNull(input.sourceRoot).startsWith(root))
            } ?: assertTrue(
                requireNotNull(result.exceptionOrNull()).message.orEmpty().contains(
                    "파일시스템이 안전한 ZIP 압축 해제를 지원하지 않습니다",
                ),
            )
        }
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

}
