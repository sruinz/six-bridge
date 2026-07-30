package dev.sixbridge.manager.install

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.ContractBundleContents
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.manager.GeneratedDocumentSaveResult
import dev.sixbridge.manager.TestConversionDocumentProvider
import dev.sixbridge.manager.copyFileAndSha256
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.BundleOpenRequest
import dev.sixbridge.manager.importer.BundleSource
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.navigation.AndroidFileManagerNavigator
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.ArchiveActivity
import dev.sixbridge.manager.wrapper.ArchiveFacts
import dev.sixbridge.manager.wrapper.ArchiveInspector
import dev.sixbridge.manager.wrapper.ArchiveIntentFilter
import dev.sixbridge.manager.wrapper.ArchiveProvider
import dev.sixbridge.manager.writeGeneratedDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualApkExportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun managerDoesNotExposeDirectInstallSurface() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS).toLong(),
            ),
        )

        assertFalse(
            info.requestedPermissions.orEmpty()
                .contains(Manifest.permission.REQUEST_INSTALL_PACKAGES),
        )
        assertFalse(
            info.providers.orEmpty()
                .any { it.authority == "${context.packageName}.install" },
        )
    }

    @Test
    fun bundleImportCopiesExactApkBytesAndFileManagerIntentCarriesNoApkGrant() = runBlocking {
        val fixture = createSixFixture()
        val cache = File(context.cacheDir, "manual-apk-import-${UUID.randomUUID()}").apply {
            assertTrue(mkdir())
        }
        val destination = TestConversionDocumentProvider.uri(
            "manual-apk-${UUID.randomUUID()}",
        )
        val importer = BundleImportService(
            source = BundleSource { request ->
                check(request is BundleOpenRequest.Saf)
                fixture.six.inputStream()
            },
            verifier = AndroidWrapperVerifier(fixture.inspector),
            cacheDirectory = cache,
        )

        try {
            val imported = when (
                val result = importer.import(Uri.parse("content://sixbridge/manual-fixture.six"))
            ) {
                is ImportResult.Success -> result.value
                is ImportResult.Failure -> error("fixture import 실패: ${result.reason.code}")
            }
            val saved = writeGeneratedDocument(
                resolver = context.contentResolver,
                uri = destination,
                expectedHash = imported.wrapperSha256,
                copy = { output -> copyFileAndSha256(imported.wrapperFile, output) },
            )

            assertEquals(GeneratedDocumentSaveResult.Success, saved)
            assertArrayEquals(
                imported.wrapperFile.readBytes(),
                context.contentResolver.openInputStream(destination)!!.use { it.readBytes() },
            )

            val fileManager = requireNotNull(
                AndroidFileManagerNavigator(context.packageManager).launcherIntent(),
            )
            assertNotNull(fileManager.component)
            assertNull(fileManager.data)
            assertNull(fileManager.type)
            assertNull(fileManager.clipData)
            assertNull(fileManager.extras)
            assertEquals(0, fileManager.flags and URI_PERMISSION_FLAGS)
        } finally {
            context.contentResolver.delete(destination, null, null)
            cache.deleteRecursively()
            fixture.six.delete()
        }
    }

    private fun createSixFixture(): SixFixture {
        val zip = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.json"))
                archive.write("""{"manifest_version":3,"name":"Manual export","version":"1.0"}""".encodeToByteArray())
                archive.closeEntry()
            }
            bytes.toByteArray()
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crx = Crx3Writer.create(
            zip,
            object : Crx3SigningKey {
                override fun publicKeyDer(): ByteArray = pair.public.encoded

                override fun signSha256Rsa(payload: ByteArray): ByteArray =
                    Signature.getInstance("SHA256withRSA").run {
                        initSign(pair.private)
                        update(payload)
                        sign()
                    }
            },
        )
        val wrapper = ByteArray(256 * 1_024) { index -> (index % 251).toByte() }
        val extensionId = crx.extensionId
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = extensionId,
            name = "Manual export",
            version = "1.0",
            sourceKind = "ZIP",
            crxSha256 = sha256(crx.bytes),
            wrapperSha256 = sha256(wrapper),
            wrapperPackage = "dev.sixbridge.ext.$extensionId",
            wrapperVersionCode = 7,
            signerSha256 = "c".repeat(64),
        )
        val report = ContractCompatibilityReport(
            schemaVersion = 4,
            status = ContractCompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = descriptor.name,
            extensionVersion = descriptor.version,
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "passthrough",
            adapterDecisions = emptyList(),
            originalExtensionId = extensionId,
            convertedExtensionId = extensionId,
            sourceSha256 = sha256(zip),
            browserProfileSha256 = "d".repeat(64),
            originalSourceSha256 = "e".repeat(64),
            planSha256 = "f".repeat(64),
        )
        val six = File(context.cacheDir, "manual-apk-${UUID.randomUUID()}.six")
        six.outputStream().use { output ->
            SixBundleContract.write(
                output,
                ContractBundleContents(
                    wrapperApk = wrapper,
                    extension = descriptor,
                    compatibility = report,
                ),
            )
        }
        val inspector = ArchiveInspector {
            ArchiveFacts(
                packageName = descriptor.wrapperPackage,
                versionCode = descriptor.wrapperVersionCode.toLong(),
                signerSha256 = descriptor.signerSha256,
                metadata = mapOf("extension-id" to extensionId),
                activities = listOf(
                    ArchiveActivity(
                        name = "dev.sixbridge.runtime.SixDiscoveryActivity",
                        exported = true,
                        intentFilters = listOf(
                            ArchiveIntentFilter(
                                actions = setOf(Intent.ACTION_VIEW),
                                categories = setOf(
                                    Intent.CATEGORY_DEFAULT,
                                    Intent.CATEGORY_BROWSABLE,
                                ),
                                schemes = setOf("six"),
                                hosts = setOf("ex"),
                            ),
                        ),
                    ),
                ),
                providers = listOf(
                    ArchiveProvider(
                        name = "dev.sixbridge.runtime.ExtensionProvider",
                        authority = "${descriptor.wrapperPackage}.contentprovider",
                        exported = true,
                    ),
                ),
                crxBytes = crx.bytes,
            )
        }
        return SixFixture(six, inspector)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class SixFixture(
        val six: File,
        val inspector: ArchiveInspector,
    )

    private companion object {
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
