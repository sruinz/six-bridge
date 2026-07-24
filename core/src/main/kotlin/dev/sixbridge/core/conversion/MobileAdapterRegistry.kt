package dev.sixbridge.core.conversion

internal class MobileAdapterRegistry private constructor(
    adapters: List<MobileAdapter>,
    private val failAfterAdapterId: String?,
) {
    private val adapters = adapters.associateBy(MobileAdapter::id)

    fun apply(
        context: AdapterExecutionContext,
        selectedAdapters: List<SelectedAdapter>,
    ): List<AdapterDecision> {
        check(
            selectedAdapters.map(SelectedAdapter::id).distinct().size ==
                selectedAdapters.size,
        ) {
            "중복 adapter selection은 실행할 수 없습니다."
        }
        val results = selectedAdapters
            .sortedWith(EXECUTION_ORDER)
            .associate { selected ->
                val adapter = adapters[selected.id]
                    ?: throw IllegalStateException(
                        "등록되지 않은 adapter를 실행할 수 없습니다.",
                    )
                val result = adapter.apply(context, selected)
                if (selected.id == failAfterAdapterId) {
                    throw IllegalStateException("주입된 adapter 실행 실패")
                }
                selected.id to result
            }
        return selectedAdapters.map { selected ->
            requireNotNull(results[selected.id])
        }
    }

    companion object {
        val DEFAULT = create(null)

        fun forTesting(failAfterAdapterId: String?) =
            create(failAfterAdapterId)

        private fun create(failAfterAdapterId: String?) =
            MobileAdapterRegistry(
                adapters = listOf(
                    ActionPopupEntryAdapter(),
                    DesktopSelectionEventBridge(),
                    StorageFallbackAdapter(),
                ),
                failAfterAdapterId = failAfterAdapterId,
            )

        private val EXECUTION_ORDER =
            compareBy<SelectedAdapter>(
                { it.id != MobilePatternAnalyzer.STORAGE_FALLBACK },
                SelectedAdapter::id,
                SelectedAdapter::version,
            )
    }
}
