package dev.sixbridge.manager.conversion

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConversionJobStage {
    INPUT_READY,
    AWAITING_APPROVAL,
    BUILDING,
    BUNDLE_READY,
}

@Entity(tableName = "conversion_job")
data class ConversionJobEntity(
    @PrimaryKey val singletonId: Int = 1,
    val token: String,
    val inputKind: String,
    val inputSha256: String,
    val inputFileName: String,
    val stage: String,
    val approvedAdapterIdsJson: String?,
    val profileSha256: String?,
    val bundleFileName: String?,
    val updatedAtEpochMillis: Long,
)
