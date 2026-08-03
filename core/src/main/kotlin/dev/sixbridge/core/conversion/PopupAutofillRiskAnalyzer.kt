package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.ManifestPopupResolver
import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

internal enum class PopupAutofillEvidenceKind {
    CONFIRMED,
    POTENTIAL,
    MANUAL,
}

internal data class PopupAutofillRisk(
    val kind: PopupAutofillEvidenceKind,
    val decision: AdapterDecision,
    val issue: CompatibilityIssue,
)

internal class PopupAutofillRiskAnalyzer {
    fun analyze(
        root: Path,
        manifest: JsonObject,
        index: RuntimeResourceIndex,
        control: AnalysisControl,
    ): PopupAutofillRisk? {
        val declaredPopup = ManifestPopupResolver.resolve(manifest) ?: return null
        control.checkCancelled()
        val popupPath = ActionPopupEntrySafety.canonicalResourcePath(declaredPopup)
        if (popupPath == null ||
            RuntimeResourceIndex.ResourceRole.ACTION_POPUP !in
            index.rolesByRelativePath[popupPath].orEmpty()
        ) {
            return unavailableRisk(popupPath ?: declaredPopup)
        }
        val source = try {
            Files.readString(root.resolve(popupPath))
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            return unavailableRisk(popupPath)
        }
        control.checkCancelled()
        val document = SafeHtmlDocument.parse(source, control::checkCancelled)
        if (!document.safeToInject) return unavailableRisk(popupPath)

        val staticEvidence = document.inputs.map { input ->
            control.checkCancelled()
            SourceEvidence(
                relativePath = popupPath,
                line = input.line,
                detail = "popup input type=${input.normalizedType}",
            )
        }
        val dynamicEvidence = index.scripts
            .asSequence()
            .filter {
                control.checkCancelled()
                RuntimeResourceIndex.ResourceRole.ACTION_POPUP in it.roles
            }
            .mapNotNull { script -> dynamicInputEvidence(script, control) }
            .toList()
            .sortedWith(
                compareBy(
                    SourceEvidence::relativePath,
                    SourceEvidence::line,
                    SourceEvidence::detail,
                ),
            )
        val kind = when {
            staticEvidence.isNotEmpty() -> PopupAutofillEvidenceKind.CONFIRMED
            dynamicEvidence.isNotEmpty() -> PopupAutofillEvidenceKind.POTENTIAL
            else -> PopupAutofillEvidenceKind.MANUAL
        }
        val evidence = listOf(popupEvidence(popupPath)) + when (kind) {
            PopupAutofillEvidenceKind.CONFIRMED -> staticEvidence
            PopupAutofillEvidenceKind.POTENTIAL -> dynamicEvidence
            PopupAutofillEvidenceKind.MANUAL -> emptyList()
        }
        return PopupAutofillRisk(
            kind = kind,
            decision = AdapterDecision(
                id = MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
                version = 1,
                risk = AdapterRisk.BEHAVIOR_CHANGE,
                disposition = AdapterDisposition.OPTIONAL_APPROVAL,
                applied = false,
                evidence = evidence,
                changes = listOf(
                    "승인 시 popup 대상 input의 native autocomplete를 비활성화합니다.",
                ),
                remainingLimitations = listOf(
                    "보호를 적용하면 popup의 native autocomplete 동작이 달라집니다.",
                ),
            ),
            issue = CompatibilityIssue(
                code = "popup_native_autofill_risk",
                message = "popup input과 native autocomplete UI가 충돌할 수 있어 선택적 보호가 필요합니다.",
                blocking = false,
            ),
        )
    }

    private fun unavailableRisk(popupPath: String): PopupAutofillRisk = PopupAutofillRisk(
        kind = PopupAutofillEvidenceKind.MANUAL,
        decision = AdapterDecision(
            id = MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
            version = 1,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = AdapterDisposition.UNAVAILABLE,
            applied = false,
            evidence = listOf(popupEvidence(popupPath)),
            changes = emptyList(),
            remainingLimitations = listOf(
                "popup HTML에 정확히 하나의 정상적인 head 삽입 경계가 필요합니다.",
            ),
        ),
        issue = CompatibilityIssue(
            code = "popup_native_autofill_unavailable",
            message = "popup HTML의 안전한 보호 script 삽입 경계를 증명할 수 없습니다.",
            blocking = true,
        ),
    )

    private fun popupEvidence(popupPath: String) = SourceEvidence(
        relativePath = popupPath,
        line = 1,
        detail = "popup_path=$popupPath",
    )

    private fun dynamicInputEvidence(
        script: RuntimeResourceIndex.RuntimeScript,
        control: AnalysisControl,
    ): SourceEvidence? {
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            script.source,
            maskLiterals = false,
            checkCancelled = control::checkCancelled,
        )
        val codeOnly = JavaScriptLexicalMask.mask(
            script.source,
            maskLiterals = true,
            checkCancelled = control::checkCancelled,
        )
        val match = CREATE_INPUT.findAll(sourceWithoutComments).firstOrNull {
            control.checkCancelled()
            codeOnly.getOrNull(it.range.first)?.isJavaIdentifierStart() == true
        } ?: return null
        return SourceEvidence(
            relativePath = script.relativePath,
            line = script.lineOffset + lineAt(script.source, match.range.first, control),
            detail = "정적 createElement('input') 호출",
        )
    }

    private fun lineAt(
        source: String,
        offset: Int,
        control: AnalysisControl,
    ): Int {
        var line = 1
        for (index in 0 until offset.coerceIn(0, source.length)) {
            if (index % CHECKPOINT_INTERVAL == 0) control.checkCancelled()
            if (source[index] == '\n') line += 1
        }
        return line
    }

    private companion object {
        val CREATE_INPUT = Regex(
            """\bcreateElement\s*\(\s*(['"])input\1\s*\)""",
        )
        const val CHECKPOINT_INTERVAL = 16_384
    }
}
