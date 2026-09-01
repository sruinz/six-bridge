package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.bundle.SixBundleContents
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

data class BuildVerificationResult(
    val extensionId: String,
    val wrapperPackage: String,
    val wrapperVersionCode: Int,
)

class BuildArtifactVerifier {
    fun verify(
        bundle: Path,
        crx: Path,
        wrapperApk: Path,
    ): BuildVerificationResult {
        val contents = SixBundle.read(bundle)
        val descriptor = contents.extension
        val crxBytes = readBoundedExternal(
            crx,
            "외부 CRX",
            MAX_EXTERNAL_CRX_SIZE,
        )
        require(sha256(crxBytes) == descriptor.crxSha256) {
            "외부 CRX SHA-256이 SixBundle descriptor와 일치하지 않습니다."
        }
        require(Crx3.parse(crxBytes).extensionId == descriptor.extensionId) {
            "외부 CRX ID가 SixBundle descriptor와 일치하지 않습니다."
        }
        val wrapperSha256 = verifyExternalWrapper(
            wrapperApk,
            contents.wrapperApk,
        )
        require(wrapperSha256 == descriptor.wrapperSha256) {
            "외부 wrapper APK SHA-256이 SixBundle descriptor와 일치하지 않습니다."
        }
        val expectedPackage = "dev.sixbridge.ext.${descriptor.extensionId}"
        require(descriptor.wrapperPackage == expectedPackage) {
            "SixBundle wrapper package가 extension ID와 일치하지 않습니다."
        }
        verifyWrapper(wrapperApk, contents)
        return BuildVerificationResult(
            extensionId = descriptor.extensionId,
            wrapperPackage = descriptor.wrapperPackage,
            wrapperVersionCode = descriptor.wrapperVersionCode,
        )
    }

    private fun readBoundedExternal(
        path: Path,
        label: String,
        maximumSize: Long,
    ): ByteArray {
        val declaredSize = Files.size(path)
        require(declaredSize in 1..maximumSize) {
            "$label 파일 크기가 제한을 초과합니다."
        }
        val bytes = Files.newInputStream(path).use { input ->
            input.readNBytes((maximumSize + 1).toInt())
        }
        require(bytes.size.toLong() <= maximumSize) {
            "$label 파일 크기가 제한을 초과합니다."
        }
        require(bytes.size.toLong() == declaredSize) {
            "$label 파일이 검증 중 변경되었습니다."
        }
        return bytes
    }

