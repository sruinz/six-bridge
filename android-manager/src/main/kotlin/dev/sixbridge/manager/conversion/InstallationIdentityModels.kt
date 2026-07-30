package dev.sixbridge.manager.conversion

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

enum class InstallationIdentityMode {
    PASSTHROUGH,
    PREPARED,
    CONVERTED,
}

@JvmInline
value class LogicalSourceIdentity private constructor(
    val canonicalValue: String,
) {
    companion object {
        fun storeExtensionId(extensionId: String): LogicalSourceIdentity {
            require(CHROME_EXTENSION_ID.matches(extensionId)) {
                "store extension ID가 올바르지 않습니다."
            }
            return LogicalSourceIdentity("store:$extensionId")
        }

        fun projectMarker(marker: String): LogicalSourceIdentity {
            require(PROJECT_MARKER.matches(marker)) {
                "project marker가 올바르지 않습니다."
            }
            return LogicalSourceIdentity("project:$marker")
        }

        fun manifestKeyExtensionId(extensionId: String): LogicalSourceIdentity {
            require(CHROME_EXTENSION_ID.matches(extensionId)) {
                "manifest key extension ID가 올바르지 않습니다."
            }
            return LogicalSourceIdentity("manifest:$extensionId")
        }

        fun signedDraft(canonicalValue: String): LogicalSourceIdentity =
            fromCanonical(canonicalValue)

        internal fun local(uuid: UUID): LogicalSourceIdentity =
            LogicalSourceIdentity("local-$uuid")

        internal fun fromStored(canonicalValue: String): LogicalSourceIdentity =
            fromCanonical(canonicalValue)

        private fun fromCanonical(value: String): LogicalSourceIdentity {
            require(
                STORE_SOURCE.matches(value) ||
                    PROJECT_SOURCE.matches(value) ||
                    MANIFEST_SOURCE.matches(value) ||
                    LOCAL_SOURCE.matches(value),
            ) { "논리 원본 신원 형식이 올바르지 않습니다." }
            return LogicalSourceIdentity(value)
        }

        private val CHROME_EXTENSION_ID = Regex("[a-p]{32}")
        private val PROJECT_MARKER = Regex("[0-9a-f]{32}")
        private val STORE_SOURCE = Regex("store:[a-p]{32}")
        private val PROJECT_SOURCE = Regex("project:[0-9a-f]{32}")
        private val MANIFEST_SOURCE = Regex("manifest:[a-p]{32}")
        private val LOCAL_SOURCE =
            Regex("local-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

@ConsistentCopyVisibility
data class InstallationIdentityKey private constructor(
    val logicalSourceIdentity: LogicalSourceIdentity,
    val profileMajorVersion: Int,
    val mode: InstallationIdentityMode,
    val identitySha256: String,
) {
    init {
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        require(
            identitySha256 == canonicalInstallationIdentitySha256(
                logicalSourceIdentity.canonicalValue,
                profileMajorVersion,
                mode,
            ),
        ) { "설치 신원 hash가 canonical tuple과 일치하지 않습니다." }
    }

    companion object {
        fun create(
            logicalSourceIdentity: LogicalSourceIdentity,
            profileMajorVersion: Int,
            mode: InstallationIdentityMode,
        ) = InstallationIdentityKey(
            logicalSourceIdentity = logicalSourceIdentity,
            profileMajorVersion = profileMajorVersion,
            mode = mode,
            identitySha256 = canonicalInstallationIdentitySha256(
                logicalSourceIdentity.canonicalValue,
                profileMajorVersion,
                mode,
            ),
        )

        internal fun restore(
            logicalSourceIdentity: String,
            profileMajorVersion: Int,
            mode: String,
            identitySha256: String,
        ): InstallationIdentityKey {
            val parsedMode = enumValues<InstallationIdentityMode>()
                .firstOrNull { it.name == mode }
                ?: throw IllegalArgumentException("저장된 설치 신원 분기가 올바르지 않습니다.")
            return InstallationIdentityKey(
                logicalSourceIdentity = LogicalSourceIdentity.fromStored(logicalSourceIdentity),
                profileMajorVersion = profileMajorVersion,
                mode = parsedMode,
                identitySha256 = identitySha256,
            )
        }
    }
}

sealed interface InstallationIdentitySelection {
    data object CreateNew : InstallationIdentitySelection

    data class UseExisting(
        val identitySha256: String,
    ) : InstallationIdentitySelection
}

data class ConversionPackageBinding(
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
    val crxKeyAlias: String?,
    val crxPublicKeySha256: String?,
)

data class ConversionIdentityReservation(
    val identityKey: InstallationIdentityKey,
    val displayName: String,
    val packageBinding: ConversionPackageBinding,
    val installedVersionCode: Long?,
)

data class ExistingConversionTarget(
    val identitySha256: String,
    val displayName: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
)

private fun canonicalInstallationIdentitySha256(
    logicalSourceIdentity: String,
    profileMajorVersion: Int,
    mode: InstallationIdentityMode,
): String {
    val tuple = listOf(
        "sixbridge-installation-v1",
        logicalSourceIdentity,
        profileMajorVersion.toString(),
        mode.name,
    )
    val encoded = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            tuple.forEach { value ->
                val utf8 = value.encodeToByteArray()
                output.writeInt(utf8.size)
                output.write(utf8)
            }
        }
        bytes.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString("") { "%02x".format(it) }
}
