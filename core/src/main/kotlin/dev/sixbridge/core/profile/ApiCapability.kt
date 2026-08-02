package dev.sixbridge.core.profile

import java.util.Collections

enum class ApiCapabilityStatus {
    VERIFIED,
    EXPOSED,
    RESTRICTED,
    ABSENT,
    FAILED,
    INTERNAL,
}

enum class ApiExecutionContext {
    SERVICE_WORKER,
    EXTENSION_PAGE,
    CONTENT_SCRIPT,
    OFFSCREEN_DOCUMENT,
    USER_GESTURE,
}

data class ApiCapabilityKey(
    val namespace: String,
    val member: String,
    val context: ApiExecutionContext,
) {
    init {
        requireApiName("capability namespace", namespace)
        requireApiName("capability member", member)
    }
}

class ApiCapabilityEvidence(
    val key: ApiCapabilityKey,
    val status: ApiCapabilityStatus,
    permissions: Set<String>,
    manifestKeys: Set<String>,
    val detail: String,
    val probeId: String,
    val probeCatalogSha256: String,
) {
    val permissions: Set<String> = immutableSet(permissions)
    val manifestKeys: Set<String> = immutableSet(manifestKeys)

    init {
        require(probeId.length in 1..MAX_IDENTIFIER_LENGTH && PROBE_ID.matches(probeId)) {
            "capability probe ID가 올바르지 않습니다."
        }
        require(SHA256.matches(probeCatalogSha256)) {
            "capability probe catalog SHA-256이 올바르지 않습니다."
        }
    }

    fun copy(
        key: ApiCapabilityKey = this.key,
        status: ApiCapabilityStatus = this.status,
        permissions: Set<String> = this.permissions,
        manifestKeys: Set<String> = this.manifestKeys,
        detail: String = this.detail,
        probeId: String = this.probeId,
        probeCatalogSha256: String = this.probeCatalogSha256,
    ) = ApiCapabilityEvidence(
        key,
        status,
        permissions,
        manifestKeys,
        detail,
        probeId,
        probeCatalogSha256,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ApiCapabilityEvidence &&
            key == other.key &&
            status == other.status &&
            permissions == other.permissions &&
            manifestKeys == other.manifestKeys &&
            detail == other.detail &&
            probeId == other.probeId &&
            probeCatalogSha256 == other.probeCatalogSha256

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + permissions.hashCode()
        result = 31 * result + manifestKeys.hashCode()
        result = 31 * result + detail.hashCode()
        result = 31 * result + probeId.hashCode()
        return 31 * result + probeCatalogSha256.hashCode()
    }

    override fun toString(): String =
        "ApiCapabilityEvidence(key=$key, status=$status, permissions=$permissions, " +
            "manifestKeys=$manifestKeys, detail=$detail, probeId=$probeId, " +
            "probeCatalogSha256=$probeCatalogSha256)"
}

internal val API_CAPABILITY_COMPARATOR = compareBy<ApiCapabilityEvidence>(
    { it.key.namespace },
    { it.key.member },
    { it.key.context.name },
)

private const val MAX_IDENTIFIER_LENGTH = 128
private val API_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
private val PROBE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val SHA256 = Regex("[0-9a-f]{64}")

private fun requireApiName(label: String, value: String) {
    require(value.length in 1..MAX_IDENTIFIER_LENGTH && API_NAME.matches(value)) {
        "$label 값이 올바르지 않습니다."
    }
}

private fun <T> immutableSet(values: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
