package dev.sixbridge.core.compat

data class ApiReference(
    val namespace: String,
    val member: String?,
) {
    init {
        require(API_NAME.matches(namespace)) { "API namespace가 올바르지 않습니다." }
        require(member == null || API_NAME.matches(member)) { "API member가 올바르지 않습니다." }
    }

    val displayName: String
        get() = member?.let { "$namespace.$it" } ?: namespace

    private companion object {
        val API_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
    }
}
