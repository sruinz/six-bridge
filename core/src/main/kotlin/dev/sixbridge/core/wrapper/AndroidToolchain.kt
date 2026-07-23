package dev.sixbridge.core.wrapper

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class AndroidToolchain(
    val sdkRoot: Path,
    val androidJar: Path,
    val aapt2: Path,
    val d8: Path,
    val zipAlign: Path,
    val apkSigner: Path,
    val apkAnalyzer: Path,
    val javac: Path,
    val keytool: Path,
) {
    companion object {
        fun locateOrNull(explicitSdkRoot: Path? = null): AndroidToolchain? =
            runCatching { locate(explicitSdkRoot) }.getOrNull()

        fun locate(explicitSdkRoot: Path? = null): AndroidToolchain {
            val candidates = buildList {
                if (explicitSdkRoot != null) add(explicitSdkRoot)
                System.getenv("ANDROID_SDK_ROOT")?.let { add(Path.of(it)) }
                System.getenv("ANDROID_HOME")?.let { add(Path.of(it)) }
                add(Path.of("/opt/homebrew/share/android-commandlinetools"))
                add(Path.of(System.getProperty("user.home"), "Library/Android/sdk"))
            }.distinct()
            val sdk = candidates.firstOrNull { root ->
                root.resolve("platforms/android-36/android.jar").exists() &&
                    root.resolve("build-tools").isDirectory()
            } ?: throw IllegalStateException(
                "Android SDK 36을 찾지 못했습니다. ANDROID_SDK_ROOT를 설정하세요.",
            )
            val buildTools = Files.list(sdk.resolve("build-tools")).use { paths ->
                paths.filter { it.isDirectory() }
                    .filter { it.resolve("aapt2").exists() && it.resolve("apksigner").exists() }
                    .max(Comparator { left, right -> compareVersions(left, right) })
                    .orElseThrow { IllegalStateException("Android build-tools를 찾지 못했습니다.") }
            }
            val javaBin = Path.of(System.getProperty("java.home"), "bin")
            val result = AndroidToolchain(
                sdkRoot = sdk,
                androidJar = sdk.resolve("platforms/android-36/android.jar"),
                aapt2 = buildTools.resolve("aapt2"),
                d8 = buildTools.resolve("d8"),
                zipAlign = buildTools.resolve("zipalign"),
                apkSigner = buildTools.resolve("apksigner"),
                apkAnalyzer = sdk.resolve("cmdline-tools/latest/bin/apkanalyzer"),
                javac = javaBin.resolve("javac"),
                keytool = javaBin.resolve("keytool"),
            )
            listOf(
                result.androidJar,
                result.aapt2,
                result.d8,
                result.zipAlign,
                result.apkSigner,
                result.apkAnalyzer,
                result.javac,
                result.keytool,
            ).forEach { path ->
                require(path.exists()) { "필수 Android/Java 도구가 없습니다: $path" }
            }
            return result
        }

        private fun compareVersions(left: Path, right: Path): Int {
            val leftParts = versionParts(left)
            val rightParts = versionParts(right)
            repeat(maxOf(leftParts.size, rightParts.size)) { index ->
                val compared = (leftParts.getOrNull(index) ?: 0)
                    .compareTo(rightParts.getOrNull(index) ?: 0)
                if (compared != 0) return compared
            }
            return 0
        }

        private fun versionParts(path: Path): List<Int> =
            path.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
    }
}
