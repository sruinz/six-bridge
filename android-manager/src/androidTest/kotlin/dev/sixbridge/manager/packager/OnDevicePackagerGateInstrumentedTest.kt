package dev.sixbridge.manager.packager

import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDevicePackagerGateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val root = File(context.filesDir, "ondevice-packager-gate")

    @Test
    fun packagesVerifiedWrapperWithExactDescriptor() {
        val source = canonicalSourceZip()
        val packager = OnDeviceWrapperPackager(context)
        val v1 = File(root, "orchestration-v1.apk")
        val v2 = File(root, "orchestration-v2.apk")
        val first = packager.packageWrapper(request(source, 1, v1))
        val second = packager.packageWrapper(request(source, 2, v2))

        assertEquals(first.extensionId, second.extensionId)
        assertEquals(first.packageName, second.packageName)
        assertEquals(first.signerSha256, second.signerSha256)
        assertEquals(first.crxSha256, second.crxSha256)
        assertEquals(1, first.versionCode)
        assertEquals(2, second.versionCode)
        assertEquals(first.crxSha256, first.descriptor.crxSha256)
        assertEquals(first.wrapperSha256, first.descriptor.wrapperSha256)
        assertEquals(first.signerSha256, first.descriptor.signerSha256)
        assertEquals(first.extensionId, first.compatibility.convertedExtensionId)
        assertTrue(first.outputApk.isFile)
        val verified = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager)).verify(
            first.outputApk,
            first.descriptor,
            first.compatibility,
        )
        assertTrue(verified is WrapperVerificationResult.Success)
        val verifiedWrapper = (verified as WrapperVerificationResult.Success).value
        assertEquals("${first.packageName}.contentprovider", verifiedWrapper.providerAuthority)
        assertEquals(1L, verifiedWrapper.versionCode)
        val corruptedDescriptor = first.descriptor.copy(crxSha256 = first.wrapperSha256)
        val corrupted = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager)).verify(
            first.outputApk,
            corruptedDescriptor,
            first.compatibility,
        )
        assertTrue(corrupted is WrapperVerificationResult.Failure)

        val invalidSource = request(source, 3, File(root, "invalid-source.apk")).copy(sourceSha256 = "z".repeat(64))
        assertRejected { packager.packageWrapper(invalidSource) }
        assertFalse(invalidSource.outputApk.exists())
        val invalidProfile = request(source, 3, File(root, "invalid-profile.apk")).copy(browserProfileSha256 = "z".repeat(64))
        assertRejected { packager.packageWrapper(invalidProfile) }
        assertFalse(invalidProfile.outputApk.exists())

        val target = File(root, "symlink-target.apk")
        val symlink = File(root, "symlink-output.apk")
        Files.deleteIfExists(symlink.toPath())
        Files.createSymbolicLink(symlink.toPath(), target.toPath())
        assertRejected { packager.packageWrapper(request(source, 3, symlink)) }
        assertFalse(target.exists())
        Files.deleteIfExists(symlink.toPath())
    }

    @Test
    fun snapshotsMutableSourceBeforeValidationAndCrxCreation() {
        val originalIdentity = SOURCE_IDENTITY.copyOf()
        val originalZip = canonicalSourceZip()
        val mutableIdentity = originalIdentity.copyOf()
        val mutableZip = originalZip.copyOf()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                afterRequestValidated = {
                    mutableIdentity.fill(0x5a)
                    mutableZip.fill(0x5a)
                },
            ),
        )
        val result = packager.packageWrapper(
            request(mutableZip, 4, File(root, "snapshot.apk")).copy(sourceIdentity = mutableIdentity),
        )
        val expected = Crx3Writer.create(
            originalZip,
            AndroidKeystoreIdentityStore().crxSigningKey(originalIdentity),
        )
        val facts = AndroidArchiveInspector(context.packageManager).inspect(result.outputApk)
        val crx = Crx3Verifier.verify(facts.crxBytes)

        assertEquals(sha256(originalZip), result.compatibility.sourceSha256)
        assertEquals(expected.extensionId, result.extensionId)
        assertEquals(expected.extensionId, crx.extensionId)
        assertTrue(
            originalZip.contentEquals(
                facts.crxBytes.copyOfRange(crx.zipOffset, crx.zipOffset + crx.zipLength),
            ),
        )
    }

    @Test
    fun rootReplacementBeforeFinalPublishIsRejectedAndPreservesExistingOutput() {
        assertTrue(root.mkdirs() || root.isDirectory)
        val existing = File(root, "preserved.apk").apply { writeText("keep") }
        val savedRoot = File(context.filesDir, "ondevice-packager-gate-saved")
        val replacement = File(context.filesDir, "ondevice-packager-gate-replacement")
        Files.deleteIfExists(savedRoot.toPath())
        replacement.deleteRecursively()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                beforeFinalPublish = {
                    Files.move(root.toPath(), savedRoot.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    assertTrue(replacement.mkdirs())
                    Files.createSymbolicLink(root.toPath(), replacement.toPath())
                },
            ),
        )
        try {
            assertRejected { packager.packageWrapper(request(canonicalSourceZip(), 5, existing)) }
            assertFalse(File(replacement, "preserved.apk").exists())
            assertEquals("keep", File(savedRoot, "preserved.apk").readText())
        } finally {
            Files.deleteIfExists(root.toPath())
            replacement.deleteRecursively()
            if (savedRoot.exists()) Files.move(savedRoot.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    @Test
    fun generateV1AndPersistIdentityEvidence() {
        val source = canonicalSourceZip()
        val output = File(root, "gate-v1.apk")
        val result = OnDeviceWrapperPackager(context).packageWrapper(request(source, 1, output))
        val alias = "sixbridge.crx.rsa.v1.${sha256(SOURCE_IDENTITY)}"
        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        assertNull(entry.privateKey.encoded)
        assertEquals(KeyProperties.KEY_ALGORITHM_RSA, entry.privateKey.algorithm)
        writeJson(
            File(root, "gate-state.json"),
            """{"schemaVersion":1,"extensionId":"${result.extensionId}","packageName":"${result.packageName}","wrapperSignerSha256":"${result.signerSha256}","crxSha256":"${result.crxSha256}","v1Sha256":"${result.wrapperSha256}"}""",
        )
        assertTrue(output.isFile)
    }

    @Test
    fun generateV2AfterProcessRestartAndMatchIdentity() {
        val state = File(root, "gate-state.json")
        assertTrue("v1 계측 method를 먼저 실행해야 합니다.", state.isFile)
        val old = state.readText()
        val v1 = File(root, "gate-v1.apk")
        assertTrue("v1 wrapper APK가 있어야 합니다.", v1.isFile)
        val source = canonicalSourceZip()
        val v2 = File(root, "gate-v2.apk")
        val result = OnDeviceWrapperPackager(context).packageWrapper(request(source, 2, v2))
        assertTrue(old.contains("\"extensionId\":\"${result.extensionId}\""))
        assertTrue(old.contains("\"packageName\":\"${result.packageName}\""))
        assertTrue(old.contains("\"wrapperSignerSha256\":\"${result.signerSha256}\""))
        assertTrue(old.contains("\"crxSha256\":\"${result.crxSha256}\""))
        val v1Hash = jsonField(old, "v1Sha256")
        assertNotNull(v1Hash)
        assertEquals(v1Hash, sha256(v1.readBytes()))
        assertNotEquals(v1Hash, sha256(v2.readBytes()))
        val verifier = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager))
        val v1Descriptor = result.descriptor.copy(
            wrapperSha256 = requireNotNull(v1Hash),
            wrapperVersionCode = 1,
        )
        val verifiedV1 = verifier.verify(v1, v1Descriptor, result.compatibility)
        val verifiedV2 = verifier.verify(v2, result.descriptor, result.compatibility)
        assertTrue(verifiedV1 is WrapperVerificationResult.Success)
        assertTrue(verifiedV2 is WrapperVerificationResult.Success)
        val v1Artifact = (verifiedV1 as WrapperVerificationResult.Success).value
        val v2Artifact = (verifiedV2 as WrapperVerificationResult.Success).value
        assertEquals(v1Artifact.extensionId, v2Artifact.extensionId)
        assertEquals(v1Artifact.packageName, v2Artifact.packageName)
        assertEquals(v1Artifact.signerSha256, v2Artifact.signerSha256)
        assertEquals(1L, v1Artifact.versionCode)
        assertEquals(2L, v2Artifact.versionCode)
        writeJson(
            File(root, "gate-result.json"),
            """{"schemaVersion":1,"v1":{"file":"gate-v1.apk","sha256":"${v1Artifact.wrapperSha256}","versionCode":${v1Artifact.versionCode}},"v2":{"file":"gate-v2.apk","sha256":"${v2Artifact.wrapperSha256}","versionCode":${v2Artifact.versionCode}}}""",
        )
        assertTrue(v2.isFile)
    }

    private fun request(source: ByteArray, versionCode: Int, output: File): OnDeviceWrapperRequest =
        OnDeviceWrapperRequest(
            sourceIdentity = SOURCE_IDENTITY,
            canonicalSourceZip = source,
            versionCode = versionCode,
            extensionName = "SIX Bridge On-Device Gate",
            extensionVersion = "1.0",
            sourceKind = "ZIP",
            sourceSha256 = sha256(source),
            browserProfileSha256 = PROFILE_SHA256,
            outputApk = output,
        )

    private fun canonicalSourceZip(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            addEntry(zip, "manifest.json", testContext.assets.open("ondevice-gate/manifest.json").readBytes())
            addEntry(zip, "content.js", testContext.assets.open("ondevice-gate/content.js").readBytes())
        }
        bytes.toByteArray()
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeJson(file: File, value: String) {
        assertTrue(root.mkdirs() || root.isDirectory)
        file.writeText(value)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun jsonField(json: String, name: String): String? =
        Regex("\\\"$name\\\":\\\"([0-9a-f]{64})\\\"").find(json)?.groupValues?.get(1)

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("입력이 거부되어야 합니다.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private companion object {
        val SOURCE_IDENTITY = "ondevice-gate-source-identity-v1".encodeToByteArray()
        const val PROFILE_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
