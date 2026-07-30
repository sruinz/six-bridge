package dev.sixbridge.desktop

import java.nio.file.Path

enum class DesktopHostOs {
    MAC,
    WINDOWS,
}

data class DesktopApplicationPaths(
    val dataRoot: Path,
    val workRoot: Path,
    val draftKeyRoot: Path,
) {
    companion object {
        fun resolve(
            osName: String = System.getProperty("os.name"),
            userHome: String = System.getProperty("user.home"),
            localAppData: String? = System.getenv("LOCALAPPDATA"),
        ): DesktopApplicationPaths {
            val dataRoot = when {
                osName.startsWith("Mac", ignoreCase = true) -> {
                    val home = requireAbsoluteHostPath(userHome, "user.home")
                    home.resolve("Library")
                        .resolve("Application Support")
                        .resolve("SIX Bridge")
                }

                osName.startsWith("Windows", ignoreCase = true) -> {
                    Path.of(validateWindowsAbsolutePath(localAppData))
                        .resolve("SIX Bridge")
                }

                else -> throw IllegalStateException(
                    "지원하지 않는 데스크톱 운영체제입니다: $osName",
                )
            }
            return DesktopApplicationPaths(
                dataRoot = dataRoot,
                workRoot = dataRoot.resolve("work"),
                draftKeyRoot = dataRoot.resolve("draft-signing"),
            )
        }

        private fun requireAbsoluteHostPath(value: String?, label: String): Path {
            val text = value?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("$label 경로가 비어 있습니다.")
            val path = runCatching { Path.of(text) }.getOrElse {
                throw IllegalStateException("$label 경로가 올바르지 않습니다.", it)
            }
            if (!path.isAbsolute || path.normalize() != path) {
                throw IllegalStateException("$label 경로는 정규화된 절대 경로여야 합니다.")
            }
            return path
        }

        private fun validateWindowsAbsolutePath(value: String?): String {
            val text = value?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("LOCALAPPDATA 경로가 비어 있습니다.")
            val normalized = text.replace('\\', '/')
            if (!WINDOWS_DRIVE_ABSOLUTE.matches(normalized)) {
                throw IllegalStateException(
                    "LOCALAPPDATA는 drive 기반 절대 경로여야 합니다.",
                )
            }
            val segments = normalized.drop(3).split('/')
            if (segments.any { segment ->
                    segment.isEmpty() ||
                        segment == "." ||
                        segment == ".." ||
                        WINDOWS_INVALID_SEGMENT.containsMatchIn(segment) ||
                        segment.endsWith('.') ||
                        segment.endsWith(' ')
                }
            ) {
                throw IllegalStateException(
                    "LOCALAPPDATA 경로에 허용되지 않는 segment가 있습니다.",
                )
            }
            return normalized
        }

        private val WINDOWS_DRIVE_ABSOLUTE = Regex("^[A-Za-z]:/[^/].*")
        private val WINDOWS_INVALID_SEGMENT = Regex("""[<>:"|?*]""")
    }
}
