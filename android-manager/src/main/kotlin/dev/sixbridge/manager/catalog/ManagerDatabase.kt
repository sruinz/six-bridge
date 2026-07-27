package dev.sixbridge.manager.catalog

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sixbridge.manager.conversion.ConversionIdentityDao
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import dev.sixbridge.manager.conversion.ConversionIdentityLeaseEntity
import dev.sixbridge.manager.conversion.ConversionJobDao
import dev.sixbridge.manager.conversion.ConversionJobEntity

@Database(
    entities = [
        CatalogEntryEntity::class,
        ConversionIdentityEntity::class,
        ConversionIdentityLeaseEntity::class,
        ConversionJobEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ManagerDatabase : RoomDatabase() {
    abstract fun catalogEntryDao(): CatalogEntryDao

    abstract fun conversionIdentityDao(): ConversionIdentityDao

    abstract fun conversionJobDao(): ConversionJobDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_identities` (
                        `identitySha256` TEXT NOT NULL,
                        `logicalSourceIdentity` TEXT NOT NULL,
                        `profileMajorVersion` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `extensionId` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `wrapperSignerSha256` TEXT NOT NULL,
                        `crxKeyAlias` TEXT,
                        `lastReservedVersionCode` INTEGER NOT NULL,
                        PRIMARY KEY(`identitySha256`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_job` (
                        `singletonId` INTEGER NOT NULL,
                        `token` TEXT NOT NULL,
                        `inputKind` TEXT NOT NULL,
                        `inputSha256` TEXT NOT NULL,
                        `inputFileName` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `approvedAdapterIdsJson` TEXT,
                        `profileSha256` TEXT,
                        `bundleFileName` TEXT,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`singletonId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                requireNoDuplicateBinding(db, "extensionId")
                requireNoDuplicateBinding(db, "packageName")
                requireNoDuplicateBinding(
                    db,
                    column = "crxKeyAlias",
                    whereClause = "WHERE `crxKeyAlias` IS NOT NULL",
                )

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_conversion_identities_extensionId`
                    ON `conversion_identities` (`extensionId`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_conversion_identities_packageName`
                    ON `conversion_identities` (`packageName`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_conversion_identities_crxKeyAlias`
                    ON `conversion_identities` (`crxKeyAlias`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE `conversion_job_v3` (
                        `singletonId` INTEGER NOT NULL,
                        `token` TEXT NOT NULL,
                        `generation` INTEGER NOT NULL,
                        `inputKind` TEXT NOT NULL,
                        `inputSha256` TEXT NOT NULL,
                        `inputFileName` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `approvedAdapterIdsJson` TEXT,
                        `profileSha256` TEXT,
                        `bundleFileName` TEXT,
                        `bundleSha256` TEXT,
                        `cleanupTokensJson` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`singletonId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `conversion_job_v3` (
                        `singletonId`, `token`, `generation`, `inputKind`,
                        `inputSha256`, `inputFileName`, `stage`,
                        `approvedAdapterIdsJson`, `profileSha256`,
                        `bundleFileName`, `bundleSha256`, `cleanupTokensJson`,
                        `updatedAtEpochMillis`
                    )
                    SELECT
                        `singletonId`, `token`, 1, `inputKind`,
                        `inputSha256`, `inputFileName`,
                        CASE
                            WHEN `stage` = 'BUNDLE_READY' THEN 'BUILDING'
                            ELSE `stage`
                        END,
                        `approvedAdapterIdsJson`, `profileSha256`,
                        CASE
                            WHEN `stage` = 'BUNDLE_READY' THEN NULL
                            ELSE `bundleFileName`
                        END,
                        NULL, '[]',
                        CASE
                            WHEN `stage` = 'BUNDLE_READY'
                                THEN CAST(strftime('%s', 'now') AS INTEGER) * 1000
                            ELSE `updatedAtEpochMillis`
                        END
                    FROM `conversion_job`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `conversion_job`")
                db.execSQL("ALTER TABLE `conversion_job_v3` RENAME TO `conversion_job`")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_identity_leases` (
                        `identitySha256` TEXT NOT NULL,
                        `crxKeyAlias` TEXT,
                        `ownerToken` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAtEpochMillis` INTEGER NOT NULL,
                        `extensionId` TEXT,
                        `packageName` TEXT,
                        `wrapperSignerSha256` TEXT,
                        `crxPublicKeySha256` TEXT,
                        `reservedVersionCode` INTEGER,
                        PRIMARY KEY(`identitySha256`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_conversion_identity_leases_crxKeyAlias`
                    ON `conversion_identity_leases` (`crxKeyAlias`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_conversion_identity_leases_ownerToken`
                    ON `conversion_identity_leases` (`ownerToken`)
                    """.trimIndent(),
                )
            }
        }

        private fun requireNoDuplicateBinding(
            db: SupportSQLiteDatabase,
            column: String,
            whereClause: String = "",
        ) {
            db.query(
                """
                SELECT 1
                FROM `conversion_identities`
                $whereClause
                GROUP BY `$column`
                HAVING COUNT(*) > 1
                LIMIT 1
                """.trimIndent(),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    throw IllegalStateException(
                        "기존 conversion identity의 $column binding이 충돌합니다.",
                    )
                }
            }
        }
    }
}
