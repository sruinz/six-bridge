package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.ManifestPopupResolver
import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

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

internal data class PopupAutofillProof(
    val popupPath: String,
    val scriptTagSha256: String,
    val assetSha256: String,
) {
    fun evidence(): SourceEvidence = SourceEvidence(
        relativePath = PopupNativeAutofillGuardContract.MARKER_PATH,
        line = 1,
        detail = "popup_guard_sha256=$scriptTagSha256,$assetSha256",
    )

    companion object {
        fun parse(evidence: List<SourceEvidence>): PopupAutofillProof {
            val popup = evidence.singleOrNull {
                it.detail.startsWith(POPUP_PREFIX)
            } ?: throw IllegalStateException("popup 경로 근거를 확인할 수 없습니다.")
            val popupPath = popup.detail.removePrefix(POPUP_PREFIX)
            check(
                popup.relativePath == popupPath &&
                    ActionPopupEntrySafety.canonicalResourcePath(popupPath) == popupPath,
            ) {
                "popup 경로 근거가 canonical path와 다릅니다."
            }
            val hashes = evidence.singleOrNull {
                it.detail.startsWith(HASH_PREFIX)
            }?.detail?.removePrefix(HASH_PREFIX)?.split(',')
            return PopupAutofillProof(
                popupPath = popupPath,
                scriptTagSha256 = hashes?.getOrNull(0).orEmpty(),
                assetSha256 = hashes?.getOrNull(1).orEmpty(),
            )
        }

        fun fromApplied(
            popupPath: String,
            scriptTag: String,
            assetBytes: ByteArray,
        ): PopupAutofillProof = PopupAutofillProof(
            popupPath = popupPath,
            scriptTagSha256 = popupGuardSha256(scriptTag.toByteArray(UTF_8)),
            assetSha256 = popupGuardSha256(assetBytes),
        )

        private const val POPUP_PREFIX = "popup_path="
        private const val HASH_PREFIX = "popup_guard_sha256="
    }
}

internal class PopupAutofillRiskAnalyzer {
    fun analyze(
        root: Path,
        manifest: JsonObject,
        index: RuntimeResourceIndex,
        control: AnalysisControl,
    ): PopupAutofillRisk? {
        val declaration = ManifestPopupResolver.declaration(manifest) ?: return null
        val declaredPopup = ManifestPopupResolver.resolve(manifest)
            ?: return unavailableRisk(declaration.field)
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
        val appliedProof = exactAppliedProof(root, popupPath, source, document)

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
        } + listOfNotNull(appliedProof?.evidence())
        return PopupAutofillRisk(
            kind = kind,
            decision = AdapterDecision(
                id = MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
                version = 1,
                risk = AdapterRisk.BEHAVIOR_CHANGE,
                disposition = AdapterDisposition.OPTIONAL_APPROVAL,
                applied = appliedProof != null,
                evidence = evidence,
                changes = if (appliedProof != null) {
                    listOf(
                        spanHashChange(
                            popupPath,
                            "",
                            PopupNativeAutofillGuardContract.SCRIPT_TAG,
                        ),
                    )
                } else {
                    listOf(
                        "승인 시 popup 대상 input의 native autocomplete를 비활성화합니다.",
                    )
                },
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

    private fun exactAppliedProof(
        root: Path,
        popupPath: String,
        source: String,
        document: SafeHtmlDocument.Result,
    ): PopupAutofillProof? = runCatching {
        val assetBytes = PopupNativeAutofillGuardContract.assetBytes()
        val proof = PopupAutofillProof.fromApplied(
            popupPath,
            PopupNativeAutofillGuardContract.SCRIPT_TAG,
            assetBytes,
        )
        val asset = root.resolve(PopupNativeAutofillGuardContract.ASSET_PATH)
        if (!Files.isRegularFile(asset, NOFOLLOW_LINKS) ||
            !Files.readAllBytes(asset).contentEquals(assetBytes) ||
            !hasSingleTagAtHead(source, document)
        ) {
            return null
        }
        val marker = root.resolve(PopupNativeAutofillGuardContract.MARKER_PATH)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) return null
        val json = Json.parseToJsonElement(Files.readString(marker, UTF_8)) as? JsonObject
            ?: return null
        val markerVersion = json["version"] as? JsonPrimitive
        if (json.keys != MARKER_KEYS ||
            json.string("adapter") != MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD ||
            markerVersion == null ||
            markerVersion.isString ||
            markerVersion.intOrNull != 1 ||
            json.string("popupPath") != proof.popupPath ||
            json.string("scriptTagSha256") != proof.scriptTagSha256 ||
            json.string("assetSha256") != proof.assetSha256
        ) {
            return null
        }
        proof
    }.getOrNull()

    private fun hasSingleTagAtHead(
        source: String,
        document: SafeHtmlDocument.Result,
    ): Boolean {
        val offset = document.headContentOffset ?: return false
        val tag = PopupNativeAutofillGuardContract.SCRIPT_TAG
        return source.startsWith(tag, offset) && source.indexOf(tag) == source.lastIndexOf(tag)
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

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
        val MARKER_KEYS = setOf(
            "adapter",
            "version",
            "popupPath",
            "scriptTagSha256",
            "assetSha256",
        )
        val CREATE_INPUT = Regex(
            """\bcreateElement\s*\(\s*(['"])input\1\s*\)""",
        )
        const val CHECKPOINT_INTERVAL = 16_384
    }
}

internal fun spanHashChange(
    path: String,
    before: String,
    after: String,
): String =
    "path=$path; span-before-sha256=${popupGuardSha256(before.toByteArray(UTF_8))}; " +
        "span-after-sha256=${popupGuardSha256(after.toByteArray(UTF_8))}"

private fun popupGuardSha256(bytes: ByteArray): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
