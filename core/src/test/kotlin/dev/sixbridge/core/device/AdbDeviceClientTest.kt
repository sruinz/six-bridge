package dev.sixbridge.core.device

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.ExternalToolException
import dev.sixbridge.core.wrapper.ProcessRunner
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `device smoke evidence never records raw provider bytes`() {
        val sentinel = "SIX_PROVIDER_SENTINEL_SECRET"
        val providerBytes = "Cr24".toByteArray() +
            byteArrayOf(0) +
            sentinel.toByteArray()
        val fixture = adbFixture(
            providerCommand = """printf 'Cr24\000$sentinel'""",
        )
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val evidenceDirectory = Files.createTempDirectory("six-adb-private-evidence")

        val result = AdbDeviceClient(fixture.adb.toString()).smoke(
            bundle(
                extensionId = extensionId,
                wrapperPackage = "dev.sixbridge.ext.$extensionId",
            ),
            "serial-private",
            evidenceDirectory,
        )

        val evidence = result.evidenceLog.readText()
        assertTrue(result.providerReadable)
        assertFalse(sentinel in evidence)
        assertFalse("provider=Cr24" in evidence)
        assertTrue("providerMagic=Cr24" in evidence)
        assertTrue("providerBytes=${providerBytes.size}" in evidence)
        assertTrue("providerSha256=${sha256(providerBytes)}" in evidence)
    }

    @Test
    fun `binary process capture enforces its byte limit without exposing payload`() {
        val sentinel = "SIX_BINARY_CAPTURE_SENTINEL"
        val fixture = adbFixture(providerCommand = """printf '$sentinel'""")
        val output = Files.createTempFile("six-bounded-process", ".bin")

        val error = assertFailsWith<ExternalToolException> {
            ProcessRunner.runToFile(
                command = listOf(
                    fixture.adb.toString(),
                    "-s",
                    "serial-private",
                    "shell",
                    "content",
                    "read",
                ),
                output = output,
                maxStdoutBytes = 4,
            )
        }

        assertTrue(Files.size(output) <= 4)
        assertFalse(sentinel in error.message.orEmpty())
        assertFalse(sentinel in error.result.stdout)
        assertFalse(sentinel in error.result.stderr)
    }

    @Test
    fun `binary process capture never exposes oversized single line stderr`() {
        val sentinel = "SIX_STDERR_SENTINEL_SECRET"
        val payload = "x".repeat(8 * 1024) + sentinel
        val directory = Files.createTempDirectory("six-stderr-limit")
        val pidFile = directory.resolve("pid")
        val childPidFile = directory.resolve("child-pid")
        val command = directory.resolve("command")
        command.writeText(
            """
            #!/bin/sh
            printf '%s' "${'$'}${'$'}" > "$pidFile"
            sleep 60 &
            printf '%s' "${'$'}!" > "$childPidFile"
            printf '%s' '$payload' >&2
            wait
            """.trimIndent(),
        )
        check(command.toFile().setExecutable(true))
        val output = Files.createTempFile("six-stderr-output", ".bin")

        val error = assertFailsWith<ExternalToolException> {
            ProcessRunner.runToFile(
                command = listOf(command.toString()),
                output = output,
                maxStdoutBytes = 16,
                maxStderrBytes = 32,
                timeoutSeconds = 5,
            )
        }

        assertTrue("stderr가 32 byte 제한을 초과했습니다." in error.message.orEmpty())
        assertFalse(sentinel in error.message.orEmpty())
        assertFalse(sentinel in error.result.stdout)
        assertFalse(sentinel in error.result.stderr)
        assertFalse(sentinel in output.readText())
        assertProcessStopped(pidFile.readText().toLong())
        assertProcessStopped(childPidFile.readText().toLong())
    }

    @Test
    fun `binary process capture rejects an output symlink before start`() {
        val fixture = adbFixture()
        val directory = Files.createTempDirectory("six-output-symlink")
        val target = directory.resolve("target").also {
            it.writeText("외부 원본")
        }
        val output = directory.resolve("output")
        Files.createSymbolicLink(output, target)

        assertFailsWith<IllegalArgumentException> {
            ProcessRunner.runToFile(
                command = listOf(fixture.adb.toString()),
                output = output,
                maxStdoutBytes = 16,
                maxStderrBytes = 16,
            )
        }

        assertEquals("외부 원본", target.readText())
        assertFalse(Files.exists(fixture.log))
    }

    @Test
    fun `rejects manipulated extension identity before invoking adb`() {
        val fixture = adbFixture()
        val malicious = "abc;touch-data-local-tmp-pwned"
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val bundle = rewriteBundleEntry(
            bundle(
                extensionId = extensionId,
                wrapperPackage = "dev.sixbridge.ext.$extensionId",
            ),
            "extension.json",
        ) { bytes ->
            bytes.decodeToString()
                .replace(extensionId, malicious)
                .encodeToByteArray()
        }

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
                "0".repeat(64),
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
                    sourceSha256 = "c".repeat(64),
                    browserProfileSha256 = "d".repeat(64),
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
                "0".repeat(64),
            ),
        )
        val cases = listOf(
            ContractCase("invalid-crx", byteArrayOf(1, 2, 3), ContractVariant.VALID),
            ContractCase("wrong-crx-id", otherPacked.bytes, ContractVariant.VALID),
            ContractCase("metadata", packed.bytes, ContractVariant.METADATA),
            ContractCase("discovery-uri", packed.bytes, ContractVariant.DISCOVERY_URI),
            ContractCase(
                "discovery-exported",
                packed.bytes,
                ContractVariant.DISCOVERY_EXPORTED,
            ),
            ContractCase("discovery-class", packed.bytes, ContractVariant.DISCOVERY_CLASS),
            ContractCase("provider-authority", packed.bytes, ContractVariant.PROVIDER_AUTHORITY),
            ContractCase("provider-exported", packed.bytes, ContractVariant.PROVIDER_EXPORTED),
            ContractCase("provider-class", packed.bytes, ContractVariant.PROVIDER_CLASS),
            ContractCase("v2-only-signature", packed.bytes, ContractVariant.V2_ONLY),
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

    @Test
    fun `verify build binds the external CRX wrapper and strict SixBundle`() {
        val sdk = AndroidToolchain.locateOrNull()
            ?: fail("Android SDK 도구가 없어 build 계약 검증 테스트를 실행할 수 없습니다.")
        val source = Files.createTempDirectory("six-verify-build-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Build verify","version":"1"}""",
        )
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
        val localRoot = Files.createTempDirectory("six-verify-build-local")
        val wrapperPath = Files.createTempFile("six-verify-build", ".apk")
        val compatibility = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Build verify",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            sourceSha256 = "c".repeat(64),
            browserProfileSha256 = "d".repeat(64),
        )
        val contract = SixBundle.toContractV6(
            compatibility,
            "c".repeat(64),
            "e".repeat(64),
            null,
        )
        val wrapper = WrapperApkBuilder(sdk, localRoot).build(
            WrapperBuildRequest(
                packed.extensionId,
                "Build verify",
                packed.bytes,
                wrapperPath,
                CompatibilityApkBinding.canonicalSha256(contract),
            ),
        )
        val descriptor = ExtensionDescriptor(
            extensionId = packed.extensionId,
            name = "Build verify",
            version = "1",
            sourceKind = "SOURCE_DIRECTORY",
            crxSha256 = sha256(packed.bytes),
            wrapperSha256 = sha256(wrapperPath.toFile().readBytes()),
            wrapperPackage = wrapper.packageName,
            wrapperVersionCode = wrapper.versionCode,
            signerSha256 = wrapper.signerSha256,
        )
        val bundle = Files.createTempFile("six-verify-build", ".six").also {
            SixBundle.write(it, wrapperPath, descriptor, contract)
        }
        val crx = Files.createTempFile("six-verify-build", ".crx")
            .also { it.writeBytes(packed.bytes) }

        val result = BuildArtifactVerifier().verify(bundle, crx, wrapperPath)

        assertEquals(packed.extensionId, result.extensionId)
        assertEquals(wrapper.packageName, result.wrapperPackage)

        val downgraded = bundle(wrapperPath, descriptor)
        assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(downgraded, crx, wrapperPath)
        }
        val fixture = adbFixture()
        assertFailsWith<IllegalArgumentException> {
            AdbDeviceClient(fixture.adb.toString()).install(downgraded, "serial-1")
        }
        assertFalse(Files.exists(fixture.log))

        val wrongCrx = Files.createTempFile("six-verify-build-wrong", ".crx")
            .also { it.writeBytes(packed.bytes + 1) }
        assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(bundle, wrongCrx, wrapperPath)
        }
        val wrongWrapper = Files.createTempFile("six-verify-build-wrong", ".apk")
            .also { it.writeBytes(wrapperPath.toFile().readBytes() + 1) }
        assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(bundle, crx, wrongWrapper)
        }

        val oversizedCrx = sparseFile(
            "six-verify-build-oversized",
            ".crx",
            128L * 1024 * 1024 + 1,
        )
        val crxError = assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(bundle, oversizedCrx, wrapperPath)
        }
        assertTrue("외부 CRX 파일 크기" in crxError.message.orEmpty())

        val oversizedWrapper = sparseFile(
            "six-verify-build-oversized",
            ".apk",
            128L * 1024 * 1024 + 1,
        )
        val wrapperError = assertFailsWith<IllegalArgumentException> {
            BuildArtifactVerifier().verify(bundle, crx, oversizedWrapper)
        }
        assertTrue("외부 wrapper APK 파일 크기" in wrapperError.message.orEmpty())
    }

    private fun adbFixture(
        providerCommand: String = """printf 'Cr24payload\n'""",
    ): AdbFixture {
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
              *"content read"*) $providerCommand ;;
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
                    sourceSha256 = "c".repeat(64),
                    browserProfileSha256 = "d".repeat(64),
                ),
            )
        }

    private fun rewriteBundleEntry(
        bundle: java.nio.file.Path,
        target: String,
        transform: (ByteArray) -> ByteArray,
    ): java.nio.file.Path = Files.createTempFile("six-adb-rewritten", ".six").also { output ->
        ZipFile(bundle.toFile()).use { source ->
            ZipOutputStream(Files.newOutputStream(output)).use { zip ->
                source.entries().asSequence().forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    val bytes = source.getInputStream(entry).use { it.readBytes() }
                    zip.write(if (entry.name == target) transform(bytes) else bytes)
                    zip.closeEntry()
                }
            }
        }
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
                    sourceSha256 = "c".repeat(64),
                    browserProfileSha256 = "d".repeat(64),
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
                discoveryExported = variant != ContractVariant.DISCOVERY_EXPORTED,
                discoveryClass = if (variant == ContractVariant.DISCOVERY_CLASS) {
                    "dev.sixbridge.runtime.WrongActivity"
                } else {
                    "dev.sixbridge.runtime.SixDiscoveryActivity"
                },
                providerAuthority = if (variant == ContractVariant.PROVIDER_AUTHORITY) {
                    "$packageName.wrong"
                } else {
                    "$packageName.contentprovider"
                },
                providerExported = variant != ContractVariant.PROVIDER_EXPORTED,
                providerClass = if (variant == ContractVariant.PROVIDER_CLASS) {
                    "dev.sixbridge.runtime.WrongProvider"
                } else {
                    "dev.sixbridge.runtime.ExtensionProvider"
                },
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
                "--v3-signing-enabled",
                (variant != ContractVariant.V2_ONLY).toString(),
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
        discoveryExported: Boolean,
        discoveryClass: String,
        providerAuthority: String,
        providerExported: Boolean,
        providerClass: String,
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
                    android:name="$discoveryClass"
                    android:exported="$discoveryExported">
                    <intent-filter>
                        <action android:name="android.intent.action.VIEW" />
                        <category android:name="android.intent.category.DEFAULT" />
                        <category android:name="android.intent.category.BROWSABLE" />
                        <data android:scheme="six" android:host="$discoveryHost" />
                    </intent-filter>
                </activity>
                <provider
                    android:name="$providerClass"
                    android:authorities="$providerAuthority"
                    android:exported="$providerExported" />
            </application>
        </manifest>
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sparseFile(
        prefix: String,
        suffix: String,
        size: Long,
    ): java.nio.file.Path = Files.createTempFile(prefix, suffix).also { path ->
        Files.newByteChannel(path, StandardOpenOption.WRITE).use { channel ->
            channel.position(size - 1)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }
    }

    private fun assertProcessStopped(pid: Long) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline &&
            java.lang.ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        ) {
            Thread.sleep(10)
        }
        assertFalse(
            java.lang.ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
            "process가 종료되지 않았습니다: $pid",
        )
    }

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
        DISCOVERY_EXPORTED,
        DISCOVERY_CLASS,
        PROVIDER_AUTHORITY,
        PROVIDER_EXPORTED,
        PROVIDER_CLASS,
        V2_ONLY,
    }
}
