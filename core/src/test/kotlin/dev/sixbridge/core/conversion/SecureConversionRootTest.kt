package dev.sixbridge.core.conversion

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Feature
import com.google.common.jimfs.Jimfs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SecureConversionRootTest {
    @Test
    fun `SDS를 제공하지 않는 root는 거부한다`() {
        val configuration = Configuration.unix().toBuilder()
            .setSupportedFeatures(
                Feature.LINKS,
                Feature.SYMBOLIC_LINKS,
                Feature.FILE_CHANNEL,
            )
            .build()
        Jimfs.newFileSystem(configuration).use { fileSystem ->
            val root = Files.createDirectory(fileSystem.getPath("/secure-root"))

            assertFailsWith<IllegalArgumentException> {
                SecureConversionRoot.verify(root)
            }
        }
    }

    @Test
    fun `SDS를 제공하는 실제 빈 directory는 검증한다`() {
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val root = Files.createDirectory(fileSystem.getPath("/secure-root"))

            assertSame(
                fileSystem.provider(),
                SecureConversionRoot.verify(root).path.fileSystem.provider(),
            )
        }
    }
}