    private fun verifyExternalWrapper(
        path: Path,
        expected: ByteArray,
    ): String {
        val declaredSize = Files.size(path)
        require(declaredSize in 1..MAX_EXTERNAL_WRAPPER_SIZE) {
            "외부 wrapper APK 파일 크기가 제한을 초과합니다."
        }
        require(declaredSize == expected.size.toLong()) {
            "외부 wrapper APK bytes가 SixBundle wrapper.apk와 일치하지 않습니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var offset = 0
            while (offset < expected.size) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size, expected.size - offset),
                )
                require(count >= 0) {
                    "외부 wrapper APK가 검증 중 짧아졌습니다."
                }
                for (index in 0 until count) {
                    require(buffer[index] == expected[offset + index]) {
                        "외부 wrapper APK bytes가 SixBundle wrapper.apk와 일치하지 않습니다."
                    }
                }
                digest.update(buffer, 0, count)
                offset += count
            }
            require(input.read() == -1) {
                "외부 wrapper APK가 검증 중 길어졌습니다."
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun verifyWrapper(
        apk: Path,
        contents: SixBundleContents,
    ) {
        verifyCompatibilityBinding(apk, contents)
        verifyStructuralWrapper(apk, contents.extension)
    }

    private fun verifyStructuralWrapper(
        apk: Path,
        descriptor: ExtensionDescriptor,
    ) {
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
                "--verbose",
                "--print-certs",
                apk.toString(),
            ),
        ).stdout
        require(V3_VERIFIED.containsMatchIn(certificates)) {
            "wrapper APK는 APK Signature Scheme v3 검증을 통과해야 합니다."
        }
        val actualSigner = SIGNER_SHA.find(certificates)
            ?.groupValues
            ?.get(1)
            ?.replace(":", "")
            ?.lowercase()
            ?: throw IllegalArgumentException("wrapper APK signer SHA-256을 읽을 수 없습니다.")
        require(actualSigner == descriptor.signerSha256) {
            "wrapper APK signer가 descriptor와 일치하지 않습니다."
        }
        ProcessRunner.run(
            listOf(
                toolchain.zipAlign.toString(),
                "-c",
                "-P",
                "16",
                "4",
                apk.toString(),
            ),
        )
    }

    private fun verifyEmbeddedCrx(
        apk: Path,
        descriptor: ExtensionDescriptor,
    ) {
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
        require(Crx3.parse(embedded).extensionId == descriptor.extensionId) {
            "wrapper APK embedded CRX ID가 descriptor와 일치하지 않습니다."
        }
    }

    private fun verifyCompatibilityBinding(
        apk: Path,
        contents: SixBundleContents,
    ) {
        if (contents.compatibilitySchemaVersion in 1..5) {
            require(contents.forcedCompatibility == null) {
                "legacy SixBundle은 forced compatibility provenance를 가질 수 없습니다."
            }
            return
        }
        require(contents.compatibilitySchemaVersion == 6) {
            "지원하지 않는 compatibility schema입니다."
        }
        val digest = ZipFile(apk.toFile()).use { zip ->
            val matches = zip.entries().asSequence()
                .filter { it.name == CompatibilityApkBinding.ENTRY_NAME }
                .toList()
            require(matches.size == 1 && !matches.single().isDirectory) {
                "wrapper APK는 ${CompatibilityApkBinding.ENTRY_NAME} asset을 정확히 하나 포함해야 합니다."
            }
            zip.getInputStream(matches.single()).use { input ->
                CompatibilityApkBinding.decode(
                    input.readNBytes(CompatibilityApkBinding.ENCODED_SIZE + 1),
                )
            }
        }
        require(digest == contents.compatibilityCanonicalSha256) {
            "wrapper APK compatibility digest가 SixBundle canonical report와 일치하지 않습니다."
        }
    }

    private fun verifyAndroidContract(
        xmlTree: String,
        descriptor: ExtensionDescriptor,
    ) {
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
        val metadataMatches = application.children.filter {
            it.name == "meta-data" &&
                it.attributes["name"] == "extension-id" &&
                it.attributes["value"] == descriptor.extensionId
        }
        require(metadataMatches.size == 1) {
            "wrapper Android manifest extension-id metadata가 올바르지 않습니다."
        }

        val discoveryActivities = application.children.filter {
            it.name == "activity" &&
                it.attributes["name"] == DISCOVERY_ACTIVITY &&
                it.attributes["exported"] == "true"
        }
        require(discoveryActivities.size == 1) {
            "wrapper Android manifest discovery Activity가 올바르지 않습니다."
        }
        val discoveryFilters = discoveryActivities.single().children
            .filter { it.name == "intent-filter" }
            .filter { filter ->
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
        require(discoveryFilters.size == 1) {
            "wrapper Android manifest VIEW/BROWSABLE six://ex 계약이 올바르지 않습니다."
        }

        val expectedAuthority = "${descriptor.wrapperPackage}.contentprovider"
        val providers = application.children.filter {
            it.name == "provider" &&
                it.attributes["name"] == CONTENT_PROVIDER &&
                it.attributes["authorities"] == expectedAuthority &&
                it.attributes["exported"] == "true"
        }
        require(providers.size == 1) {
            "wrapper Android manifest ContentProvider 계약이 올바르지 않습니다."
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

    private companion object {
        const val EMBEDDED_CRX = "assets/extension.crx"
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val CONTENT_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
        const val MAX_EMBEDDED_CRX_SIZE = 128L * 1024 * 1024
        const val MAX_EXTERNAL_CRX_SIZE = 128L * 1024 * 1024
        const val MAX_EXTERNAL_WRAPPER_SIZE = 128L * 1024 * 1024
        val PACKAGE_BADGING = Regex("""package: name='([^']+)' versionCode='(\d+)'""")
        val SIGNER_SHA =
            Regex("""Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")
        val V3_VERIFIED =
            Regex("""(?m)^Verified using v3 scheme \(APK Signature Scheme v3\): true$""")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
