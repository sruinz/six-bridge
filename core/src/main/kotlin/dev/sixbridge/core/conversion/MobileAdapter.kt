package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Path

interface MobileAdapter {
    val id: String
    val version: Int

    fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision
}

sealed interface AdapterExecutionContext {
    val originalRoot: Path
    val stagedRoot: Path
    val profile: BrowserProfile
}

internal class ActiveAdapterExecutionContext private constructor(
    override val originalRoot: Path,
    override val stagedRoot: Path,
    override val profile: BrowserProfile,
) : AdapterExecutionContext {
    private var active = true

    fun requireActive() {
        check(active) { "adapter execution context가 활성 상태가 아닙니다." }
    }

    fun deactivate() {
        active = false
    }

    companion object {
        fun create(
            originalRoot: Path,
            stagedRoot: Path,
            profile: BrowserProfile,
        ) = ActiveAdapterExecutionContext(
            originalRoot,
            stagedRoot,
            profile,
        )
    }
}

sealed interface SelectedAdapter {
    val id: String
    val version: Int
    val risk: AdapterRisk
    val disposition: AdapterDisposition
    val applied: Boolean
    val evidence: List<SourceEvidence>
    val changes: List<String>
    val remainingLimitations: List<String>
}

internal fun AdapterExecutionContext.requireActive(): ActiveAdapterExecutionContext =
    (this as? ActiveAdapterExecutionContext)
        ?.also(ActiveAdapterExecutionContext::requireActive)
        ?: throw IllegalStateException(
            "ConversionWorkspace가 발급한 adapter execution context가 아닙니다.",
        )

class ConversionExecutionException internal constructor() :
    IllegalStateException("변환 작업을 안전하게 완료하지 못했습니다.")

internal fun SelectedAdapter.toDecision(): AdapterDecision = AdapterDecision(
    id = id,
    version = version,
    risk = risk,
    disposition = disposition,
    applied = applied,
    evidence = evidence,
    changes = changes,
    remainingLimitations = remainingLimitations,
)
