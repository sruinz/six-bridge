package dev.sixbridge.probe.evidence

import dev.sixbridge.probe.catalog.ApiProbeCatalog

enum class ProbeManifestLoadOutcome {
    LOAD_BLOCKED,
    LOADED,
}

data class ProbeManifestLoadIsolation(
    val schemaVersion: Int,
    val groupId: String,
    val catalogSha256: String,
    val excludedCaseId: String,
    val excludedPermission: String,
    val controlSessionId: String,
    val controlExtensionId: String,
    val controlManifestSha256: String,
    val controlOutcome: ProbeManifestLoadOutcome,
    val variantSessionId: String,
    val variantExtensionId: String,
    val variantManifestSha256: String,
    val variantEvidenceSha256: String,
    val variantOutcome: ProbeManifestLoadOutcome,
    val wrapperSignerSha256: String,
    val browserPackage: String,
    val browserVersionName: String,
    val browserVersionCode: Long,
    val browserApkSetSha256: String,
    val browserSignerSha256: String,
) {
    init {
        require(schemaVersion == 1) { "manifest 로드 분리 증거 schemaVersion을 지원하지 않습니다." }
        require(groupId in ApiProbeCatalog.PROBE_GROUP_IDS) {
            "manifest 로드 분리 증거 groupId가 catalog에 없습니다."
        }
        require(SHA256.matches(catalogSha256)) { "catalogSha256 값이 올바르지 않습니다." }
        require(CASE_ID.matches(excludedCaseId)) { "excludedCaseId 값이 올바르지 않습니다." }
        require(PERMISSION.matches(excludedPermission)) { "excludedPermission 값이 올바르지 않습니다." }
        require(SESSION_ID.matches(controlSessionId) && SESSION_ID.matches(variantSessionId)) {
            "manifest 로드 분리 증거 sessionId가 올바르지 않습니다."
        }
        require(controlSessionId != variantSessionId) {
            "control과 variant sessionId는 달라야 합니다."
        }
        require(EXTENSION_ID.matches(controlExtensionId) && EXTENSION_ID.matches(variantExtensionId)) {
            "manifest 로드 분리 증거 extensionId가 올바르지 않습니다."
        }
        require(controlExtensionId != variantExtensionId) {
            "control과 variant extensionId는 달라야 합니다."
        }
        require(
            listOf(
                controlManifestSha256,
                variantManifestSha256,
                variantEvidenceSha256,
                wrapperSignerSha256,
                browserApkSetSha256,
                browserSignerSha256,
            ).all(SHA256::matches),
        ) { "manifest 로드 분리 증거 SHA-256 값이 올바르지 않습니다." }
        require(controlManifestSha256 != variantManifestSha256) {
            "control과 variant manifest SHA-256은 달라야 합니다."
        }
        require(controlOutcome == ProbeManifestLoadOutcome.LOAD_BLOCKED) {
            "controlOutcome은 LOAD_BLOCKED여야 합니다."
        }
        require(variantOutcome == ProbeManifestLoadOutcome.LOADED) {
            "variantOutcome은 LOADED여야 합니다."
        }
        require(BROWSER_PACKAGE.matches(browserPackage) && browserPackage.length <= 200) {
            "browserPackage 값이 올바르지 않습니다."
        }
        require(
            browserVersionName.length in 1..100 &&
                browserVersionName == browserVersionName.trim() &&
                browserVersionName.all { it.code in 0x20..0x7e },
        ) { "browserVersionName 값이 올바르지 않습니다." }
        require(browserVersionCode in 0..MAX_SAFE_INTEGER) {
            "browserVersionCode 값이 올바르지 않습니다."
        }
    }

    private companion object {
        val SESSION_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val EXTENSION_ID = Regex("[a-p]{32}")
        val CASE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val PERMISSION = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
        val BROWSER_PACKAGE = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
