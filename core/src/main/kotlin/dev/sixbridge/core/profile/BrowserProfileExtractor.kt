package dev.sixbridge.core.profile

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class BrowserProfileExtractor {
    fun extract(browserApk: Path): BrowserProfile {
        val scan = ApkScan()
        ZipFile(browserApk.toFile()).use { apk ->
            val entries = apk.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                when {
                    entry.name.endsWith(".dex") -> apk.getInputStream(entry).use(scan::scanContractData)
                    entry.name.endsWith("/libterrace.so") ->
                        apk.getInputStream(entry).use(scan::scanTerraceLibrary)
                }
            }
        }

        val manifestVersion = readManifestVersion(browserApk)
        val filenameVersion = versionFromFilename(browserApk.fileName.toString())
        val filenameVersionCode = versionCodeFromFilename(browserApk.fileName.toString())
        val versionName = manifestVersion?.first
            ?: filenameVersion.takeUnless { it == "unknown" }
            ?: scan.versionName
            ?: "unknown"
        val versionCode = manifestVersion?.second
            ?: filenameVersionCode.takeUnless { it == 0L }
            ?: scan.versionCode
            ?: 0

        val majorVersion = versionName.substringBefore('.').toIntOrNull() ?: 0
        val differences = if (
            majorVersion == BrowserProfile.SUPPORTED_MAJOR_VERSION &&
            versionName != BrowserProfile.BASELINE_VERSION
        ) {
            buildList {
                (BrowserProfile.BASELINE_APIS - scan.namespaces).sorted().forEach {
                    add("missing_api:$it")
                }
                (scan.namespaces - BrowserProfile.BASELINE_APIS).sorted().forEach {
                    add("added_api:$it")
                }
            }
        } else {
            emptyList()
        }

        return BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = versionName,
            versionCode = versionCode,
            majorVersion = majorVersion,
            supportedApis = (
                scan.namespaces intersect BrowserProfile.POC_SUPPORTED_APIS
                ).toSortedSet(),
            detectedApis = scan.namespaces.toSortedSet(),
            wrapperContract = WrapperContract(
                discoveryUri = "six://ex" in scan.markers,
                extensionMetadata = "extension-id" in scan.markers,
                contentProvider = ".contentprovider" in scan.markers,
                devExtensionPath = "dev_ext" in scan.markers,
            ),
            sourceApkSha256 = sha256(browserApk),
            sourceApkSignerSha256 = readSignerSha256(browserApk).orEmpty(),
            profileDifferences = differences,
        )
    }

    private fun versionFromFilename(name: String): String =
        VERSION_NAME_REGEX.find(name)?.groupValues?.get(1) ?: "unknown"

    private fun versionCodeFromFilename(name: String): Long =
        VERSION_CODE_FILENAME_REGEX.find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0

    private fun readManifestVersion(browserApk: Path): Pair<String, Long>? {
        val analyzer = apkAnalyzerCandidates().firstOrNull { it.exists() } ?: return null
        val versionName = runAnalyzer(analyzer, "version-name", browserApk) ?: return null
        val versionCode = runAnalyzer(analyzer, "version-code", browserApk)
            ?.toLongOrNull()
            ?: return null
        return versionName to versionCode
    }

    private fun runAnalyzer(analyzer: Path, operation: String, browserApk: Path): String? =
        runCatching {
            val process = ProcessBuilder(
                analyzer.toString(),
                "manifest",
                operation,
                browserApk.toString(),
            ).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

    private fun readSignerSha256(browserApk: Path): String? {
        val apkSigner = apkSignerCandidates().firstOrNull { it.exists() } ?: return null
        return runCatching {
            val process = ProcessBuilder(
                apkSigner.toString(),
                "verify",
                "--print-certs",
                browserApk.toString(),
            ).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) return null
            SIGNER_SHA_REGEX.find(process.inputStream.bufferedReader().readText())
                ?.groupValues
                ?.get(1)
                ?.replace(":", "")
                ?.lowercase()
        }.getOrNull()
    }

    private fun apkAnalyzerCandidates(): List<Path> = buildList {
        System.getenv("ANDROID_SDK_ROOT")?.let { root ->
            add(Path.of(root, "cmdline-tools/latest/bin/apkanalyzer"))
        }
        System.getenv("ANDROID_HOME")?.let { root ->
            add(Path.of(root, "cmdline-tools/latest/bin/apkanalyzer"))
        }
        add(
            Path.of(
                "/opt/homebrew/share/android-commandlinetools/" +
                    "cmdline-tools/latest/bin/apkanalyzer",
            ),
        )
        add(
            Path.of(
                System.getProperty("user.home"),
                "Library/Android/sdk/cmdline-tools/latest/bin/apkanalyzer",
            ),
        )
    }.distinct()

    private fun apkSignerCandidates(): List<Path> = buildList {
        listOfNotNull(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            "/opt/homebrew/share/android-commandlinetools",
            Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString(),
        ).distinct().forEach { rootValue ->
            val buildTools = Path.of(rootValue, "build-tools")
            if (Files.isDirectory(buildTools)) {
                Files.list(buildTools).use { versions ->
                    versions.sorted(Comparator.reverseOrder()).forEach { version ->
                        add(version.resolve("apksigner"))
                    }
                }
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class ApkScan {
        val markers = mutableSetOf<String>()
        val namespaces = mutableSetOf<String>()
        var versionName: String? = null
        var versionCode: Long? = null

        fun scanContractData(input: InputStream) {
            scanText(input) { text ->
                REQUIRED_MARKERS.filterTo(markers) { it in text }
                if (versionName == null) {
                    versionName = VERSION_NAME_REGEX.find(text)?.groupValues?.get(1)
                }
                if (versionCode == null) {
                    versionCode = VERSION_CODE_TEXT_REGEX.find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                }
            }
        }

        fun scanTerraceLibrary(input: InputStream) {
            scanText(input) { text ->
                NAMESPACE_REGEX.findAll(text).forEach { match ->
                    namespaces += match.groupValues[1]
                }
            }
        }

        private fun scanText(input: InputStream, consume: (String) -> Unit) {
            val buffer = ByteArray(64 * 1024)
            var tail = ""
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val current = tail + buffer.decodeToString(endIndex = read)
                consume(current)
                tail = current.takeLast(SCAN_OVERLAP)
            }
        }
    }

    companion object {
        private const val SCAN_OVERLAP = 512
        private val REQUIRED_MARKERS = setOf(
            "six://ex",
            "extension-id",
            ".contentprovider",
            "dev_ext",
        )
        private val NAMESPACE_REGEX =
            Regex("""["']namespace["']\s*:\s*["']([A-Za-z][A-Za-z0-9_.]*)["']""")
        private val VERSION_NAME_REGEX =
            Regex("""(?:versionName[\s:=_-]*)?(\d{2}\.\d+\.\d+\.\d+)""")
        private val VERSION_CODE_TEXT_REGEX =
            Regex("""versionCode[\s:=_-]*(\d{6,})""")
        private val VERSION_CODE_FILENAME_REGEX =
            Regex("""-(\d{6,})_""")
        private val SIGNER_SHA_REGEX =
            Regex("""Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")
    }
}
