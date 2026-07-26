package dev.sixbridge.manager.catalog

import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogReconcilerTest {
    @Test
    fun `uses installed wrappers as the source of truth and marks stale catalog entries for removal`() {
        val matching = catalogRecord(
            packageName = "dev.sixbridge.ext.matching",
            installedVersionCode = 1,
            signerSha256 = "a".repeat(64),
            reportJson = "{\"report\":\"matching\"}",
        )
        val stale = catalogRecord(packageName = "dev.sixbridge.ext.stale")
        val changed = catalogRecord(
            packageName = "dev.sixbridge.ext.changed",
            installedVersionCode = 4,
            signerSha256 = "b".repeat(64),
            reportJson = "{\"report\":\"previous\"}",
        )

        val result = CatalogReconciler().reconcile(
            catalogRecords = listOf(matching, stale, changed),
            installedWrappers = listOf(
                installedWrapper("dev.sixbridge.ext.matching", versionCode = 2, signerSha256 = "c".repeat(64)),
                installedWrapper("dev.sixbridge.ext.external", versionCode = 3, signerSha256 = "d".repeat(64)),
                installedWrapper("dev.sixbridge.ext.changed", versionCode = 5, signerSha256 = "e".repeat(64)),
            ),
        )

        assertEquals(listOf("dev.sixbridge.ext.stale"), result.stalePackageNames)
        assertEquals(
            listOf(
                matching.copy(
                    name = "matching name",
                    iconPng = null,
                    installedVersionCode = 2,
                    signerSha256 = "c".repeat(64),
                    compatibilityStatus = "UNKNOWN",
                    reportJson = null,
                    lastVerifiedAtEpochMillis = 0,
                ),
                catalogRecord(
                    packageName = "dev.sixbridge.ext.external",
                    extensionId = "external",
                    name = "external name",
                    iconPng = null,
                    installedVersionCode = 3,
                    signerSha256 = "d".repeat(64),
                    compatibilityStatus = "UNKNOWN",
                    reportJson = null,
                    lastVerifiedAtEpochMillis = 0,
                ),
                changed.copy(
                    name = "changed name",
                    iconPng = null,
                    installedVersionCode = 5,
                    signerSha256 = "e".repeat(64),
                    compatibilityStatus = "UNKNOWN",
                    reportJson = null,
                    lastVerifiedAtEpochMillis = 0,
                ),
            ),
            result.records,
        )
        assertNull(result.records.single { it.packageName == "dev.sixbridge.ext.external" }.reportJson)
    }

    @Test
    fun `extension ID versionCode signer 중 하나가 바뀌면 기존 icon을 무효화한다`() {
        val extensionChangedPackage = "dev.sixbridge.ext.extension-changed"
        val versionChangedPackage = "dev.sixbridge.ext.version-changed"
        val signerChangedPackage = "dev.sixbridge.ext.signer-changed"
        val catalogRecords = listOf(
            catalogRecord(
                packageName = extensionChangedPackage,
                extensionId = "old-extension-id",
            ),
            catalogRecord(
                packageName = versionChangedPackage,
                installedVersionCode = 1,
            ),
            catalogRecord(
                packageName = signerChangedPackage,
                signerSha256 = "a".repeat(64),
            ),
        )

        val result = CatalogReconciler().reconcile(
            catalogRecords = catalogRecords,
            installedWrappers = listOf(
                installedWrapper(
                    packageName = extensionChangedPackage,
                    extensionId = "new-extension-id",
                    versionCode = 1,
                    signerSha256 = "a".repeat(64),
                ),
                installedWrapper(
                    packageName = versionChangedPackage,
                    versionCode = 2,
                    signerSha256 = "a".repeat(64),
                ),
                installedWrapper(
                    packageName = signerChangedPackage,
                    versionCode = 1,
                    signerSha256 = "b".repeat(64),
                ),
            ),
        )

        result.records.forEach { record ->
            assertNull(record.iconPng, "${record.packageName} icon은 무효화되어야 합니다.")
        }
    }

    @Test
    fun `queries installed wrappers and removes all stale catalog entries in one batch`() = runBlocking {
        val staleFirst = catalogRecord(packageName = "dev.sixbridge.ext.stale-first")
        val staleSecond = catalogRecord(packageName = "dev.sixbridge.ext.stale-second")
        val catalog = FakeCatalog(listOf(staleFirst, staleSecond))
        val installed = installedWrapper(
            packageName = "dev.sixbridge.ext.external",
            versionCode = 3,
            signerSha256 = "d".repeat(64),
        )

        val result = CatalogReconciler().reconcile(
            catalog = catalog,
            installedWrappers = object : InstalledWrapperRepository {
                override suspend fun findAll(): List<InstalledWrapper> = listOf(installed)

                override suspend fun find(packageName: String): InstalledWrapper? =
                    installed.takeIf { it.packageName == packageName }
            },
        )

        assertEquals(
            listOf(setOf(staleFirst.packageName, staleSecond.packageName)),
            catalog.removedPackageNameBatches,
        )
        assertEquals(listOf(installed.packageName), result.records.map(CatalogRecord::packageName))
    }

    private fun catalogRecord(
        packageName: String,
        extensionId: String = packageName.substringAfterLast('.'),
        name: String = "$extensionId catalog name",
        iconPng: ByteArray? = byteArrayOf(1, 2, 3),
        installedVersionCode: Long = 1,
        signerSha256: String = "a".repeat(64),
        compatibilityStatus: String = "COMPATIBLE",
        reportJson: String? = "{\"report\":\"known\"}",
        lastVerifiedAtEpochMillis: Long = 99,
    ) = CatalogRecord(
        packageName = packageName,
        extensionId = extensionId,
        name = name,
        iconPng = iconPng,
        installedVersionCode = installedVersionCode,
        signerSha256 = signerSha256,
        compatibilityStatus = compatibilityStatus,
        reportJson = reportJson,
        lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
    )

    private fun installedWrapper(
        packageName: String,
        extensionId: String = packageName.substringAfterLast('.'),
        versionCode: Long,
        signerSha256: String,
    ) = InstalledWrapper(
        packageName = packageName,
        extensionId = extensionId,
        name = "${packageName.substringAfterLast('.')} name",
        versionCode = versionCode,
        signerSha256 = signerSha256,
    )

    private class FakeCatalog(
        private val records: List<CatalogRecord>,
    ) : ManagerCatalog {
        val removedPackageNameBatches = mutableListOf<Set<String>>()

        override fun observe(): Flow<List<CatalogRecord>> = flowOf(records)

        override suspend fun replaceVerified(record: CatalogRecord) = Unit

        override suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord =
            records.singleOrNull { it.packageName == discovered.packageName } ?: discovered

        override suspend fun persistIconIfIdentity(
            identity: CatalogIdentity,
            iconPng: ByteArray,
        ): CatalogRecord? = null

        override suspend fun remove(packageName: String) {
            throw AssertionError("CatalogReconciler must use removeAll instead of remove")
        }

        override suspend fun removeAll(packageNames: Set<String>) {
            removedPackageNameBatches += packageNames
        }

        override suspend fun get(packageName: String): CatalogRecord? =
            records.singleOrNull { it.packageName == packageName }
    }
}
