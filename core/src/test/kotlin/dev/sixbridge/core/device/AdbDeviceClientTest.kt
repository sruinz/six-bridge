package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.io.path.readLines
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AdbDeviceClientTest {
    @Test
    fun `uses structured provider arguments without a remote shell pipeline`() {
        val fixture = adbFixture()
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val bundle = bundle(
            extensionId = extensionId,
            wrapperPackage = "dev.sixbridge.ext.$extensionId",
        )

        val result = AdbDeviceClient(fixture.adb.toString()).smoke(
            bundle,
            "serial-1",
            Files.createTempDirectory("six-adb-evidence"),
        )

        val commands = fixture.log.readLines()
        assertTrue(result.providerReadable)
        assertTrue(
            commands.any {
                it == "-s|serial-1|shell|content|read|--uri|" +
                    "content://dev.sixbridge.ext.$extensionId.contentprovider"
            },
        )
        assertFalse(commands.any { "content read --uri" in it || "head -c" in it })
    }

    @Test
    fun `rejects manipulated extension identity before invoking adb`() {
        val fixture = adbFixture()
        val malicious = "abc;touch-data-local-tmp-pwned"
        val bundle = bundle(
            extensionId = malicious,
            wrapperPackage = "dev.sixbridge.ext.$malicious",
        )

        assertFailsWith<IllegalArgumentException> {
            AdbDeviceClient(fixture.adb.toString()).smoke(
                bundle,
                "serial-1",
                Files.createTempDirectory("six-adb-evidence"),
            )
        }
        assertFalse(Files.exists(fixture.log))
    }

    @Test
    fun `rejects wrapper package version and signer mismatches before installation`() {
        val sdk = AndroidToolchain.locateOrNull()
            ?: fail("Android SDK 도구가 없어 wrapper 검증 테스트를 실행할 수 없습니다.")
        val source = Files.createTempDirectory("six-adb-wrapper-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"ADB wrapper","version":"1"}""",
        )
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val packed = Crx3.pack(source, key)
        val output = Files.createTempFile("six-adb-real-wrapper", ".apk")
        val wrapper = WrapperApkBuilder(
            sdk,
            Files.createTempDirectory("six-adb-wrapper-local"),
        ).build(
            WrapperBuildRequest(
                packed.extensionId,
                "ADB wrapper",
                packed.bytes,
                output,
            ),
        )
        val base = ExtensionDescriptor(
            extensionId = packed.extensionId,
            name = "ADB wrapper",
            version = "1",
            sourceKind = "SOURCE_DIRECTORY",
            crxSha256 = sha256(packed.bytes),
            wrapperSha256 = sha256(output.toFile().readBytes()),
            wrapperPackage = wrapper.packageName,
            wrapperVersionCode = wrapper.versionCode,
            signerSha256 = wrapper.signerSha256,
        )
        val otherId = "abcdefghijklmnopabcdefghijklmnop"
        val mismatches = listOf(
            base.copy(
                extensionId = otherId,
                wrapperPackage = "dev.sixbridge.ext.$otherId",
            ),
            base.copy(wrapperVersionCode = base.wrapperVersionCode + 1),
            base.copy(signerSha256 = "0".repeat(64)),
        )
        val fixture = adbFixture()

        mismatches.forEach { descriptor ->
            val bundle = Files.createTempFile("six-adb-mismatch", ".six")
            SixBundle.write(
                bundle,
                output,
                descriptor,
                CompatibilityReport(
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = "ADB wrapper",
                    extensionVersion = "1",
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = emptyList(),
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                AdbDeviceClient(fixture.adb.toString()).install(bundle, "serial-1")
            }
        }
        assertFalse(Files.exists(fixture.log))
    }

    private fun adbFixture(): AdbFixture {
        val directory = Files.createTempDirectory("six-fake-adb")
        val log = directory.resolve("commands.log")
        val adb = directory.resolve("adb")
        adb.writeText(
            """
            #!/bin/sh
            first=true
            for argument in "${'$'}@"; do
              if [ "${'$'}first" = true ]; then
                first=false
              else
                printf '|' >> "${log}"
              fi
              printf '%s' "${'$'}argument" >> "${log}"
            done
            printf '\n' >> "${log}"
            case "${'$'}*" in
              *"install -r"*) printf 'Success\n' ;;
              *"pm path"*) printf 'package:/data/app/wrapper.apk\n' ;;
              *"resolve-activity"*) printf 'dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop/.Activity\n' ;;
              *"content read"*) printf 'Cr24payload\n' ;;
            esac
            """.trimIndent(),
        )
        check(adb.toFile().setExecutable(true))
        return AdbFixture(adb, log)
    }

    private fun bundle(extensionId: String, wrapperPackage: String) =
        Files.createTempFile("six-adb", ".six").also { output ->
            val wrapper = Files.createTempFile("six-adb-wrapper", ".apk")
            wrapper.writeBytes(byteArrayOf(0x50, 0x4b, 1, 2, 3))
            SixBundle.write(
                output,
                wrapper,
                ExtensionDescriptor(
                    extensionId = extensionId,
                    name = "ADB fixture",
                    version = "1",
                    sourceKind = "SOURCE_DIRECTORY",
                    crxSha256 = "a".repeat(64),
                    wrapperSha256 = sha256(wrapper.toFile().readBytes()),
                    wrapperPackage = wrapperPackage,
                    wrapperVersionCode = 1,
                    signerSha256 = "b".repeat(64),
                ),
                CompatibilityReport(
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = "ADB fixture",
                    extensionVersion = "1",
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = emptyList(),
                ),
            )
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class AdbFixture(
        val adb: java.nio.file.Path,
        val log: java.nio.file.Path,
    )
}
