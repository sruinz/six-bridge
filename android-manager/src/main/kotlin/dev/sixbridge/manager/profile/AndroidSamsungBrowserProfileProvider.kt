package dev.sixbridge.manager.profile

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import dev.sixbridge.core.profile.BrowserArchiveEvidenceException
import dev.sixbridge.core.profile.BrowserArchiveEvidenceScanner
import dev.sixbridge.core.profile.BrowserArchiveInput
import dev.sixbridge.core.profile.BrowserPackageEvidence
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.ProfileSupport
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSamsungBrowserProfileProvider internal constructor(
    private val packageLookup: SamsungBrowserPackageLookup,
    private val cache: SamsungBrowserProfileCache,
    private val scanner: BrowserArchiveEvidenceScanner = BrowserArchiveEvidenceScanner(),
) : SamsungBrowserProfileProvider {
    constructor(context: Context) : this(
        packageLookup = AndroidSamsungBrowserPackageLookup(context.packageManager),
        cache = SharedPreferencesSamsungBrowserProfileCache(
            context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    override suspend fun load(): SamsungBrowserProfileResult = withContext(Dispatchers.IO) {
        loadBlocking()
    }

    private fun loadBlocking(): SamsungBrowserProfileResult {
        val found = when (val result = packageLookup.load()) {
            SamsungBrowserPackageLookupResult.Missing ->
                return SamsungBrowserProfileResult.Missing
            is SamsungBrowserPackageLookupResult.Failure ->
                return unknown(result.reason)
            is SamsungBrowserPackageLookupResult.Found -> result
        }
        if (found.packageName != SAMSUNG_BROWSER_PACKAGE) {
            return unknown("package_mismatch")
        }
        if (found.signerSha256.size != 1) {
            return unknown("signer_count:${found.signerSha256.size}")
        }
        val signerSha256 = found.signerSha256.single()
        if (signerSha256 != BrowserProfile.SAMSUNG_SIGNER_SHA256) {
            return unknown("signer_mismatch")
        }
        val majorVersion = found.versionName.substringBefore('.').toIntOrNull()
        if (majorVersion != BrowserProfile.SUPPORTED_MAJOR_VERSION) {
            return unknown("unsupported_major:${majorVersion ?: 0}")
        }

        val evidence = BrowserPackageEvidence(
            packageName = found.packageName,
            versionName = found.versionName,
            versionCode = found.versionCode,
            signerSha256 = signerSha256,
            baseApk = found.baseApk,
            splitApks = found.splitApks,
        )
        val installationApkSetSha256 = try {
            evidence.installationApkSetSha256()
        } catch (_: Exception) {
            return unknown("archive_hash_failed")
        }
        val cacheKey = cacheKey(
            versionCode = found.versionCode,
            signerSha256 = signerSha256,
            installationApkSetSha256 = installationApkSetSha256,
        )
        cachedProfile(cacheKey, evidence, installationApkSetSha256)?.let {
            return resultFor(it)
        }

        val profile = try {
            scanner.scan(evidence)
        } catch (error: BrowserArchiveEvidenceException) {
            return unknown("archive_evidence:${error.failure.name.lowercase()}")
        } catch (_: Exception) {
            return unknown("archive_scan_failed")
        }
        runCatching {
            cache.save(
                CachedSamsungBrowserProfile(
                    cacheKey = cacheKey,
                    encodedProfile = BrowserProfileJson.encode(profile),
                ),
            )
        }
        return resultFor(profile)
    }

    private fun cachedProfile(
        expectedKey: String,
        evidence: BrowserPackageEvidence,
        installationApkSetSha256: String,
    ): BrowserProfile? {
        val cached = runCatching(cache::load).getOrNull() ?: return null
        if (cached.cacheKey != expectedKey) return null
        val profile = runCatching {
            BrowserProfileJson.decode(cached.encodedProfile)
        }.getOrNull() ?: return null
        return profile.takeIf {
            it.browserPackage == evidence.packageName &&
                it.versionName == evidence.versionName &&
                it.versionCode == evidence.versionCode &&
                it.majorVersion == BrowserProfile.SUPPORTED_MAJOR_VERSION &&
                it.sourceApkSha256 == evidence.baseApk.sha256 &&
                it.installationApkSetSha256 == installationApkSetSha256 &&
                it.sourceApkSignerSha256 == evidence.signerSha256
        }
    }

    private fun resultFor(profile: BrowserProfile): SamsungBrowserProfileResult {
        if (profile.support == ProfileSupport.SUPPORTED) {
            return SamsungBrowserProfileResult.Supported(profile)
        }
        val reasons = buildList {
            if (!profile.wrapperContract.discoveryUri) add("wrapper_contract:discovery_uri")
            if (!profile.wrapperContract.extensionMetadata) {
                add("wrapper_contract:extension_metadata")
            }
            if (!profile.wrapperContract.contentProvider) {
                add("wrapper_contract:content_provider")
            }
            if (!profile.wrapperContract.devExtensionPath) {
                add("wrapper_contract:dev_extension_path")
            }
            addAll(profile.profileDifferences)
            if (isEmpty()) add("profile_not_supported")
        }
        return SamsungBrowserProfileResult.Unknown(profile, reasons)
    }

    private fun unknown(reason: String) =
        SamsungBrowserProfileResult.Unknown(profile = null, reasons = listOf(reason))

    private fun cacheKey(
        versionCode: Long,
        signerSha256: String,
        installationApkSetSha256: String,
    ): String = "$versionCode:$signerSha256:$installationApkSetSha256"

    private companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
        const val CACHE_PREFERENCES = "samsung-browser-profile"
    }
}

internal fun interface SamsungBrowserPackageLookup {
    fun load(): SamsungBrowserPackageLookupResult
}

internal sealed interface SamsungBrowserPackageLookupResult {
    data object Missing : SamsungBrowserPackageLookupResult
    data class Failure(val reason: String) : SamsungBrowserPackageLookupResult
    data class Found(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val signerSha256: List<String>,
        val baseApk: BrowserArchiveInput,
        val splitApks: List<BrowserArchiveInput>,
    ) : SamsungBrowserPackageLookupResult
}

internal data class CachedSamsungBrowserProfile(
    val cacheKey: String,
    val encodedProfile: String,
)

internal interface SamsungBrowserProfileCache {
    fun load(): CachedSamsungBrowserProfile?
    fun save(value: CachedSamsungBrowserProfile)
}

private class AndroidSamsungBrowserPackageLookup(
    private val packageManager: PackageManager,
) : SamsungBrowserPackageLookup {
    override fun load(): SamsungBrowserPackageLookupResult {
        val info = try {
            packageInfo()
        } catch (_: PackageManager.NameNotFoundException) {
            return SamsungBrowserPackageLookupResult.Missing
        } catch (_: Exception) {
            return SamsungBrowserPackageLookupResult.Failure("package_read_failed")
        }
        return try {
            val applicationInfo = info.applicationInfo
                ?: return SamsungBrowserPackageLookupResult.Failure(
                    "application_info_missing",
                )
            val sourceDir = applicationInfo.sourceDir
                ?.takeIf(String::isNotBlank)
                ?: return SamsungBrowserPackageLookupResult.Failure(
                    "base_apk_missing",
                )
            SamsungBrowserPackageLookupResult.Found(
                packageName = info.packageName,
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                signerSha256 = info.signingInfo
                    ?.apkContentsSigners
                    .orEmpty()
                    .map { sha256(it.toByteArray()) },
                baseApk = FileBrowserArchiveInput(File(sourceDir)),
                splitApks = applicationInfo.splitSourceDirs
                    .orEmpty()
                    .map { FileBrowserArchiveInput(File(it)) },
            )
        } catch (_: Exception) {
            SamsungBrowserPackageLookupResult.Failure("package_facts_invalid")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() =
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

    private companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
    }
}

private class FileBrowserArchiveInput(
    private val file: File,
) : BrowserArchiveInput {
    override val name: String = file.name
    override val sha256: String by lazy(LazyThreadSafetyMode.NONE) {
        file.inputStream().use(::sha256)
    }

    override fun openStream(): InputStream = file.inputStream()
}

private class SharedPreferencesSamsungBrowserProfileCache(
    private val preferences: SharedPreferences,
) : SamsungBrowserProfileCache {
    override fun load(): CachedSamsungBrowserProfile? {
        val cacheKey = preferences.getString(KEY_CACHE_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val encodedProfile = preferences.getString(KEY_ENCODED_PROFILE, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return CachedSamsungBrowserProfile(cacheKey, encodedProfile)
    }

    override fun save(value: CachedSamsungBrowserProfile) {
        preferences.edit()
            .putString(KEY_CACHE_KEY, value.cacheKey)
            .putString(KEY_ENCODED_PROFILE, value.encodedProfile)
            .commit()
    }

    private companion object {
        const val KEY_CACHE_KEY = "cache-key"
        const val KEY_ENCODED_PROFILE = "encoded-profile"
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) error("archive stream returned zero bytes")
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
