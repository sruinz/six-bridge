package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.wrapper.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

data class DeviceInstallResult(
    val serial: String,
    val packageName: String,
    val output: String,
)

data class DeviceSmokeResult(
    val serial: String,
    val packageName: String,
    val installed: Boolean,
    val discoveryActivityResolved: Boolean,
    val providerReadable: Boolean,
    val evidenceLog: Path,
)

class AdbDeviceClient(
    private val adb: String = "adb",
) {
    fun install(bundle: Path, serial: String): DeviceInstallResult {
        val contents = SixBundle.read(bundle)
        val packageName = trustedPackage(contents)
        val apk = Files.createTempFile("sixbridge-install-", ".apk")
        apk.writeBytes(contents.wrapperApk)
        BuildArtifactVerifier().verifyWrapper(apk, contents)
        val result = adb(serial, "install", "-r", apk.toString())
        require("Success" in result) { "ADB 설치 결과를 확인할 수 없습니다: $result" }
        return DeviceInstallResult(
            serial = serial,
            packageName = packageName,
            output = result.trim(),
        )
    }

    fun smoke(
        bundle: Path,
        serial: String,
        evidenceDirectory: Path,
    ): DeviceSmokeResult {
        val contents = SixBundle.read(bundle)
        val packageName = trustedPackage(contents)
        val packagePath = runCatching {
            adb(serial, "shell", "pm", "path", packageName)
        }.getOrDefault("")
        val resolution = runCatching {
            adb(
                serial,
                "shell",
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.VIEW",
                "-c",
                "android.intent.category.BROWSABLE",
                "-d",
                "six://ex",
                "-p",
                packageName,
            )
        }.getOrDefault("")
        val provider = runCatching {
            providerSummary(
                serial,
                "content://$packageName.contentprovider",
            )
        }.getOrElse { ProviderSummary.empty() }
        val addOnsLaunch = runCatching {
            adb(
                serial,
                "shell",
                "am",
                "start",
                "-a",
                "android.intent.action.VIEW",
                "-d",
                "internet://extension",
                "-p",
                "com.sec.android.app.sbrowser",
            )
        }.getOrElse { it.message.orEmpty() }
        val packageDump = runCatching {
            adb(serial, "shell", "dumpsys", "package", packageName)
        }.getOrElse { it.message.orEmpty() }
        val browserDump = runCatching {
            adb(
                serial,
                "shell",
                "dumpsys",
                "package",
                "com.sec.android.app.sbrowser",
            )
        }.getOrElse { it.message.orEmpty() }
        val logcat = runCatching {
            adb(serial, "logcat", "-d", "-t", "1000")
        }.getOrElse { it.message.orEmpty() }
        Files.createDirectories(evidenceDirectory)
        val log = evidenceDirectory.resolve(
            "${Instant.now().epochSecond}-${contents.extension.extensionId}.log",
        )
        log.writeText(
            """
            # SIX Bridge device smoke evidence
            serial=$serial
            package=$packageName
            packagePath=${packagePath.trim()}
            discovery=${resolution.trim()}
            providerMagic=${provider.magic}
            providerBytes=${provider.byteLength}
            providerSha256=${provider.sha256}
            addOnsLaunch=${addOnsLaunch.trim()}

            ## wrapper package
            $packageDump

            ## Samsung Internet package
            $browserDump

            ## filtered logcat
            ${filterLogcat(logcat, packageName, contents.extension.extensionId)}
            """.trimIndent(),
        )
        return DeviceSmokeResult(
            serial = serial,
            packageName = packageName,
            installed = packagePath.lineSequence().any { it.startsWith("package:") },
            discoveryActivityResolved = packageName in resolution,
            providerReadable = provider.readable,
            evidenceLog = log,
        )
    }

    private fun trustedPackage(contents: dev.sixbridge.core.bundle.SixBundleContents): String {
        val extensionId = contents.extension.extensionId
        require(EXTENSION_ID.matches(extensionId)) {
            "SixBundle extension ID가 올바르지 않습니다."
        }
        val derived = "dev.sixbridge.ext.$extensionId"
        require(contents.extension.wrapperPackage == derived) {
            "SixBundle wrapper package가 extension ID와 일치하지 않습니다."
        }
        return derived
    }

    private fun adb(serial: String, vararg arguments: String): String =
        ProcessRunner.run(listOf(adb, "-s", serial) + arguments).stdout

    private fun providerSummary(serial: String, uri: String): ProviderSummary {
        val output = Files.createTempFile("sixbridge-provider-", ".bin")
        return try {
            ProcessRunner.runToFile(
                command = listOf(
                    adb,
                    "-s",
                    serial,
                    "shell",
                    "content",
                    "read",
                    "--uri",
                    uri,
                ),
                output = output,
                maxStdoutBytes = MAX_PROVIDER_BYTES,
            )
            val digest = MessageDigest.getInstance("SHA-256")
            val magic = ByteArray(PROVIDER_MAGIC.size)
            var magicLength = 0
            var byteLength = 0L
            Files.newInputStream(output).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (magicLength < magic.size) {
                        val copyLength = minOf(count, magic.size - magicLength)
                        buffer.copyInto(
                            magic,
                            destinationOffset = magicLength,
                            startIndex = 0,
                            endIndex = copyLength,
                        )
                        magicLength += copyLength
                    }
                    digest.update(buffer, 0, count)
                    byteLength += count
                }
            }
            val magicBytes = magic.copyOf(magicLength)
            ProviderSummary(
                magic = printableMagic(magicBytes),
                byteLength = byteLength,
                sha256 = digest.digest().toHex(),
                readable = magicBytes.contentEquals(PROVIDER_MAGIC),
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun printableMagic(bytes: ByteArray): String =
        if (bytes.all { it.toInt() in 0x20..0x7e }) {
            bytes.toString(Charsets.US_ASCII)
        } else {
            "hex:${bytes.toHex()}"
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun filterLogcat(logcat: String, packageName: String, extensionId: String): String {
        val needles = listOf(
            packageName,
            extensionId,
            "SixManager",
            "SixPkg",
            "TerraceExtensions",
            "dev_ext",
        )
        return logcat.lineSequence()
            .filter { line -> needles.any { it in line } }
            .joinToString("\n")
    }

    companion object {
        private val EXTENSION_ID = Regex("[a-p]{32}")
        private val PROVIDER_MAGIC = "Cr24".toByteArray(Charsets.US_ASCII)
        private const val MAX_PROVIDER_BYTES = 128L * 1024 * 1024
    }

    private data class ProviderSummary(
        val magic: String,
        val byteLength: Long,
        val sha256: String,
        val readable: Boolean,
    ) {
        companion object {
            fun empty(): ProviderSummary = ProviderSummary(
                magic = "",
                byteLength = 0,
                sha256 = MessageDigest.getInstance("SHA-256")
                    .digest()
                    .joinToString("") { "%02x".format(it) },
                readable = false,
            )
        }
    }
}
