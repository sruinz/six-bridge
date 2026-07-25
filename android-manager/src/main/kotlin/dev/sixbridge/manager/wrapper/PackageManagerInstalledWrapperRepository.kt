package dev.sixbridge.manager.wrapper

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.security.MessageDigest

class PackageManagerInstalledWrapperRepository(
    private val gateway: InstalledWrapperPackageGateway,
) : InstalledWrapperRepository {
    constructor(packageManager: PackageManager) : this(
        AndroidInstalledWrapperPackageGateway(packageManager),
    )

    override suspend fun findAll(): List<InstalledWrapper> = gateway.querySixExtensionPackages()
        .groupBy(InstalledWrapperPackageFacts::packageName)
        .filterValues { it.size == 1 }
        .values
        .mapNotNull { it.singleOrNull()?.let(::validatedWrapper) }
        .sortedBy(InstalledWrapper::packageName)

    override suspend fun find(packageName: String): InstalledWrapper? =
        findAll().singleOrNull { it.packageName == packageName }

    private fun validatedWrapper(facts: InstalledWrapperPackageFacts): InstalledWrapper? {
        val extensionId = facts.metadata[EXTENSION_ID_METADATA]
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (!EXTENSION_ID.matches(extensionId)) return null
        if (facts.packageName != "$PACKAGE_PREFIX$extensionId") return null
        if (facts.signerSha256.size != 1) return null
        if (facts.matchedActivity.packageName != facts.packageName ||
            facts.matchedActivity.name != DISCOVERY_ACTIVITY ||
            !facts.matchedActivity.exported
        ) {
            return null
        }
        val filter = facts.resolvedIntentFilter
        if (filter.actionCount != 1 ||
            filter.actions != setOf(VIEW_ACTION) ||
            filter.categoryCount != 2 ||
            filter.categories != setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY) ||
            filter.schemeCount != 1 ||
            filter.schemes != setOf(SIX_SCHEME) ||
            filter.authorityCount != 1 ||
            filter.authorityHosts != setOf(EX_HOST) ||
            filter.authorityPorts != setOf(null) ||
            filter.dataTypeCount != 0 ||
            filter.dataTypes.isNotEmpty() ||
            filter.dataPathCount != 0 ||
            filter.dataPaths.isNotEmpty() ||
            filter.schemeSpecificPartCount != 0
        ) {
            return null
        }
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
        const val VIEW_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
        const val SIX_SCHEME = "six"
        const val EX_HOST = "ex"
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
    val matchedActivity: ResolvedDiscoveryActivity,
    val resolvedIntentFilter: ResolvedDiscoveryIntentFilter,
    val metadata: Map<String, String>,
    val activities: List<InstalledWrapperActivity>,
    val providers: List<InstalledWrapperProvider>,
)

data class ResolvedDiscoveryActivity(
    val packageName: String,
    val name: String,
    val exported: Boolean,
)

data class ResolvedDiscoveryIntentFilter(
    val actions: Set<String>,
    val actionCount: Int,
    val categories: Set<String>,
    val categoryCount: Int,
    val schemes: Set<String>,
    val schemeCount: Int,
    val authorityHosts: Set<String>,
    val authorityPorts: Set<Int?>,
    val authorityCount: Int,
    val dataTypes: Set<String>,
    val dataTypeCount: Int,
    val dataPaths: Set<String>,
    val dataPathCount: Int,
    val schemeSpecificPartCount: Int,
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
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return queryActivities(intent).mapNotNull(::packageFacts)
    }

    private fun queryActivities(intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(QUERY_FLAGS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, QUERY_FLAGS)
        }

    private fun packageFacts(resolveInfo: android.content.pm.ResolveInfo): InstalledWrapperPackageFacts? = runCatching {
        val matched = resolveInfo.activityInfo ?: return null
        val matchedName = matched.name ?: return null
        val filter = resolveInfo.filter ?: return null
        val packageName = matched.packageName ?: return null
        val info = packageInfo(packageName)
        val application = info.applicationInfo ?: return null
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        InstalledWrapperPackageFacts(
            packageName = info.packageName,
            applicationLabel = application.loadLabel(packageManager).toString(),
            versionCode = info.longVersionCode,
            signerSha256 = signers.map { sha256(it.toByteArray()) },
            matchedActivity = ResolvedDiscoveryActivity(
                packageName = packageName,
                name = matchedName,
                exported = matched.exported,
            ),
            resolvedIntentFilter = filterFacts(filter),
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

    private fun filterFacts(filter: IntentFilter): ResolvedDiscoveryIntentFilter {
        val authorities = filter.authoritiesIterator()?.asSequence()?.toList().orEmpty()
        return ResolvedDiscoveryIntentFilter(
            actions = filter.actionsIterator()?.asSequence()?.toSet().orEmpty(),
            actionCount = filter.countActions(),
            categories = filter.categoriesIterator()?.asSequence()?.toSet().orEmpty(),
            categoryCount = filter.countCategories(),
            schemes = filter.schemesIterator()?.asSequence()?.toSet().orEmpty(),
            schemeCount = filter.countDataSchemes(),
            authorityHosts = authorities.map { it.host }.toSet(),
            authorityPorts = authorities.map { it.port.takeIf { port -> port >= 0 } }.toSet(),
            authorityCount = filter.countDataAuthorities(),
            dataTypes = filter.typesIterator()?.asSequence()?.toSet().orEmpty(),
            dataTypeCount = filter.countDataTypes(),
            dataPaths = filter.pathsIterator()?.asSequence()?.map { it.path }?.toSet().orEmpty(),
            dataPathCount = filter.countDataPaths(),
            schemeSpecificPartCount = filter.countDataSchemeSpecificParts(),
        )
    }

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
        const val QUERY_FLAGS = PackageManager.MATCH_ALL or PackageManager.GET_RESOLVED_FILTER
        const val PACKAGE_FLAGS =
            PackageManager.GET_META_DATA or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_SIGNING_CERTIFICATES
    }
}
