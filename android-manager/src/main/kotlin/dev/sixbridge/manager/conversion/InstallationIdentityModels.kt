package dev.sixbridge.manager.conversion

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class InstallationIdentityMode {
    PASSTHROUGH,
    CONVERTED,
}

data class InstallationIdentityKey(
    val logicalSourceIdentity: String,
    val profileMajorVersion: Int,
    val mode: InstallationIdentityMode,
    val identitySha256: String = canonicalInstallationIdentitySha256(
        logicalSourceIdentity,
        profileMajorVersion,
        mode,
    ),
) {
    init {
        require(SOURCE_IDENTITY.matches(logicalSourceIdentity)) {
            "논리 원본 신원 형식이 올바르지 않습니다."
        }
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        require(
            identitySha256 == canonicalInstallationIdentitySha256(
                logicalSourceIdentity,
                profileMajorVersion,
                mode,
            ),
        ) { "설치 신원 hash가 canonical tuple과 일치하지 않습니다." }
    }

    companion object {
        fun create(
            logicalSourceIdentity: String,
            profileMajorVersion: Int,
            mode: InstallationIdentityMode,
        ) = InstallationIdentityKey(
            logicalSourceIdentity = logicalSourceIdentity,
            profileMajorVersion = profileMajorVersion,
            mode = mode,
        )
    }
}

sealed interface InstallationIdentitySelection {
    data object CreateNew : InstallationIdentitySelection

    data class UseExisting(
        val identitySha256: String,
    ) : InstallationIdentitySelection
}

data class ConversionIdentityRequest(
    val logicalSourceIdentity: String?,
    val profileMajorVersion: Int,
    val mode: InstallationIdentityMode,
    val displayName: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
    val crxKeyAlias: String?,
    val installedVersionCode: Long?,
    val selection: InstallationIdentitySelection?,
)

data class ExistingConversionTarget(
    val identitySha256: String,
    val displayName: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
)

data class ReservedConversionIdentity(
    val identitySha256: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
    val versionCode: Int,
)

private val SOURCE_IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

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
