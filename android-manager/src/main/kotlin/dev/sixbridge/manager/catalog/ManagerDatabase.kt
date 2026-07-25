package dev.sixbridge.manager.catalog

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CatalogEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ManagerDatabase : RoomDatabase() {
    abstract fun catalogEntryDao(): CatalogEntryDao
}
