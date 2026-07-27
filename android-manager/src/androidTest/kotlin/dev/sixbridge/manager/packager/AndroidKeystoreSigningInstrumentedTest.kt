package dev.sixbridge.manager.packager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.apksig.ApkVerifier
import dev.sixbridge.contract.crx.Crx3Writer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSigningInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "sixbridge.test.${UUID.randomUUID()}"
    private val work = File(context.cacheDir, "keystore-signing-$namespace")
    private lateinit var store: AndroidKeystoreIdentityStore

    @Before
    fun setUp() {
        assertTrue(work.mkdirs() || work.isDirectory)
        store = AndroidKeystoreIdentityStore(namespace)
    }

    @After
    fun tearDown() {
        AndroidKeystoreIdentityStore.deleteTestAliases(namespace)
        work.deleteRecursively()
    }

    @Test
    fun keystoreIdentityIsNonExportableAndStable() {
        val first = store.wrapperIdentity()
        val second = store.wrapperIdentity()

        assertNull(first.privateKey.encoded)
        assertEquals(first.alias, second.alias)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }

    @Test
    fun sameSourceIdentityKeepsCrxIdAndDifferentIdentitySeparatesIt() {
        val first = Crx3Writer.create(MINIMAL_ZIP, store.crxSigningKey(SOURCE_A))
        val again = Crx3Writer.create(MINIMAL_ZIP, store.crxSigningKey(SOURCE_A))
        val other = Crx3Writer.create(MINIMAL_ZIP, store.crxSigningKey(SOURCE_B))

        assertArrayEquals(first.bytes, again.bytes)
        assertEquals(first.extensionId, again.extensionId)
        assertNotEquals(first.extensionId, other.extensionId)
        assertEquals("$namespace.crx.rsa.v1.${sha256(SOURCE_A)}", store.crxIdentity(SOURCE_A).alias)
        assertNotEquals(store.crxIdentity(SOURCE_A).alias, store.crxIdentity(SOURCE_B).alias)
    }

    @Test
    fun keystoreSignerCreatesV2AndV3WrapperWithItsCertificate() {
        val crx = Crx3Writer.create(MINIMAL_ZIP, store.crxSigningKey(SOURCE_A))
        val unsigned = buildUnsignedWrapper(crx.extensionId, crx.bytes)
        val signed = File(work, "signed.apk")
        val identity = store.wrapperIdentity()

        val signing = AndroidApkSigner().sign(unsigned, signed, identity)
        assertTrue(signing.v2Verified)
        assertTrue(signing.v3Verified)
        assertEquals(sha256(identity.certificate.encoded), signing.signerCertificateSha256)

        val result = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(30)
            .build()
            .verify()
        assertTrue(result.isVerified)
        assertFalse(result.isVerifiedUsingV1Scheme)
        assertTrue(result.isVerifiedUsingV3Scheme)
        assertFalse(result.isVerifiedUsingV4Scheme)
        assertEquals(1, result.v3SchemeSigners.size)
        assertEquals(1, result.signerCertificates.size)
        assertArrayEquals(identity.certificate.encoded, result.signerCertificates.single().encoded)
        assertEquals(sha256(identity.certificate.encoded), sha256(result.signerCertificates.single().encoded))

        val legacyResult = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue(legacyResult.isVerified)
        assertTrue(legacyResult.isVerifiedUsingV2Scheme)
        assertEquals(1, legacyResult.v2SchemeSigners.size)
        assertArrayEquals(identity.certificate.encoded, legacyResult.v2SchemeSigners.single().certificate.encoded)
    }

    @Test
    fun signerRejectsUnsafeOutputsAndPreservesExistingOutputWhenSigningFails() {
        val crx = Crx3Writer.create(MINIMAL_ZIP, store.crxSigningKey(SOURCE_A))
        val unsigned = buildUnsignedWrapper(crx.extensionId, crx.bytes)
        val signer = AndroidApkSigner()
        val identity = store.wrapperIdentity()

        assertSigningFails { signer.sign(unsigned, unsigned, identity) }

        val link = File(work, "signed-link.apk")
        Files.createSymbolicLink(link.toPath(), File(work, "target.apk").toPath())
        assertSigningFails { signer.sign(unsigned, link, identity) }

        val preserved = File(work, "preserved.apk").apply { writeText("keep") }
        val malformed = File(work, "malformed.apk").apply { writeText("not-an-apk") }
        assertSigningFails { signer.sign(malformed, preserved, identity) }
        assertEquals("keep", preserved.readText())
    }

    private fun buildUnsignedWrapper(extensionId: String, crxBytes: ByteArray): File {
        val template = File(work, "wrapper-template.apk").also { destination ->
            context.assets.open("wrapper-template.apk").use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
        val manifest = ZipFile(template).use { archive ->
            archive.getInputStream(archive.getEntry("AndroidManifest.xml")).readBytes()
        }
        val patchedManifest = BinaryAndroidManifestPatcher().patch(
            manifest,
            WrapperManifestRequest(extensionId = extensionId, versionCode = 1),
        )
        return File(work, "unsigned.apk").also { output ->
            AlignedApkAssembler(work.toPath()).assemble(
                ApkAssemblyRequest(template, patchedManifest, crxBytes, output),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertSigningFails(action: () -> Unit) {
        try {
            action()
            throw AssertionError("서명이 실패해야 합니다.")
        } catch (_: Exception) {
            // 예상한 서명 또는 입력 검증 실패다.
        }
    }

    private companion object {
        val SOURCE_A = "source-a".encodeToByteArray()
        val SOURCE_B = "source-b".encodeToByteArray()
        val MINIMAL_ZIP: ByteArray = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
                zip.write("{\"manifest_version\":3,\"name\":\"Gate\",\"version\":\"1\"}".encodeToByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("content.js").apply { time = 0L })
                zip.write("console.log('six');".encodeToByteArray())
                zip.closeEntry()
            }
            bytes.toByteArray()
        }
    }
}
