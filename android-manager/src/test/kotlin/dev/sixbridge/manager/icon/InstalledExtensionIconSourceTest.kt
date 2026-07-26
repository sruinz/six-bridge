package dev.sixbridge.manager.icon

import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InstalledExtensionIconSourceTest {
    @Test
    fun `검증된 wrapper package의 정확한 provider root만 연다`() = runTest {
        val opened = mutableListOf<String>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { uri ->
                opened += uri
                "Cr24fixture".byteInputStream()
            },
        )

        assertContentEquals(PNG, source.load(PACKAGE))
        assertEquals(
            listOf("content://$PACKAGE.contentprovider"),
            opened,
        )
    }

    @Test
    fun `검증된 wrapper package 형식이 아니면 provider를 열지 않는다`() = runTest {
        val opened = mutableListOf<String>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { uri ->
                opened += uri
                "unused".byteInputStream()
            },
        )

        listOf(
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmn0p",
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnoq",
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmno",
            "other.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
        ).forEach { packageName ->
            assertNull(source.load(packageName))
        }

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `provider stream이 없거나 열기 예외가 나면 icon 없음으로 격리한다`() = runTest {
        val missing = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { null },
        )
        val failed = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { throw IllegalStateException("provider unavailable") },
        )

        assertNull(missing.load(PACKAGE))
        assertNull(failed.load(PACKAGE))
    }

    @Test
    fun `extractor 예외가 나면 icon 없음으로 격리한다`() = runTest {
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { throw IllegalArgumentException("invalid CRX") },
            openStream = { "Cr24fixture".byteInputStream() },
        )

        assertNull(source.load(PACKAGE))
    }

    @Test
    fun `provider와 extractor cancellation은 재전파한다`() = runTest {
        val providerCancellation = CancellationException("provider cancelled")
        val provider = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { throw providerCancellation },
        )
        val extractorCancellation = CancellationException("extractor cancelled")
        val extractor = ContentProviderInstalledExtensionIconSource(
            extract = { throw extractorCancellation },
            openStream = { "Cr24fixture".byteInputStream() },
        )

        assertFailsWith<CancellationException> { provider.load(PACKAGE) }
        assertFailsWith<CancellationException> { extractor.load(PACKAGE) }
    }

    private companion object {
        const val PACKAGE =
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }
}
