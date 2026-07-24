package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.RuntimeResourceIndex
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.readText

class MobilePatternAnalyzer(
    private val compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun analyze(
        extensionRoot: Path,
        profile: BrowserProfile,
    ): CompatibilityReport {
        val compatibility = compatibilityAnalyzer.analyze(extensionRoot, profile)
        if (compatibility.status == CompatibilityStatus.BLOCKED ||
            compatibility.status == CompatibilityStatus.UNKNOWN
        ) {
            return compatibility
        }
        val manifestPath = extensionRoot.resolve("manifest.json")
        val manifest = runCatching {
            json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull() ?: return compatibility
        val index = RuntimeResourceIndex.create(extensionRoot, manifest)
        if (index.unresolvedDependency) return compatibility

        val decisions = mutableListOf<AdapterDecision>()
        val issues = compatibility.issues.toMutableList()
        selectionDecision(index)?.let(decisions::add)
        popupDecision(manifestPath, manifest, profile)?.let(decisions::add)
        val storage = storageDecision(index, profile)
        storage.decision?.let(decisions::add)
        if (storage.unknown) {
            issues += CompatibilityIssue(
                code = "storage_area_unknown",
                message = "storage.local과 storage.sync 영역 지원 증거가 충분하지 않습니다.",
                blocking = false,
            )
        }

        val sortedDecisions = decisions.sortedWith(
            compareBy(AdapterDecision::id, AdapterDecision::version),
        )
        return compatibility.copy(
            issues = issues,
            conversionState = conversionState(sortedDecisions),
            adapterDecisions = sortedDecisions,
        )
    }

    private fun selectionDecision(
        index: RuntimeResourceIndex,
    ): AdapterDecision? {
        val scripts = index.scripts.filter {
            RuntimeResourceIndex.ResourceRole.CONTENT_SCRIPT in it.roles
        }
        val mouseEvidence = scripts.firstNotNullOfOrNull { script ->
            eventEvidence(script, MOUSEUP_LISTENER, "정적인 mouseup 선택 listener")
        } ?: return null
        val usesSelection = scripts.any { script ->
            GET_SELECTION.containsMatchIn(codeOnly(script.source))
        }
        if (!usesSelection) return null
        val handlesMobileInput = scripts.any { script ->
            listOf(POINTERUP_LISTENER, TOUCHEND_LISTENER, SELECTIONCHANGE_LISTENER)
                .any { event -> eventInCode(script.source, event) != null }
        }
        if (handlesMobileInput) return null

        val unsafeEvidence = scripts.firstNotNullOfOrNull { script ->
            val match = SENSITIVE_MOUSE_STATE.find(codeOnly(script.source))
                ?: return@firstNotNullOfOrNull null
            evidence(
                script,
                match.range.first,
                "${match.groupValues[1]} mouse event 상태 의존",
            )
        }
        val unavailable = unsafeEvidence != null
        return AdapterDecision(
            id = DESKTOP_SELECTION_EVENT_BRIDGE,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = if (unavailable) {
                AdapterDisposition.UNAVAILABLE
            } else {
                AdapterDisposition.AUTOMATIC
            },
            applied = false,
            evidence = listOf(unsafeEvidence ?: mouseEvidence),
            changes = if (unavailable) {
                emptyList()
            } else {
                listOf("해당 content script 앞에 모바일 선택 event bridge를 추가합니다.")
            },
            remainingLimitations = if (unavailable) {
                listOf("합성 mouse event로 원본의 event 상태 의존을 보존할 수 없습니다.")
            } else {
                emptyList()
            },
        )
    }

    private fun popupDecision(
        manifestPath: Path,
        manifest: JsonObject,
        profile: BrowserProfile,
    ): AdapterDecision? {
        if (ACTION_POPUP_USER_ACCESSIBLE in profile.supportedFeatures) return null
        val popup = (manifest["action"] as? JsonObject)
            ?.stringOrNull("default_popup")
            ?: return null
        val hasContentScript = (manifest["content_scripts"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.any { readStrings(it["js"]).isNotEmpty() }
            ?: false
        if (!hasContentScript) return null
        val manifestSource = runCatching { manifestPath.readText() }.getOrDefault("")
        val popupOffset = DEFAULT_POPUP.find(manifestSource)?.range?.first ?: 0
        return AdapterDecision(
            id = ACTION_POPUP_ENTRY,
            version = 1,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = AdapterDisposition.REQUIRES_APPROVAL,
            applied = false,
            evidence = listOf(
                SourceEvidence(
                    relativePath = "manifest.json",
                    line = lineAt(manifestSource, popupOffset),
                    detail = "action.default_popup=$popup",
                ),
            ),
            changes = listOf("기존 content script에 action popup 보조 진입점을 추가합니다."),
            remainingLimitations = listOf("popup이 브라우저 action UI가 아닌 별도 탭에서 열립니다."),
        )
    }

    private fun storageDecision(
        index: RuntimeResourceIndex,
        profile: BrowserProfile,
    ): StorageDecision {
        val evidence = index.scripts.firstNotNullOfOrNull { script ->
            val match = STORAGE_SYNC.find(codeOnly(script.source))
                ?: return@firstNotNullOfOrNull null
            evidence(script, match.range.first, "정적인 ${match.value} 사용")
        } ?: return StorageDecision()

        if (STORAGE_SYNC_FEATURE in profile.supportedFeatures) {
            return StorageDecision()
        }
        val localSupported = STORAGE_LOCAL_FEATURE in profile.supportedFeatures
        val syncUnavailable = STORAGE_SYNC_FEATURE in profile.unavailableFeatures
        if (!localSupported || !syncUnavailable) {
            val bothUnavailable =
                STORAGE_LOCAL_FEATURE in profile.unavailableFeatures && syncUnavailable
            return if (bothUnavailable) {
                StorageDecision(
                    decision = AdapterDecision(
                        id = STORAGE_FALLBACK,
                        version = 1,
                        risk = AdapterRisk.SAFE,
                        disposition = AdapterDisposition.UNAVAILABLE,
                        applied = false,
                        evidence = listOf(evidence),
                        changes = emptyList(),
                        remainingLimitations = listOf(
                            "storage.local도 사용할 수 없어 안전한 fallback이 없습니다.",
                        ),
                    ),
                )
            } else {
                StorageDecision(unknown = true)
            }
        }
        return StorageDecision(
            decision = AdapterDecision(
                id = STORAGE_FALLBACK,
                version = 1,
                risk = AdapterRisk.SAFE,
                disposition = AdapterDisposition.AUTOMATIC,
                applied = false,
                evidence = listOf(evidence),
                changes = listOf("정적인 storage.sync member를 storage.local로 변경합니다."),
                remainingLimitations = listOf("기기 간 storage 동기화가 제공되지 않습니다."),
            ),
        )
    }

    private fun eventEvidence(
        script: RuntimeResourceIndex.RuntimeScript,
        event: Regex,
        detail: String,
    ): SourceEvidence? {
        val offset = eventInCode(script.source, event) ?: return null
        return evidence(script, offset, detail)
    }

    private fun eventInCode(source: String, event: Regex): Int? {
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            source,
            maskLiterals = false,
        )
        val codeOnly = codeOnly(source)
        return event.findAll(sourceWithoutComments)
            .firstOrNull { codeOnly.getOrNull(it.range.first)?.isJavaIdentifierStart() == true }
            ?.range
            ?.first
    }

    private fun codeOnly(source: String): String =
        JavaScriptLexicalMask.mask(source, maskLiterals = true)

    private fun evidence(
        script: RuntimeResourceIndex.RuntimeScript,
        offset: Int,
        detail: String,
    ) = SourceEvidence(
        relativePath = script.relativePath,
        line = script.lineOffset + lineAt(script.source, offset),
        detail = detail,
    )

    private fun lineAt(source: String, offset: Int): Int =
        source.substring(0, offset.coerceIn(0, source.length)).count { it == '\n' } + 1

    private fun conversionState(decisions: List<AdapterDecision>): ConversionState =
        when {
            decisions.any {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL
            } -> ConversionState.REQUIRES_APPROVAL
            decisions.any {
                it.disposition == AdapterDisposition.AUTOMATIC
            } -> ConversionState.AUTOMATIC
            decisions.any {
                it.disposition == AdapterDisposition.UNAVAILABLE
            } -> ConversionState.UNAVAILABLE
            else -> ConversionState.PASSTHROUGH
        }

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun readStrings(
        element: kotlinx.serialization.json.JsonElement?,
    ): List<String> = (element as? JsonArray)
        ?.mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        }
        .orEmpty()

    private data class StorageDecision(
        val decision: AdapterDecision? = null,
        val unknown: Boolean = false,
    )

    companion object {
        const val DESKTOP_SELECTION_EVENT_BRIDGE =
            "desktop-selection-event-bridge"
        const val ACTION_POPUP_ENTRY = "action-popup-entry"
        const val STORAGE_FALLBACK = "storage-fallback"

        val ADAPTER_IDS = sortedSetOf(
            ACTION_POPUP_ENTRY,
            DESKTOP_SELECTION_EVENT_BRIDGE,
            STORAGE_FALLBACK,
        )

        private const val ACTION_POPUP_USER_ACCESSIBLE =
            "action.popup.user-accessible"
        private const val STORAGE_LOCAL_FEATURE = "storage.local"
        private const val STORAGE_SYNC_FEATURE = "storage.sync"

        private val MOUSEUP_LISTENER = eventListenerRegex("mouseup")
        private val POINTERUP_LISTENER = eventListenerRegex("pointerup")
        private val TOUCHEND_LISTENER = eventListenerRegex("touchend")
        private val SELECTIONCHANGE_LISTENER = eventListenerRegex("selectionchange")
        private val GET_SELECTION = Regex("""\bgetSelection\s*\(""")
        private val SENSITIVE_MOUSE_STATE = Regex(
            """\.\s*(isTrusted|button|buttons|movementX|movementY)\b""",
        )
        private val STORAGE_SYNC = Regex(
            """\b(?:chrome|browser)\s*\.\s*storage\s*\.\s*sync\b""",
        )
        private val DEFAULT_POPUP = Regex(""""default_popup"\s*:""")

        private fun eventListenerRegex(eventName: String) = Regex(
            """\b(?:document|window)\s*\.\s*addEventListener\s*""" +
                """\(\s*(["'])${Regex.escape(eventName)}\1""",
        )
    }
}
