package dev.sixbridge.core.profile

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

internal object BrowserProfileDigest {
    fun sha256(profile: BrowserProfile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field("browserPackage", profile.browserPackage)
        digest.field("versionName", profile.versionName)
        digest.field("versionCode", profile.versionCode.toString())
        digest.field("majorVersion", profile.majorVersion.toString())
        digest.set("supportedApis", profile.supportedApis)
        digest.set("detectedApis", profile.detectedApis)
        digest.field(
            "wrapper.discoveryUri",
            profile.wrapperContract.discoveryUri.toString(),
        )
        digest.field(
            "wrapper.extensionMetadata",
            profile.wrapperContract.extensionMetadata.toString(),
        )
        digest.field(
            "wrapper.contentProvider",
            profile.wrapperContract.contentProvider.toString(),
        )
        digest.field(
            "wrapper.devExtensionPath",
            profile.wrapperContract.devExtensionPath.toString(),
        )
        digest.field("sourceApkSha256", profile.sourceApkSha256)
        digest.field("installationApkSetSha256", profile.installationApkSetSha256)
        digest.field("sourceApkSignerSha256", profile.sourceApkSignerSha256)
        digest.list("profileDifferences", profile.profileDifferences)
        digest.set("supportedFeatures", profile.supportedFeatures)
        digest.set("unavailableFeatures", profile.unavailableFeatures)
        digest.capabilities("apiCapabilities", profile.apiCapabilities)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.capabilities(
        name: String,
        values: List<ApiCapabilityEvidence>,
    ) {
        val sorted = values.sortedWith(API_CAPABILITY_COMPARATOR)
        field("$name.size", sorted.size.toString())
        sorted.forEachIndexed { index, evidence ->
            val prefix = "$name[$index]"
            field("$prefix.key.namespace", evidence.key.namespace)
            field("$prefix.key.member", evidence.key.member)
            field("$prefix.key.context", evidence.key.context.name)
            field("$prefix.status", evidence.status.name)
            set("$prefix.permissions", evidence.permissions)
            set("$prefix.manifestKeys", evidence.manifestKeys)
            field("$prefix.detail", evidence.detail)
            field("$prefix.probeId", evidence.probeId)
            field("$prefix.probeCatalogSha256", evidence.probeCatalogSha256)
        }
    }

    private fun MessageDigest.set(name: String, values: Set<String>) {
        list(name, values.sorted())
    }

    private fun MessageDigest.list(name: String, values: List<String>) {
        field("$name.size", values.size.toString())
        values.forEachIndexed { index, value ->
            field("$name[$index]", value)
        }
    }

    private fun MessageDigest.field(name: String, value: String) {
        bytes(name.toByteArray(UTF_8))
        bytes(value.toByteArray(UTF_8))
    }

    private fun MessageDigest.bytes(value: ByteArray) {
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        update(value)
    }
}
