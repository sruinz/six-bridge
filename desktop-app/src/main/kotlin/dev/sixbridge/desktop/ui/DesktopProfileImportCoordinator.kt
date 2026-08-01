package dev.sixbridge.desktop.ui

import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.profile.DesktopProfileOption

internal enum class DesktopProfileImportKind {
    JSON,
    BROWSER_APK,
}

internal data class DesktopProfileImportModel(
    val generation: Long = 0,
    val inputState: DesktopConversionState.Input,
    val profiles: List<DesktopProfileOption>,
)

internal data class DesktopProfileImportTicket(
    val generation: Long,
    val kind: DesktopProfileImportKind,
)

internal data class DesktopProfileImportStart(
    val model: DesktopProfileImportModel,
    val ticket: DesktopProfileImportTicket,
)

internal sealed interface DesktopProfileImportOutcome {
    data class Success(val option: DesktopProfileOption) : DesktopProfileImportOutcome
    data object Failure : DesktopProfileImportOutcome
}

internal fun beginProfileImport(
    model: DesktopProfileImportModel,
    kind: DesktopProfileImportKind,
): DesktopProfileImportStart {
    val generation = model.generation + 1
    return DesktopProfileImportStart(
        model = model.copy(generation = generation),
        ticket = DesktopProfileImportTicket(generation, kind),
    )
}

internal fun DesktopProfileImportModel.invalidateProfileImports(): DesktopProfileImportModel =
    copy(generation = generation + 1)

internal fun finishProfileImport(
    model: DesktopProfileImportModel,
    ticket: DesktopProfileImportTicket,
    outcome: DesktopProfileImportOutcome,
): DesktopProfileImportModel {
    if (ticket.generation != model.generation) return model
    return when (outcome) {
        is DesktopProfileImportOutcome.Success -> {
            val option = outcome.option
            val nextInput = if (option.selectable) {
                model.inputState.copy(selectedProfile = option, message = option.explanation)
            } else {
                model.inputState.copy(message = option.explanation)
            }
            model.copy(
                inputState = nextInput,
                profiles = (model.profiles + option).distinctBy(DesktopProfileOption::id),
            )
        }
        DesktopProfileImportOutcome.Failure -> model.copy(
            inputState = model.inputState.copy(message = ticket.kind.failureMessage()),
        )
    }
}

private fun DesktopProfileImportKind.failureMessage(): String = when (this) {
    DesktopProfileImportKind.JSON -> "profile JSON을 가져오지 못했습니다."
    DesktopProfileImportKind.BROWSER_APK ->
        "Samsung Internet APK profile을 가져오지 못했습니다."
}
