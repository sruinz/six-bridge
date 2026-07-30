package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes

class SecureConversionRoot private constructor(
    val path: Path,
) {
    companion object {
        fun verify(path: Path): SecureConversionRoot {
            val normalized = path.toAbsolutePath().normalize()
            val before = attributes(normalized)
            require(
                before.isDirectory && !before.isSymbolicLink && before.fileKey() != null,
            ) {
                "secure conversion root는 실제 디렉터리여야 합니다: $normalized"
            }
            val stream = Files.newDirectoryStream(normalized)
            try {
                require(stream is SecureDirectoryStream<Path>) {
                    "secure conversion root는 SecureDirectoryStream을 제공해야 합니다: $normalized"
                }
                val after = attributes(normalized)
                require(
                    after.isDirectory &&
                        !after.isSymbolicLink &&
                        after.fileKey() == before.fileKey(),
                ) {
                    "secure conversion root가 검증 중 변경되었습니다: $normalized"
                }
            } finally {
                stream.close()
            }
            return SecureConversionRoot(normalized)
        }

        private fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    }
}
