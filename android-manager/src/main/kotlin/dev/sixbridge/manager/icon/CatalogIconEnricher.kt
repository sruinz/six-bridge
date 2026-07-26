package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import java.util.concurrent.CancellationException

internal data class IconIdentity(
    val packageName: String,
    val extensionId: String,
    val versionCode: Long,
    val signerSha256: String,
)

internal class CatalogIconEnricher(
    private val catalog: ManagerCatalog,
    private val source: InstalledExtensionIconSource,
) {
    private val results = mutableMapOf<IconIdentity, ByteArray?>()

    suspend fun enrich(records: List<CatalogRecord>): List<CatalogRecord> =
        records.map { record ->
            if (record.iconPng != null) {
                return@map record
            }
            val identity = IconIdentity(
                packageName = record.packageName,
                extensionId = record.extensionId,
                versionCode = record.installedVersionCode,
                signerSha256 = record.signerSha256,
            )
            if (results.containsKey(identity)) {
                return@map results.getValue(identity)
                    ?.let { record.copy(iconPng = it) }
                    ?: record
            }
            val bytes = source.load(record.packageName)
            results[identity] = bytes
            if (bytes == null) {
                return@map record
            }
            val enriched = record.copy(iconPng = bytes)
            try {
                catalog.replaceVerified(enriched)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 현재 process 목록은 양성 cache의 bytes를 계속 사용한다.
            }
            enriched
        }
}
