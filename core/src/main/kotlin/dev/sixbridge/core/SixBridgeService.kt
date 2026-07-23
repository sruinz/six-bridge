package dev.sixbridge.core

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.copyTo
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeBytes

data class SixBuildResult(
    val extensionId: String,
    val crx: Path,
    val wrapperApk: Path,
    val bundle: Path,
    val compatibility: CompatibilityReport,
)

class SixBridgeService(
    private val workspaceRoot: Path,
    private val inputResolver: ExtensionInputResolver = ExtensionInputResolver(),
    private val compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(),
    private val toolchainProvider: () -> AndroidToolchain = { AndroidToolchain.locate() },
) {
    private val localRoot = workspaceRoot.resolve(".local")

    fun analyze(input: String, profile: BrowserProfile): CompatibilityReport {
        val resolved = inputResolver.resolve(input)
        val source = resolved.sourceRoot
            ?: throw IllegalArgumentException("분석할 확장 소스를 입력에서 추출하지 못했습니다.")
        return compatibilityAnalyzer.analyze(source, profile)
    }

    fun build(
        input: String,
        profile: BrowserProfile,
        outputBundle: Path? = null,
    ): SixBuildResult {
        val resolved = inputResolver.resolve(input)
        val source = resolved.sourceRoot
            ?: throw IllegalArgumentException("빌드할 확장 소스를 입력에서 추출하지 못했습니다.")
        val compatibility = compatibilityAnalyzer.analyze(source, profile)
        require(
            compatibility.status == CompatibilityStatus.COMPATIBLE ||
                compatibility.status == CompatibilityStatus.PARTIAL,
        ) {
            "호환성 상태 ${compatibility.status.name.lowercase()}에서는 빌드하지 않습니다."
        }

        val packed = prepareCrx(resolved, source)
        val outputDirectory = (outputBundle?.toAbsolutePath()?.parent
            ?: workspaceRoot.resolve("dist")).also { Files.createDirectories(it) }
        val crxOutput = outputDirectory.resolve("${packed.extensionId}.crx")
        crxOutput.writeBytes(packed.bytes)
        val wrapperOutput = outputDirectory.resolve("${packed.extensionId}-wrapper.apk")
        val wrapper = WrapperApkBuilder(toolchainProvider(), localRoot).build(
            WrapperBuildRequest(
                extensionId = packed.extensionId,
                extensionName = compatibility.extensionName ?: packed.extensionId,
                crxBytes = packed.bytes,
                outputApk = wrapperOutput,
            ),
        )
        val bundleOutput = outputBundle?.toAbsolutePath()
            ?: outputDirectory.resolve("${packed.extensionId}.six")
        val descriptor = ExtensionDescriptor(
            extensionId = packed.extensionId,
            name = compatibility.extensionName ?: packed.extensionId,
            version = compatibility.extensionVersion ?: "0",
            sourceKind = resolved.kind.name,
            crxSha256 = sha256(packed.bytes),
            wrapperPackage = wrapper.packageName,
            wrapperVersionCode = wrapper.versionCode,
            signerSha256 = wrapper.signerSha256,
        )
        SixBundle.write(bundleOutput, wrapperOutput, descriptor, compatibility)
        if (resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY ||
            resolved.kind == ExtensionInputKind.ZIP
        ) {
            saveLocalSource(source, packed.extensionId)
        }
        return SixBuildResult(
            extensionId = packed.extensionId,
            crx = crxOutput,
            wrapperApk = wrapperOutput,
            bundle = bundleOutput,
            compatibility = compatibility,
        )
    }

    private fun prepareCrx(
        resolved: ResolvedExtensionInput,
        source: Path,
    ): PreparedCrx {
        val original = resolved.crxBytes
        if (original != null) {
            val parsed = Crx3.parse(original)
            return PreparedCrx(parsed.extensionId, original)
        }
        val keyManager = ExtensionKeyManager(localRoot.resolve("keystore/crx"))
        val leaf = source.fileName.toString()
        val identity = if (EXTENSION_ID.matches(leaf)) {
            leaf
        } else {
            val stableInput = when (resolved.kind) {
                ExtensionInputKind.SOURCE_DIRECTORY -> source.toAbsolutePath().normalize().toString()
                ExtensionInputKind.ZIP ->
                    Path.of(resolved.originalInput).toAbsolutePath().normalize().toString()
                else -> resolved.originalInput
            }
            "source-${sha256(stableInput.toByteArray()).take(24)}"
        }
        val key = keyManager.getOrCreate(identity)
        val packed = Crx3.pack(source, key)
        keyManager.persist(packed.extensionId, key)
        return PreparedCrx(packed.extensionId, packed.bytes)
    }

    private fun saveLocalSource(source: Path, extensionId: String) {
        val destination = localRoot.resolve("extensions").resolve(extensionId)
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize()) return
        Files.createDirectories(destination)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                if (relative.any { it.toString() in setOf(".git", ".gradle", "node_modules") }) {
                    return@forEach
                }
                val output = destination.resolve(relative.toString())
                if (Files.isDirectory(path)) {
                    Files.createDirectories(output)
                } else if (path.isRegularFile()) {
                    Files.createDirectories(output.parent)
                    path.copyTo(output, overwrite = true)
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class PreparedCrx(
        val extensionId: String,
        val bytes: ByteArray,
    )

    companion object {
        private val EXTENSION_ID = Regex("[a-p]{32}")
    }
}
