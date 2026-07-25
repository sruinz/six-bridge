package dev.sixbridge.manager.wrapper

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import java.security.MessageDigest

class PackageManagerInstalledWrapperRepository(
    private val gateway: InstalledWrapperPackageGateway,
) : InstalledWrapperRepository {
    constructor(packageManager: PackageManager) : this(
        AndroidInstalledWrapperPackageGateway(packageManager),
    )

    override suspend fun findAll(): List<InstalledWrapper> = gateway.querySixExtensionPackages()
        .mapNotNull(::validatedWrapper)
        .sortedWith(
            compareBy<InstalledWrapper>({ it.packageName }, { it.extensionId }, { it.name }, { it.versionCode }),
        )
        .distinctBy(InstalledWrapper::packageName)

    override suspend fun find(packageName: String): InstalledWrapper? =
        findAll().singleOrNull { it.packageName == packageName }

    private fun validatedWrapper(facts: InstalledWrapperPackageFacts): InstalledWrapper? {
        val extensionId = facts.metadata[EXTENSION_ID_METADATA]
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (!EXTENSION_ID.matches(extensionId)) return null
        if (facts.packageName != "$PACKAGE_PREFIX$extensionId") return null
        if (facts.signerSha256.size != 1) return null
        val activity = facts.activities.singleOrNull() ?: return null
        if (activity.name != DISCOVERY_ACTIVITY || !activity.exported) return null
        val provider = facts.providers.singleOrNull() ?: return null
        if (provider.name != EXTENSION_PROVIDER ||
            !provider.exported ||
            provider.authority != "${facts.packageName}.contentprovider"
        ) {
            return null
        }
        return InstalledWrapper(
            packageName = facts.packageName,
            extensionId = extensionId,
            name = facts.applicationLabel.trim().ifEmpty { extensionId },
            versionCode = facts.versionCode,
            signerSha256 = facts.signerSha256.single(),
        )
    }

    private companion object {
        const val EXTENSION_ID_METADATA = "extension-id"
        const val PACKAGE_PREFIX = "dev.sixbridge.ext."
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val EXTENSION_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
        val EXTENSION_ID = Regex("[a-p]{32}")
    }
}

fun interface InstalledWrapperPackageGateway {
    fun querySixExtensionPackages(): List<InstalledWrapperPackageFacts>
}

data class InstalledWrapperPackageFacts(
    val packageName: String,
    val applicationLabel: String,
    val versionCode: Long,
    val signerSha256: List<String>,
    val metadata: Map<String, String>,
    val activities: List<InstalledWrapperActivity>,
    val providers: List<InstalledWrapperProvider>,
)

data class InstalledWrapperActivity(
    val name: String,
    val exported: Boolean,
)

data class InstalledWrapperProvider(
    val name: String,
    val authority: String,
    val exported: Boolean,
)

private class AndroidInstalledWrapperPackageGateway(
    private val packageManager: PackageManager,
) : InstalledWrapperPackageGateway {
    override fun querySixExtensionPackages(): List<InstalledWrapperPackageFacts> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("six://ex"))
        val packageNames = queryActivities(intent)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
        return packageNames.mapNotNull(::packageFacts)
    }

    private fun queryActivities(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

    private fun packageFacts(packageName: String): InstalledWrapperPackageFacts? = runCatching {
        val info = packageInfo(packageName)
        val application = info.applicationInfo ?: return null
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        InstalledWrapperPackageFacts(
            packageName = info.packageName,
            applicationLabel = application.loadLabel(packageManager).toString(),
            versionCode = info.longVersionCode,
            signerSha256 = signers.map { sha256(it.toByteArray()) },
            metadata = application.metaData?.getString(EXTENSION_ID_METADATA)
                ?.let { mapOf(EXTENSION_ID_METADATA to it) }
                .orEmpty(),
            activities = info.activities.orEmpty().mapNotNull { activity ->
                activity.name?.let { InstalledWrapperActivity(it, activity.exported) }
            },
            providers = info.providers.orEmpty().mapNotNull { provider ->
                val name = provider.name ?: return@mapNotNull null
                val authority = provider.authority ?: return@mapNotNull null
                InstalledWrapperProvider(name, authority, provider.exported)
            },
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()),
            )
        } else {
            packageManager.getPackageInfo(packageName, PACKAGE_FLAGS)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXTENSION_ID_METADATA = "extension-id"
        const val PACKAGE_FLAGS =
            PackageManager.GET_META_DATA or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_SIGNING_CERTIFICATES
    }
}
