package dev.sixbridge.core.device

import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import dev.sixbridge.core.wrapper.ProcessRunner
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuildArtifactVerifierTest {
    @Test
    fun `v6 wrapper는 canonical compatibility digest asset을 요구한다`() {
        val sdk = requireNotNull(AndroidToolchain.locateOrNull())
        val source = Files.createTempDirectory("six-v6-verifier-source").also {
            it.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"V6","version":"1"}""")
        }
        val crx = Crx3.pack(source, KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())
        val report = SixBundle.toContractV6(
            CompatibilityReport(
                CompatibilityStatus.COMPATIBLE, 3, "V6", "1", declaredPermissions = emptySet(),
                usedApis = emptySet(), unsupportedApis = emptySet(), issues = emptyList(),
                conversionState = ConversionState.PASSTHROUGH, sourceSha256 = "a".repeat(64),
                browserProfileSha256 = "b".repeat(64),
            ), "a".repeat(64), "c".repeat(64), null,
        )
        val wrapper = Files.createTempFile("six-v6-verifier", ".apk")
        val localRoot = Files.createTempDirectory("six-v6-verifier-local")
        val built = WrapperApkBuilder(sdk, localRoot).build(
            WrapperBuildRequest(crx.extensionId, "V6", crx.bytes, wrapper, CompatibilityApkBinding.canonicalSha256(report)),
        )
        val descriptor = ExtensionDescriptor(crx.extensionId, "V6", "1", "SOURCE_DIRECTORY", sha(crx.bytes), sha(Files.readAllBytes(wrapper)), built.packageName, built.versionCode, built.signerSha256)
        val bundle = Files.createTempFile("six-v6-verifier", ".six")
        SixBundle.write(bundle, wrapper, descriptor, report)
        val contents = SixBundle.read(bundle)

        assertEquals(6, contents.compatibilitySchemaVersion)
        assertEquals(CompatibilityApkBinding.canonicalSha256(report), contents.compatibilityCanonicalSha256)
        ZipFile(wrapper.toFile()).use { zip ->
            assertEquals(contents.compatibilityCanonicalSha256, zip.getInputStream(zip.getEntry(CompatibilityApkBinding.ENTRY_NAME)).readBytes().decodeToString())
        }
        BuildArtifactVerifier().verifyWrapper(wrapper, contents)

        val wrong = Files.createTempFile("six-v6-wrong-digest", ".apk")
        WrapperApkBuilder(sdk, Files.createTempDirectory("six-v6-wrong-local")).build(
            WrapperBuildRequest(crx.extensionId, "V6", crx.bytes, wrong, "0".repeat(64)),
        )
        assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verifyWrapper(wrong, contents)
        }

        listOf(
            "missing" to null,
            "short" to "a".repeat(63).encodeToByteArray(),
            "long" to "a".repeat(65).encodeToByteArray(),
            "upper" to "A".repeat(64).encodeToByteArray(),
            "newline" to ("a".repeat(63) + "\n").encodeToByteArray(),
        ).forEach { (name, replacement) ->
            val malformed = rewriteBinding(wrapper, replacement)
            val error = assertFailsWith<IllegalArgumentException>(name) {
                BuildArtifactVerifier().verifyWrapper(malformed, contents)
            }
            assertTrue("compatibility" in error.message.orEmpty(), name)
        }

        val legacy = Files.createTempFile("six-v5-verifier", ".six")
        val legacyWrapper = removeBindingAndResign(sdk, localRoot, wrapper)
        val legacyDescriptor = descriptor.copy(wrapperSha256 = sha(Files.readAllBytes(legacyWrapper)))
        SixBundle.write(legacy, legacyWrapper, legacyDescriptor, contents.compatibility)
        BuildArtifactVerifier().verifyWrapper(legacyWrapper, SixBundle.read(legacy))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun rewriteBinding(source: java.nio.file.Path, replacement: ByteArray?): java.nio.file.Path {
        val output = Files.createTempFile("six-v6-binding-rewrite", ".apk")
        ZipFile(source.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(output)).use { target ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name == CompatibilityApkBinding.ENTRY_NAME && replacement == null) return@forEach
                    target.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    target.write(
                        if (entry.name == CompatibilityApkBinding.ENTRY_NAME) replacement
                        else zip.getInputStream(entry).use { it.readBytes() },
                    )
                    target.closeEntry()
                }
            }
        }
        return output
    }

    private fun removeBindingAndResign(
        sdk: AndroidToolchain,
        localRoot: java.nio.file.Path,
        source: java.nio.file.Path,
    ): java.nio.file.Path {
        val unsigned = Files.createTempFile("six-v5-no-binding", ".apk")
        ZipFile(source.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(unsigned)).use { out ->
                zip.entries().asSequence()
                    .filter { it.name != CompatibilityApkBinding.ENTRY_NAME }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val copy = ZipEntry(entry.name).apply {
                            time = 0L
                            method = entry.method
                            if (method == ZipEntry.STORED) {
                                size = bytes.size.toLong()
                                compressedSize = bytes.size.toLong()
                                crc = CRC32().apply { update(bytes) }.value
                            }
                        }
                        out.putNextEntry(copy)
                        out.write(bytes)
                        out.closeEntry()
                    }
            }
        }
        val aligned = Files.createTempFile("six-v5-no-binding-aligned", ".apk")
        ProcessRunner.run(listOf(sdk.zipAlign.toString(), "-f", "4", unsigned.toString(), aligned.toString()))
        return Files.createTempFile("six-v5-no-binding-signed", ".apk").also { signed ->
            ProcessRunner.run(
                listOf(
                    sdk.apkSigner.toString(), "sign", "--ks", localRoot.resolve("keystore/wrapper.p12").toString(),
                    "--ks-type", "PKCS12", "--ks-key-alias", "sixbridge", "--ks-pass",
                    "file:${localRoot.resolve("keystore/wrapper.pass")}", "--out", signed.toString(), aligned.toString(),
                ),
            )
        }
    }
}
