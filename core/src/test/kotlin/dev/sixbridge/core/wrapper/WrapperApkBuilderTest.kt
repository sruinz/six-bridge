package dev.sixbridge.core.wrapper

import dev.sixbridge.core.crx.Crx3
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class WrapperApkBuilderTest {
    @Test
    fun `builds a signed wrapper with the Samsung discovery contract`() {
        val sdk = AndroidToolchain.locateOrNull()
            ?: fail("Android SDK 도구가 없어 wrapper 배포 검증을 실행할 수 없습니다.")
        val source = Files.createTempDirectory("six-wrapper-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Tiny","version":"1.0.0"}""",
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crx = Crx3.pack(source, keyPair)
        val localRoot = Files.createTempDirectory("six-wrapper-local")
        val output = Files.createTempDirectory("six-wrapper-output").resolve("wrapper.apk")
        val request = WrapperBuildRequest(
            extensionId = crx.extensionId,
            extensionName = "Tiny",
            crxBytes = crx.bytes,
            outputApk = output,
        )

        val result = WrapperApkBuilder(sdk, localRoot).build(request)
        val manifest = ProcessRunner.run(
            listOf(sdk.apkAnalyzer.toString(), "manifest", "print", output.toString()),
        ).stdout
        val signature = ProcessRunner.run(
            listOf(sdk.apkSigner.toString(), "verify", "--verbose", output.toString()),
        ).stdout

        assertEquals("dev.sixbridge.ext.${crx.extensionId}", result.packageName)
        assertEquals(1, result.versionCode)
        assertTrue("""android:scheme="six"""" in manifest)
        assertTrue("""android:host="ex"""" in manifest)
        assertTrue("extension-id" in manifest)
        assertTrue("${result.packageName}.contentprovider" in manifest)
        assertTrue(
            "Verified using v2 scheme (APK Signature Scheme v2): true" in signature ||
                "Verified using v3 scheme (APK Signature Scheme v3): true" in signature,
        )
        ZipFile(output.toFile()).use { apk ->
            assertEquals(ZipEntry.STORED, apk.getEntry("resources.arsc").method)
            assertTrue(apk.getEntry("classes.dex") != null)
            val embedded = apk.getInputStream(apk.getEntry("assets/extension.crx")).readBytes()
            assertTrue(embedded.contentEquals(crx.bytes))
        }
    }

    @Test
    fun `increments wrapper version while retaining package and signing key`() {
        val sdk = AndroidToolchain.locateOrNull()
            ?: fail("Android SDK 도구가 없어 wrapper 업데이트 검증을 실행할 수 없습니다.")
        val source = Files.createTempDirectory("six-wrapper-update-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Update","version":"1"}""",
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crx = Crx3.pack(source, keyPair)
        val localRoot = Files.createTempDirectory("six-wrapper-update-local")
        val builder = WrapperApkBuilder(sdk, localRoot)
        val outputs = Files.createTempDirectory("six-wrapper-update-output")

        val first = builder.build(
            WrapperBuildRequest(crx.extensionId, "Update", crx.bytes, outputs.resolve("one.apk")),
        )
        val second = builder.build(
            WrapperBuildRequest(crx.extensionId, "Update", crx.bytes, outputs.resolve("two.apk")),
        )

        assertEquals(first.packageName, second.packageName)
        assertEquals(first.versionCode + 1, second.versionCode)
        assertTrue(localRoot.resolve("keystore/wrapper.p12").toFile().isFile)
        val ownerOnly = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        assertEquals(
            ownerOnly,
            Files.getPosixFilePermissions(localRoot.resolve("keystore/wrapper.p12")),
        )
        assertEquals(
            ownerOnly,
            Files.getPosixFilePermissions(localRoot.resolve("keystore/wrapper.pass")),
        )
        assertTrue(first.signerSha256.isNotBlank())
        assertEquals(first.signerSha256, second.signerSha256)
        assertTrue(outputs.resolve("one.apk").readBytes().isNotEmpty())
    }
}
