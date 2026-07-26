package dev.sixbridge.manager.catalog

import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import kotlinx.coroutines.flow.first

data class CatalogReconciliation(
    val records: List<CatalogRecord>,
    val stalePackageNames: List<String>,
)

class CatalogReconciler {
    fun reconcile(
        catalogRecords: List<CatalogRecord>,
        installedWrappers: List<InstalledWrapper>,
    ): CatalogReconciliation {
        val recordsByPackage = catalogRecords.associateBy(CatalogRecord::packageName)
        val installedPackages = installedWrappers.mapTo(mutableSetOf(), InstalledWrapper::packageName)

        return CatalogReconciliation(
            records = installedWrappers.map { installed ->
                val existing = recordsByPackage[installed.packageName]
                val identityChanged = existing != null && (
                    existing.extensionId != installed.extensionId ||
                        existing.installedVersionCode != installed.versionCode ||
                        existing.signerSha256 != installed.signerSha256
                    )
                existing?.copy(
                    extensionId = installed.extensionId,
                    name = installed.name,
                    installedVersionCode = installed.versionCode,
                    signerSha256 = installed.signerSha256,
                    compatibilityStatus = if (identityChanged) {
                        "UNKNOWN"
                    } else {
                        existing.compatibilityStatus
                    },
                    reportJson = existing.reportJson.takeUnless { identityChanged },
                    lastVerifiedAtEpochMillis = existing.lastVerifiedAtEpochMillis
                        .takeUnless { identityChanged } ?: 0,
                ) ?: CatalogRecord(
                    packageName = installed.packageName,
                    extensionId = installed.extensionId,
                    name = installed.name,
                    iconPng = null,
                    installedVersionCode = installed.versionCode,
                    signerSha256 = installed.signerSha256,
                    compatibilityStatus = "UNKNOWN",
                    reportJson = null,
                    lastVerifiedAtEpochMillis = 0,
                )
            },
            stalePackageNames = catalogRecords
                .asSequence()
                .map(CatalogRecord::packageName)
                .filterNot(installedPackages::contains)
                .toList(),
        )
    }

    suspend fun reconcile(
        catalog: ManagerCatalog,
        installedWrappers: InstalledWrapperRepository,
    ): CatalogReconciliation {
        val reconciliation = reconcile(
            catalogRecords = catalog.observe().first(),
            installedWrappers = installedWrappers.findAll(),
        )
        catalog.removeAll(reconciliation.stalePackageNames.toSet())
        return reconciliation
    }
}
