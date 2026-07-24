package dev.sixbridge.core.conversion

sealed class AdapterApprovalException(
    message: String,
    adapterIds: Set<String>,
) : IllegalArgumentException(message) {
    val adapterIds: Set<String> = adapterIds.toSortedSet()
}

class MissingAdapterApprovalException(
    adapterIds: Set<String>,
) : AdapterApprovalException(
    "필수 adapter 승인이 누락되었습니다: ${adapterIds.sorted().joinToString(",")}",
    adapterIds,
)

class UnknownAdapterApprovalException(
    adapterIds: Set<String>,
) : AdapterApprovalException(
    "알 수 없는 adapter 승인입니다: ${adapterIds.sorted().joinToString(",")}",
    adapterIds,
)

class InapplicableAdapterApprovalException(
    adapterIds: Set<String>,
) : AdapterApprovalException(
    "현재 확장에 적용할 수 없는 adapter 승인입니다: ${adapterIds.sorted().joinToString(",")}",
    adapterIds,
)

class ConversionUnavailableException(
    message: String = "안전하게 적용할 수 있는 모바일 변환 계획이 없습니다.",
) : IllegalStateException(message)

class ConversionValidationException(
    message: String,
) : IllegalStateException(message)

class CrxSourceBindingException :
    IllegalArgumentException("검증된 CRX payload와 canonical source snapshot이 다릅니다.")
