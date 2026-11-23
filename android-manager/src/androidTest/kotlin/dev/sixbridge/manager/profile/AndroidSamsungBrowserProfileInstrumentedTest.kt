package dev.sixbridge.manager.profile

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.ProfileSupport
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSamsungBrowserProfileInstrumentedTest {
    @Test
    fun installedSamsungBrowserMatchesSupportedProfile(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageInfo = packageInfo(context.packageManager)
        val applicationInfo = requireNotNull(packageInfo.applicationInfo)
        val signerSha256 = packageInfo.signingInfo
            ?.apkContentsSigners
            .orEmpty()
            .map { sha256(it.toByteArray()) }
        val base = File(requireNotNull(applicationInfo.sourceDir))
        val splits = applicationInfo.splitSourceDirs.orEmpty().map(::File)
        assertEquals("검증 기준 설치는 base-only여야 합니다.", 0, splits.size)
        val archiveHashes = (listOf(base) + splits).mapIndexed { index, archive ->
            val logicalName = if (index == 0) "base.apk" else archive.name
            logicalName to archive.inputStream().use(::sha256)
        }
        val expectedInstallationSha256 = installationApkSetSha256(archiveHashes)
        val oracle = inspectArchives(listOf(base) + splits)
        val installedMajor = packageInfo.versionName.orEmpty().substringBefore('.').toIntOrNull()
        val baseline = installedMajor?.let(BrowserProfile::baselineForMajor)
        val expectedSupport = (
            packageInfo.packageName == SAMSUNG_BROWSER_PACKAGE &&
                signerSha256 == listOf(BrowserProfile.SAMSUNG_SIGNER_SHA256) &&
                baseline != null &&
                oracle.wrapperContractComplete &&
                oracle.namespaces == baseline.apis
            )

        assertTrue(
            context.getSharedPreferences(
                AndroidSamsungBrowserProfileProvider.CACHE_PREFERENCES,
                android.content.Context.MODE_PRIVATE,
            ).edit().clear().commit(),
        )
        val result = AndroidSamsungBrowserProfileProvider(context).load()
        assertEquals(true, expectedSupport)
        assertTrue(result is SamsungBrowserProfileResult.Supported)
        val supported = result as SamsungBrowserProfileResult.Supported
        val profile = supported.profile

        assertEquals(SAMSUNG_BROWSER_PACKAGE, packageInfo.packageName)
        assertEquals(1, signerSha256.size)
        assertEquals(BrowserProfile.SAMSUNG_SIGNER_SHA256, signerSha256.single())
        assertTrue(BrowserProfile.isSupportedMajorVersion(requireNotNull(installedMajor)))
        assertEquals(SAMSUNG_BROWSER_PACKAGE, profile.browserPackage)
        assertEquals(packageInfo.versionName, profile.versionName)
        assertEquals(packageInfo.longVersionCode, profile.versionCode)
        assertEquals(signerSha256.single(), profile.sourceApkSignerSha256)
        assertEquals(archiveHashes.first().second, profile.sourceApkSha256)
        assertEquals(expectedInstallationSha256, profile.installationApkSetSha256)
        assertEquals(oracle.discoveryUri, profile.wrapperContract.discoveryUri)
        assertEquals(oracle.extensionMetadata, profile.wrapperContract.extensionMetadata)
        assertEquals(oracle.contentProvider, profile.wrapperContract.contentProvider)
        assertEquals(oracle.devExtensionPath, profile.wrapperContract.devExtensionPath)
        assertEquals(oracle.namespaces, profile.detectedApis)
        assertEquals(oracle.wrapperContractComplete, profile.wrapperContract.isComplete)
        assertEquals(ProfileSupport.SUPPORTED, profile.support)

        val marker = buildString {
            append("SIXBRIDGE_SAMSUNG_PROFILE_OK")
            append(" package=${profile.browserPackage}")
            append(" signer=${profile.sourceApkSignerSha256}")
            append(" version=${profile.versionName}")
            append(" versionCode=${profile.versionCode}")
            append(" baseSha256=${profile.sourceApkSha256}")
            append(" splitCount=${splits.size}")
            append(" installationApkSetSha256=${profile.installationApkSetSha256}")
            append(" contract=discoveryUri,extensionMetadata,contentProvider,devExtensionPath")
            append(" support=${profile.support}")
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("stream", "$marker\n") },
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                SAMSUNG_BROWSER_PACKAGE,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        } else {
            packageManager.getPackageInfo(
                SAMSUNG_BROWSER_PACKAGE,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }

    private fun installationApkSetSha256(
        archives: List<Pair<String, String>>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.bytes(INSTALLATION_DIGEST_DOMAIN.toByteArray(UTF_8))
        val sorted = archives.sortedWith(
            compareBy(Pair<String, String>::first, Pair<String, String>::second),
        )
        digest.bytes(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(sorted.size).array())
        sorted.forEach { (name, sha256) ->
            digest.bytes(name.toByteArray(UTF_8))
            digest.bytes(sha256.toByteArray(UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun inspectArchives(archives: List<File>): IndependentOracle {
        val markers = mutableSetOf<String>()
        val namespaces = mutableSetOf<String>()
        archives.forEach { archive ->
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (
                        entry.name.endsWith(".dex") ||
                        entry.name.endsWith("/libterrace.so")
                    ) {
                        scanText(zip) { text ->
                            ORACLE_MARKERS.filterTo(markers) { it in text }
                            NAMESPACE_REGEX.findAll(text).forEach {
                                namespaces += it.groupValues[1]
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return IndependentOracle(
            discoveryUri = "six://ex" in markers,
            extensionMetadata = "extension-id" in markers,
            contentProvider = ".contentprovider" in markers,
            devExtensionPath = "dev_ext" in markers,
            namespaces = namespaces,
        )
    }

    private fun scanText(input: java.io.InputStream, consume: (String) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var tail = ""
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            check(read > 0)
            val text = tail + buffer.decodeToString(endIndex = read)
            consume(text)
            tail = text.takeLast(512)
        }
    }

    private data class IndependentOracle(
        val discoveryUri: Boolean,
        val extensionMetadata: Boolean,
        val contentProvider: Boolean,
        val devExtensionPath: Boolean,
        val namespaces: Set<String>,
    ) {
        val wrapperContractComplete: Boolean
            get() = discoveryUri &&
                extensionMetadata &&
                contentProvider &&
                devExtensionPath
    }

    private fun MessageDigest.bytes(value: ByteArray) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        update(value)
    }

    private fun sha256(fileBytes: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = fileBytes.read(buffer)
            if (read < 0) break
            check(read > 0)
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
        const val INSTALLATION_DIGEST_DOMAIN =
            "sixbridge-browser-installation-apk-set-v1"
        val ORACLE_MARKERS = setOf(
            "six://ex",
            "extension-id",
            ".contentprovider",
            "dev_ext",
        )
        val NAMESPACE_REGEX =
            Regex("""["']namespace["']\s*:\s*["']([A-Za-z][A-Za-z0-9_.]*)["']""")
    }
}
