package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.ProcessRunner
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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

    @Test
    fun `rejects embedded CRX and Android discovery contract mismatches before installation`() {
        val sdk = AndroidToolchain.locateOrNull()
            ?: fail("Android SDK 도구가 없어 wrapper 계약 검증 테스트를 실행할 수 없습니다.")
        val source = Files.createTempDirectory("six-adb-contract-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Contract wrapper","version":"1"}""",
        )
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val packed = Crx3.pack(source, key)
        val otherKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val otherPacked = Crx3.pack(source, otherKey)
        val localRoot = Files.createTempDirectory("six-adb-contract-local")
        val validOutput = Files.createTempFile("six-adb-contract-valid", ".apk")
        val valid = WrapperApkBuilder(sdk, localRoot).build(
            WrapperBuildRequest(
                packed.extensionId,
                "Contract wrapper",
                packed.bytes,
                validOutput,
            ),
        )
        val cases = listOf(
            ContractCase("invalid-crx", byteArrayOf(1, 2, 3), ContractVariant.VALID),
            ContractCase("wrong-crx-id", otherPacked.bytes, ContractVariant.VALID),
            ContractCase("metadata", packed.bytes, ContractVariant.METADATA),
            ContractCase("discovery-uri", packed.bytes, ContractVariant.DISCOVERY_URI),
            ContractCase("provider-authority", packed.bytes, ContractVariant.PROVIDER_AUTHORITY),
            ContractCase("provider-exported", packed.bytes, ContractVariant.PROVIDER_EXPORTED),
        )
        val fixture = adbFixture()

        cases.forEach { case ->
            val wrapper = Files.createTempFile("six-adb-${case.name}", ".apk")
            createSignedContractWrapper(
                sdk = sdk,
                localRoot = localRoot,
                extensionId = packed.extensionId,
                crxBytes = case.crxBytes,
                variant = case.variant,
                output = wrapper,
            )
            val descriptor = ExtensionDescriptor(
                extensionId = packed.extensionId,
                name = "Contract wrapper",
                version = "1",
                sourceKind = "SOURCE_DIRECTORY",
                crxSha256 = sha256(case.crxBytes),
                wrapperSha256 = sha256(wrapper.toFile().readBytes()),
                wrapperPackage = valid.packageName,
                wrapperVersionCode = valid.versionCode,
                signerSha256 = valid.signerSha256,
            )
            val bundle = bundle(wrapper, descriptor)
            assertFailsWith<IllegalArgumentException>(case.name) {
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

    private fun bundle(wrapper: java.nio.file.Path, descriptor: ExtensionDescriptor) =
        Files.createTempFile("six-adb-contract", ".six").also { output ->
            SixBundle.write(
                output,
                wrapper,
                descriptor,
                CompatibilityReport(
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = descriptor.name,
                    extensionVersion = descriptor.version,
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = emptyList(),
                ),
            )
        }

    private fun createSignedContractWrapper(
        sdk: AndroidToolchain,
        localRoot: java.nio.file.Path,
        extensionId: String,
        crxBytes: ByteArray,
        variant: ContractVariant,
        output: java.nio.file.Path,
    ) {
        val work = Files.createTempDirectory("six-adb-contract-build")
        val packageName = "dev.sixbridge.ext.$extensionId"
        val manifest = work.resolve("AndroidManifest.xml")
        manifest.writeText(
            testManifest(
                packageName,
                extensionId,
                metadataId = if (variant == ContractVariant.METADATA) {
                    "abcdefghijklmnopabcdefghijklmnop"
                } else {
                    extensionId
                },
                discoveryHost = if (variant == ContractVariant.DISCOVERY_URI) "wrong" else "ex",
                providerAuthority = if (variant == ContractVariant.PROVIDER_AUTHORITY) {
                    "$packageName.wrong"
                } else {
                    "$packageName.contentprovider"
                },
                providerExported = variant != ContractVariant.PROVIDER_EXPORTED,
            ),
        )
        val base = work.resolve("base.apk")
        ProcessRunner.run(
            listOf(
                sdk.aapt2.toString(),
                "link",
                "-o",
                base.toString(),
                "-I",
                sdk.androidJar.toString(),
                "--manifest",
                manifest.toString(),
                "--min-sdk-version",
                "30",
                "--target-sdk-version",
                "36",
                "--version-code",
                "1",
                "--version-name",
                "1.0.1",
            ),
        )
        val unsigned = work.resolve("unsigned.apk")
        ZipOutputStream(Files.newOutputStream(unsigned)).use { zip ->
            ZipFile(base.toFile()).use { source ->
                val entries = source.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    val copy = ZipEntry(entry.name).apply {
                        time = 0L
                        method = entry.method
                        if (method == ZipEntry.STORED) {
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                    zip.putNextEntry(copy)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("assets/extension.crx").apply { time = 0L })
            zip.write(crxBytes)
            zip.closeEntry()
        }
        val aligned = work.resolve("aligned.apk")
        ProcessRunner.run(
            listOf(
                sdk.zipAlign.toString(),
                "-f",
                "4",
                unsigned.toString(),
                aligned.toString(),
            ),
        )
        ProcessRunner.run(
            listOf(
                sdk.apkSigner.toString(),
                "sign",
                "--ks",
                localRoot.resolve("keystore/wrapper.p12").toString(),
                "--ks-type",
                "PKCS12",
                "--ks-key-alias",
                "sixbridge",
                "--ks-pass",
                "file:${localRoot.resolve("keystore/wrapper.pass")}",
                "--out",
                output.toString(),
                aligned.toString(),
            ),
        )
    }

    private fun testManifest(
        packageName: String,
        extensionId: String,
        metadataId: String,
        discoveryHost: String,
        providerAuthority: String,
        providerExported: Boolean,
    ) = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$packageName"
            android:versionCode="1"
            android:versionName="1.0.1">
            <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
            <application android:label="Contract wrapper">
                <meta-data android:name="extension-id" android:value="$metadataId" />
                <activity
                    android:name="dev.sixbridge.runtime.SixDiscoveryActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.VIEW" />
                        <category android:name="android.intent.category.DEFAULT" />
                        <category android:name="android.intent.category.BROWSABLE" />
                        <data android:scheme="six" android:host="$discoveryHost" />
                    </intent-filter>
                </activity>
                <provider
                    android:name="dev.sixbridge.runtime.ExtensionProvider"
                    android:authorities="$providerAuthority"
                    android:exported="$providerExported" />
            </application>
        </manifest>
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class AdbFixture(
        val adb: java.nio.file.Path,
        val log: java.nio.file.Path,
    )

    private data class ContractCase(
        val name: String,
        val crxBytes: ByteArray,
        val variant: ContractVariant,
    )

    private enum class ContractVariant {
        VALID,
        METADATA,
        DISCOVERY_URI,
        PROVIDER_AUTHORITY,
        PROVIDER_EXPORTED,
    }
}
