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
        val archiveHashes = (listOf(base) + splits).map { archive ->
            archive.name to archive.inputStream().use(::sha256)
        }
        val expectedInstallationSha256 = installationApkSetSha256(archiveHashes)

        val result = AndroidSamsungBrowserProfileProvider(context).load()
        assertTrue(result is SamsungBrowserProfileResult.Supported)
        val supported = result as SamsungBrowserProfileResult.Supported
        val profile = supported.profile

        assertEquals(SAMSUNG_BROWSER_PACKAGE, packageInfo.packageName)
        assertEquals(1, signerSha256.size)
        assertEquals(BrowserProfile.SAMSUNG_SIGNER_SHA256, signerSha256.single())
        assertEquals(30, packageInfo.versionName.orEmpty().substringBefore('.').toInt())
        assertEquals(SAMSUNG_BROWSER_PACKAGE, profile.browserPackage)
        assertEquals(packageInfo.versionName, profile.versionName)
        assertEquals(packageInfo.longVersionCode, profile.versionCode)
        assertEquals(signerSha256.single(), profile.sourceApkSignerSha256)
        assertEquals(archiveHashes.first().second, profile.sourceApkSha256)
        assertEquals(expectedInstallationSha256, profile.installationApkSetSha256)
        assertTrue(profile.wrapperContract.isComplete)
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
    }
}
