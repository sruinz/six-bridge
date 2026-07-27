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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidSamsungBrowserProfileProvider internal constructor(
    private val packageLookup: SamsungBrowserPackageLookup,
    private val cache: SamsungBrowserProfileCache,
    private val scannerFactory: (() -> Unit) -> BrowserArchiveEvidenceScanner = {
        BrowserArchiveEvidenceScanner(checkCancelled = it)
    },
) : SamsungBrowserProfileProvider {
    constructor(context: Context) : this(
        packageLookup = AndroidSamsungBrowserPackageLookup(context.packageManager),
        cache = SharedPreferencesSamsungBrowserProfileCache(
            context.getSharedPreferences(CACHE_PREFERENCES, Context.MODE_PRIVATE),
        ),
    )

    override suspend fun load(): SamsungBrowserProfileResult = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        loadBlocking { context.ensureActive() }
    }

    private fun loadBlocking(checkCancelled: () -> Unit): SamsungBrowserProfileResult {
        val initial = when (val result = loadSnapshot(checkCancelled)) {
            SnapshotLookup.Missing -> return SamsungBrowserProfileResult.Missing
            is SnapshotLookup.Rejected -> return unknown(result.reason)
            is SnapshotLookup.Valid -> result.snapshot
        }

        cachedProfile(initial)?.let { cached ->
            if (!snapshotStillMatches(initial, checkCancelled)) {
                return unknown(PACKAGE_CHANGED)
            }
            return resultFor(cached)
        }

        val profile = try {
            scannerFactory(checkCancelled).scan(initial.evidence)
        } catch (error: BrowserArchiveEvidenceException) {
            return unknown("archive_evidence:${error.failure.name.lowercase()}")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return unknown("archive_scan_failed")
        }
        if (!snapshotStillMatches(initial, checkCancelled)) {
            return unknown(PACKAGE_CHANGED)
        }

        val encodedProfile = BrowserProfileJson.encode(profile)
        val profileDigest = profile.canonicalSha256()
        try {
            cache.save(
                CachedSamsungBrowserProfile(
                    domain = CACHE_DOMAIN,
                    schemaVersion = CACHE_SCHEMA_VERSION,
                    profileDigest = profileDigest,
                    cacheKey = cacheKey(initial.fingerprint, profileDigest),
                    encodedProfile = encodedProfile,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 현재 scan 결과는 안전하며, 저장 실패 시 다음 load가 다시 검사한다.
        }
        return resultFor(profile)
    }

    private fun snapshotStillMatches(
        initial: InstalledSnapshot,
        checkCancelled: () -> Unit,
    ): Boolean {
        checkCancelled()
        val current = loadSnapshot(checkCancelled)
        return current is SnapshotLookup.Valid &&
            current.snapshot.fingerprint == initial.fingerprint
    }

    private fun loadSnapshot(checkCancelled: () -> Unit): SnapshotLookup {
        val found = when (val result = packageLookup.load(checkCancelled)) {
            SamsungBrowserPackageLookupResult.Missing -> return SnapshotLookup.Missing
            is SamsungBrowserPackageLookupResult.Failure ->
                return SnapshotLookup.Rejected(result.reason)
            is SamsungBrowserPackageLookupResult.Found -> result
        }
        if (found.packageName != SAMSUNG_BROWSER_PACKAGE) {
            return SnapshotLookup.Rejected("package_mismatch")
        }
        if (found.signerSha256.size != 1) {
            return SnapshotLookup.Rejected("signer_count:${found.signerSha256.size}")
        }
        val signerSha256 = found.signerSha256.single()
        if (signerSha256 != BrowserProfile.SAMSUNG_SIGNER_SHA256) {
            return SnapshotLookup.Rejected("signer_mismatch")
        }
        val majorVersion = found.versionName.substringBefore('.').toIntOrNull()
        if (majorVersion != BrowserProfile.SUPPORTED_MAJOR_VERSION) {
            return SnapshotLookup.Rejected("unsupported_major:${majorVersion ?: 0}")
        }

        val evidence = BrowserPackageEvidence(
            packageName = found.packageName,
            versionName = found.versionName,
            versionCode = found.versionCode,
            signerSha256 = signerSha256,
            baseApk = found.baseApk,
            splitApks = found.splitApks,
        )
        return try {
            checkCancelled()
            val installationApkSetSha256 = evidence.installationApkSetSha256()
            SnapshotLookup.Valid(
                InstalledSnapshot(
                    evidence = evidence,
                    fingerprint = InstalledFingerprint(
                        packageName = found.packageName,
                        versionName = found.versionName,
                        versionCode = found.versionCode,
                        signerSha256 = signerSha256,
                        baseApkSha256 = found.baseApk.sha256,
                        installationApkSetSha256 = installationApkSetSha256,
                    ),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SnapshotLookup.Rejected("archive_hash_failed")
        }
    }

    private fun cachedProfile(snapshot: InstalledSnapshot): BrowserProfile? {
        val cached = try {
            cache.load()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        if (
            cached.domain != CACHE_DOMAIN ||
            cached.schemaVersion != CACHE_SCHEMA_VERSION ||
            !SHA256.matches(cached.profileDigest)
        ) {
            return null
        }
        val profile = try {
            BrowserProfileJson.decode(cached.encodedProfile)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        if (profile.canonicalSha256() != cached.profileDigest) return null
        if (
            cached.cacheKey != cacheKey(snapshot.fingerprint, cached.profileDigest)
        ) {
            return null
        }
        val facts = snapshot.fingerprint
        return profile.takeIf {
            it.browserPackage == facts.packageName &&
                it.versionName == facts.versionName &&
                it.versionCode == facts.versionCode &&
                it.majorVersion == BrowserProfile.SUPPORTED_MAJOR_VERSION &&
                it.sourceApkSha256 == facts.baseApkSha256 &&
                it.installationApkSetSha256 == facts.installationApkSetSha256 &&
                it.sourceApkSignerSha256 == facts.signerSha256
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
        fingerprint: InstalledFingerprint,
        profileDigest: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.bytes(CACHE_KEY_DOMAIN.toByteArray(UTF_8))
        digest.bytes(fingerprint.packageName.toByteArray(UTF_8))
        digest.bytes(fingerprint.versionName.toByteArray(UTF_8))
        digest.bytes(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(fingerprint.versionCode).array())
        digest.bytes(fingerprint.signerSha256.toByteArray(UTF_8))
        digest.bytes(fingerprint.installationApkSetSha256.toByteArray(UTF_8))
        digest.bytes(profileDigest.toByteArray(UTF_8))
        return digest.hex()
    }

    private data class InstalledSnapshot(
        val evidence: BrowserPackageEvidence,
        val fingerprint: InstalledFingerprint,
    )

    private data class InstalledFingerprint(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val signerSha256: String,
        val baseApkSha256: String,
        val installationApkSetSha256: String,
    )

    private sealed interface SnapshotLookup {
        data object Missing : SnapshotLookup
        data class Rejected(val reason: String) : SnapshotLookup
        data class Valid(val snapshot: InstalledSnapshot) : SnapshotLookup
    }

    internal companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
        const val CACHE_PREFERENCES = "samsung-browser-profile"
        const val CACHE_DOMAIN = "sixbridge-samsung-browser-profile"
        const val CACHE_SCHEMA_VERSION = 2
        private const val CACHE_KEY_DOMAIN =
            "sixbridge-samsung-browser-profile-cache-key-v2"
        private const val PACKAGE_CHANGED = "package_changed"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal fun interface SamsungBrowserPackageLookup {
    fun load(checkCancelled: () -> Unit): SamsungBrowserPackageLookupResult
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
    val domain: String,
    val schemaVersion: Int,
    val profileDigest: String,
    val cacheKey: String,
    val encodedProfile: String,
)

internal interface SamsungBrowserProfileCache {
    fun load(): CachedSamsungBrowserProfile?
    fun save(value: CachedSamsungBrowserProfile): Boolean
}

private class AndroidSamsungBrowserPackageLookup(
    private val packageManager: PackageManager,
) : SamsungBrowserPackageLookup {
    override fun load(checkCancelled: () -> Unit): SamsungBrowserPackageLookupResult {
        checkCancelled()
        val info = try {
            packageInfo()
        } catch (_: PackageManager.NameNotFoundException) {
            return SamsungBrowserPackageLookupResult.Missing
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SamsungBrowserPackageLookupResult.Failure("package_read_failed")
        }
        return try {
            checkCancelled()
            val applicationInfo = info.applicationInfo
                ?: return SamsungBrowserPackageLookupResult.Failure(
                    "application_info_missing",
                )
            val sourceDir = applicationInfo.sourceDir
                ?.takeIf(String::isNotBlank)
                ?: return SamsungBrowserPackageLookupResult.Failure(
                    "base_apk_missing",
                )
            val archives = androidArchiveInputs(
                sourceDir = sourceDir,
                splitNames = info.splitNames.orEmpty().toList(),
                splitSourceDirs = applicationInfo.splitSourceDirs.orEmpty().toList(),
                checkCancelled = checkCancelled,
            )
            SamsungBrowserPackageLookupResult.Found(
                packageName = info.packageName,
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
                signerSha256 = info.signingInfo
                    ?.apkContentsSigners
                    .orEmpty()
                    .map {
                        checkCancelled()
                        sha256(it.toByteArray())
                    },
                baseApk = archives.baseApk,
                splitApks = archives.splitApks,
            )
        } catch (error: CancellationException) {
            throw error
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

internal data class AndroidArchiveInputs(
    val baseApk: BrowserArchiveInput,
    val splitApks: List<BrowserArchiveInput>,
)

internal fun androidArchiveInputs(
    sourceDir: String,
    splitNames: List<String>,
    splitSourceDirs: List<String>,
    checkCancelled: () -> Unit,
): AndroidArchiveInputs {
    require(sourceDir.isNotBlank())
    require(splitNames.size == splitSourceDirs.size)
    return AndroidArchiveInputs(
        baseApk = FileBrowserArchiveInput(
            file = File(sourceDir),
            name = "base.apk",
            checkCancelled = checkCancelled,
            maxBytes = MAX_ARCHIVE_RAW_BYTES,
        ),
        splitApks = splitNames.zip(splitSourceDirs).map { (splitName, splitSourceDir) ->
            FileBrowserArchiveInput(
                file = File(splitSourceDir),
                name = "split_$splitName.apk",
                checkCancelled = checkCancelled,
                maxBytes = MAX_ARCHIVE_RAW_BYTES,
            )
        },
    )
}

private const val MAX_ARCHIVE_RAW_BYTES = 512L * 1024 * 1024

private class FileBrowserArchiveInput(
    private val file: File,
    override val name: String,
    private val checkCancelled: () -> Unit,
    private val maxBytes: Long,
) : BrowserArchiveInput {
    override val sha256: String by lazy(LazyThreadSafetyMode.NONE) {
        file.inputStream().use {
            sha256(it, checkCancelled, maxBytes)
        }
    }

    override fun openStream(): InputStream {
        checkCancelled()
        return file.inputStream()
    }
}

private class SharedPreferencesSamsungBrowserProfileCache(
    private val preferences: SharedPreferences,
) : SamsungBrowserProfileCache {
    override fun load(): CachedSamsungBrowserProfile? {
        val domain = preferences.getString(KEY_DOMAIN, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, 0)
            .takeIf { it > 0 }
            ?: return null
        val profileDigest = preferences.getString(KEY_PROFILE_DIGEST, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val cacheKey = preferences.getString(KEY_CACHE_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val encodedProfile = preferences.getString(KEY_ENCODED_PROFILE, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return CachedSamsungBrowserProfile(
            domain = domain,
            schemaVersion = schemaVersion,
            profileDigest = profileDigest,
            cacheKey = cacheKey,
            encodedProfile = encodedProfile,
        )
    }

    override fun save(value: CachedSamsungBrowserProfile): Boolean {
        val saved = preferences.edit()
            .putString(KEY_DOMAIN, value.domain)
            .putInt(KEY_SCHEMA_VERSION, value.schemaVersion)
            .putString(KEY_PROFILE_DIGEST, value.profileDigest)
            .putString(KEY_CACHE_KEY, value.cacheKey)
            .putString(KEY_ENCODED_PROFILE, value.encodedProfile)
            .commit()
        if (!saved) {
            preferences.edit()
                .remove(KEY_DOMAIN)
                .remove(KEY_SCHEMA_VERSION)
                .remove(KEY_PROFILE_DIGEST)
                .remove(KEY_CACHE_KEY)
                .remove(KEY_ENCODED_PROFILE)
                .apply()
        }
        return saved
    }

    private companion object {
        const val KEY_DOMAIN = "domain"
        const val KEY_SCHEMA_VERSION = "schema-version"
        const val KEY_PROFILE_DIGEST = "profile-digest"
        const val KEY_CACHE_KEY = "cache-key"
        const val KEY_ENCODED_PROFILE = "encoded-profile"
    }
}

private fun sha256(
    input: InputStream,
    checkCancelled: () -> Unit,
    maxBytes: Long,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        checkCancelled()
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) error("archive stream returned zero bytes")
        totalBytes += read
        require(totalBytes <= maxBytes) { "archive exceeds raw byte limit" }
        digest.update(buffer, 0, read)
    }
    return digest.hex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun MessageDigest.bytes(value: ByteArray) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
    update(value)
}

private fun MessageDigest.hex(): String =
    digest().joinToString("") { "%02x".format(it) }
