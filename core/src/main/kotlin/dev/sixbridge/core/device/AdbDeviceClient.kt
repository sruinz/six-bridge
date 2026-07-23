package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
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
        verifyWrapperApk(apk, contents.extension)
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

    private fun verifyWrapperApk(apk: Path, descriptor: ExtensionDescriptor) {
        val toolchain = AndroidToolchain.locate()
        verifyEmbeddedCrx(apk, descriptor)
        val badging = ProcessRunner.run(
            listOf(toolchain.aapt2.toString(), "dump", "badging", apk.toString()),
        ).stdout
        val packageMatch = PACKAGE_BADGING.find(badging)
            ?: throw IllegalArgumentException("wrapper APK package 정보를 읽을 수 없습니다.")
        val actualPackage = packageMatch.groupValues[1]
        val actualVersionCode = packageMatch.groupValues[2].toIntOrNull()
            ?: throw IllegalArgumentException("wrapper APK versionCode를 읽을 수 없습니다.")
        require(actualPackage == descriptor.wrapperPackage) {
            "wrapper APK package가 descriptor와 일치하지 않습니다."
        }
        require(actualVersionCode == descriptor.wrapperVersionCode) {
            "wrapper APK versionCode가 descriptor와 일치하지 않습니다."
        }
        val manifestTree = ProcessRunner.run(
            listOf(
                toolchain.aapt2.toString(),
                "dump",
                "xmltree",
                apk.toString(),
                "--file",
                "AndroidManifest.xml",
            ),
        ).stdout
        verifyAndroidContract(manifestTree, descriptor)
        val certificates = ProcessRunner.run(
            listOf(
                toolchain.apkSigner.toString(),
                "verify",
                "--print-certs",
                apk.toString(),
            ),
        ).stdout
        val actualSigner = SIGNER_SHA.find(certificates)
            ?.groupValues
            ?.get(1)
            ?.replace(":", "")
            ?.lowercase()
            ?: throw IllegalArgumentException("wrapper APK signer SHA-256을 읽을 수 없습니다.")
        require(actualSigner == descriptor.signerSha256) {
            "wrapper APK signer가 descriptor와 일치하지 않습니다."
        }
    }

    private fun verifyEmbeddedCrx(apk: Path, descriptor: ExtensionDescriptor) {
        val embedded = ZipFile(apk.toFile()).use { zip ->
            val matches = zip.entries().asSequence()
                .filter { it.name == EMBEDDED_CRX && !it.isDirectory }
                .toList()
            require(matches.size == 1) {
                "wrapper APK는 $EMBEDDED_CRX 파일을 정확히 하나 포함해야 합니다."
            }
            val entry = matches.single()
            require(entry.size in 1..MAX_EMBEDDED_CRX_SIZE) {
                "wrapper APK embedded CRX 크기가 올바르지 않습니다."
            }
            zip.getInputStream(entry).use { input ->
                input.readNBytes((MAX_EMBEDDED_CRX_SIZE + 1).toInt())
            }.also {
                require(it.size.toLong() <= MAX_EMBEDDED_CRX_SIZE) {
                    "wrapper APK embedded CRX가 너무 큽니다."
                }
            }
        }
        require(sha256(embedded) == descriptor.crxSha256) {
            "wrapper APK embedded CRX SHA-256이 descriptor와 일치하지 않습니다."
        }
        val parsed = Crx3.parse(embedded)
        require(parsed.extensionId == descriptor.extensionId) {
            "wrapper APK embedded CRX ID가 descriptor와 일치하지 않습니다."
        }
    }

    private fun verifyAndroidContract(xmlTree: String, descriptor: ExtensionDescriptor) {
        val root = parseManifestTree(xmlTree)
        require(root.name == "manifest" &&
            root.attributes["package"] == descriptor.wrapperPackage
        ) {
            "wrapper Android manifest package가 descriptor와 일치하지 않습니다."
        }
        val applications = root.children.filter { it.name == "application" }
        require(applications.size == 1) {
            "wrapper Android manifest application 계약이 올바르지 않습니다."
        }
        val application = applications.single()
        val metadataMatches = application.children
            .filter {
                it.name == "meta-data" &&
                    it.attributes["name"] == "extension-id" &&
                    it.attributes["value"] == descriptor.extensionId
            }
        require(metadataMatches.size == 1) {
            "wrapper Android manifest extension-id metadata가 올바르지 않습니다."
        }

        val discovery = application.children.filter { it.name == "activity" }.any { activity ->
            activity.children.filter { it.name == "intent-filter" }.any { filter ->
                val actions = filter.children.filter { it.name == "action" }
                    .mapNotNull { it.attributes["name"] }
                    .toSet()
                val categories = filter.children.filter { it.name == "category" }
                    .mapNotNull { it.attributes["name"] }
                    .toSet()
                val data = filter.children.filter { it.name == "data" }
                "android.intent.action.VIEW" in actions &&
                    "android.intent.category.BROWSABLE" in categories &&
                    data.any {
                        it.attributes["scheme"] == "six" &&
                            it.attributes["host"] == "ex"
                    }
            }
        }
        require(discovery) {
            "wrapper Android manifest에 VIEW/BROWSABLE six://ex Activity가 없습니다."
        }

        val expectedAuthority = "${descriptor.wrapperPackage}.contentprovider"
        val providers = application.children
            .filter {
                it.name == "provider" &&
                    it.attributes["authorities"] == expectedAuthority
            }
        require(providers.size == 1) {
            "wrapper Android manifest provider authority가 올바르지 않습니다."
        }
        require(providers.single().attributes["exported"] == "true") {
            "wrapper Android manifest provider는 exported=true여야 합니다."
        }
    }

    private fun parseManifestTree(output: String): ManifestNode {
        val roots = mutableListOf<ManifestNode>()
        val stack = mutableListOf<Pair<Int, ManifestNode>>()
        output.lineSequence().forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }
                ?: return@forEach
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("E: ") -> {
                    while (stack.isNotEmpty() && stack.last().first >= indent) {
                        stack.removeLast()
                    }
                    val name = trimmed.removePrefix("E: ").substringBefore(' ').trim()
                    val node = ManifestNode(name)
                    stack.lastOrNull()?.second?.children?.add(node) ?: roots.add(node)
                    stack += indent to node
                }
                trimmed.startsWith("A: ") && stack.isNotEmpty() -> {
                    val encoded = trimmed.removePrefix("A: ")
                    val equals = encoded.indexOf('=')
                    if (equals > 0) {
                        val name = encoded.substring(0, equals)
                            .substringAfterLast(':')
                            .substringBefore('(')
                            .trim()
                        val value = encoded.substring(equals + 1)
                            .substringBefore(" (Raw:")
                            .trim()
                            .removeSurrounding("\"")
                        stack.last().second.attributes[name] = value
                    }
                }
            }
        }
        return roots.singleOrNull { it.name == "manifest" }
            ?: throw IllegalArgumentException("wrapper Android manifest tree를 읽을 수 없습니다.")
    }

    private data class ManifestNode(
        val name: String,
        val attributes: MutableMap<String, String> = linkedMapOf(),
        val children: MutableList<ManifestNode> = mutableListOf(),
    )

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
        private const val EMBEDDED_CRX = "assets/extension.crx"
        private const val MAX_EMBEDDED_CRX_SIZE = 128L * 1024 * 1024
        private val EXTENSION_ID = Regex("[a-p]{32}")
        private val PACKAGE_BADGING =
            Regex("""package: name='([^']+)' versionCode='(\d+)'""")
        private val SIGNER_SHA =
            Regex("""Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
