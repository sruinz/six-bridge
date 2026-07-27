package dev.sixbridge.manager.catalog

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sixbridge.manager.conversion.ConversionIdentityDao
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import dev.sixbridge.manager.conversion.ConversionJobDao
import dev.sixbridge.manager.conversion.ConversionJobEntity

@Database(
    entities = [
        CatalogEntryEntity::class,
        ConversionIdentityEntity::class,
        ConversionJobEntity::class,
    ],
    version = 2,
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
    }
}
