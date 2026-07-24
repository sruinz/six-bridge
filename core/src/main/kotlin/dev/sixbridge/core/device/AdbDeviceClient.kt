package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.wrapper.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
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
        BuildArtifactVerifier().verifyWrapper(apk, contents.extension)
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
            adb(
                serial,
                "shell",
                "content",
                "read",
                "--uri",
                "content://$packageName.contentprovider",
            )
        }.getOrDefault("")
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
            provider=${provider.trim()}
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
            providerReadable = provider.take(4) == "Cr24",
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
    }
}
