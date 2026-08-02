package dev.sixbridge.probe.session

import dev.sixbridge.probe.catalog.ApiProbeCatalog

data class ProbeSessionBinding(
    val groupId: String,
    val extensionId: String,
    val wrapperSignerSha256: String,
) {
    val origin: String
        get() = "chrome-extension://$extensionId"

    fun validate() {
        require(groupId in ApiProbeCatalog.PROBE_GROUP_IDS) { "probe session groupId가 올바르지 않습니다." }
        require(EXTENSION_ID.matches(extensionId)) { "probe session extensionId가 올바르지 않습니다." }
        require(SHA256.matches(wrapperSignerSha256)) { "probe session wrapper signer가 올바르지 않습니다." }
    }
}

data class ProbeSessionConfig(
    val schemaVersion: Int,
    val sessionId: String,
    val catalogSha256: String,
    val browserPackage: String,
    val browserVersionName: String,
    val browserVersionCode: Long,
    val browserApkSetSha256: String,
    val browserSignerSha256: String,
    val bindings: List<ProbeSessionBinding>,
) {
    fun validate() {
        require(schemaVersion == 1) { "probe session schemaVersion을 지원하지 않습니다." }
        require(ID.matches(sessionId)) { "probe session ID가 올바르지 않습니다." }
        require(SHA256.matches(catalogSha256)) { "probe catalog SHA-256이 올바르지 않습니다." }
        require(BROWSER_PACKAGE.matches(browserPackage) && browserPackage.length <= 200) {
            "browser package가 올바르지 않습니다."
        }
        require(browserVersionName.length in 1..100 &&
            browserVersionName == browserVersionName.trim() &&
            browserVersionName.all { it.code in 0x20..0x7e }
        ) { "browser versionName이 올바르지 않습니다." }
        require(browserVersionCode >= 0) { "browser versionCode가 올바르지 않습니다." }
        require(SHA256.matches(browserApkSetSha256) && SHA256.matches(browserSignerSha256)) {
            "browser provenance digest가 올바르지 않습니다."
        }
        bindings.forEach(ProbeSessionBinding::validate)
        require(bindings.map(ProbeSessionBinding::groupId).toSet() == ApiProbeCatalog.PROBE_GROUP_IDS &&
            bindings.size == ApiProbeCatalog.PROBE_GROUP_IDS.size
        ) { "probe session은 정확한 6개 group binding을 포함해야 합니다." }
        require(bindings.map(ProbeSessionBinding::extensionId).distinct().size == bindings.size) {
            "probe session extensionId는 중복될 수 없습니다."
        }
        require(bindings.map(ProbeSessionBinding::wrapperSignerSha256).distinct().size == 1) {
            "probe session wrapper signer는 6개 binding에서 동일해야 합니다."
        }
    }

    companion object {
        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val BROWSER_PACKAGE = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+$")
    }
}

private val EXTENSION_ID = Regex("^[a-p]{32}$")
private val SHA256 = Regex("^[0-9a-f]{64}$")
