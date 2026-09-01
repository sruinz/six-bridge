package dev.sixbridge.contract.bundle

import java.security.MessageDigest

/**
 * wrapper APK asset가 canonical compatibility JSON과 결합됐는지 확인하는 digest 계약이다.
 * .six ZIP 자체는 서명되지 않았으므로 이 JSON 검증이나 digest만으로 Android 설치 권한을 부여할 수 없다.
 * 설치 권한은 Task 3과 Task 7의 서명된 wrapper asset 대조를 수행하는 상위 경계에서 별도로 확인해야 한다.
 */
object CompatibilityApkBinding {
    const val ENTRY_NAME = "assets/sixbridge/compatibility.sha256"
    const val ENCODED_SIZE = 64

    fun canonicalSha256(report: ContractCompatibilityReport): String = MessageDigest
        .getInstance("SHA-256")
        .digest(BundleJson.encodeCompatibility(report).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    fun encode(report: ContractCompatibilityReport): ByteArray = canonicalSha256(report).encodeToByteArray()

    fun decode(bytes: ByteArray): String {
        require(bytes.size == ENCODED_SIZE) { "compatibility SHA-256 길이가 올바르지 않습니다." }
        val value = bytes.decodeToString()
        require(SHA256.matches(value)) { "compatibility SHA-256 형식이 올바르지 않습니다." }
        return value
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
