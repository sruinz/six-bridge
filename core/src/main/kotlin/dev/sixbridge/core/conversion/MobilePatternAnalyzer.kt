package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.RuntimeResourceIndex
import dev.sixbridge.core.compat.StableExtensionAnalysis
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.jvm.JvmSynthetic

class MobilePatternAnalyzer(
    private val compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun analyze(
        extensionRoot: Path,
        profile: BrowserProfile,
    ): CompatibilityReport {
        return StableExtensionAnalysis.captureLive(extensionRoot).use { stable ->
            val report = analyzeStable(
                stable.root,
                profile,
                stable.sourceSha256,
            )
            stable.verifyLiveSource()
            report
        }
    }

    @JvmSynthetic
    internal fun analyzeArchive(
        sourceArchive: ByteArray,
        profile: BrowserProfile,
    ): CompatibilityReport {
        return StableExtensionAnalysis.fromArchive(sourceArchive).use { stable ->
            analyzeStable(
                stable.root,
                profile,
                stable.sourceSha256,
            )
        }
    }

    private fun analyzeStable(
        extensionRoot: Path,
        profile: BrowserProfile,
        sourceSha256: String?,
    ): CompatibilityReport {
        val compatibility = compatibilityAnalyzer.analyzeStable(
            extensionRoot,
            profile,
            sourceSha256,
        )
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
        if (index.unresolvedDependency) {
            return compatibility
        }

        val decisions = mutableListOf<AdapterDecision>()
        val issues = compatibility.issues.toMutableList()
        selectionDecision(index)?.let(decisions::add)
        popupDecision(manifestPath, manifest, index, profile)?.let(decisions::add)
        val storage = storageDecision(extensionRoot, manifest, index, profile)
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

        val unsafeEvidence = scripts.firstNotNullOfOrNull(
            ::sensitiveMouseEventEvidence,
        )
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
        index: RuntimeResourceIndex,
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
        val manifestSource = runCatching { manifestPath.readText() }.getOrDefault("")
        val popupOffset = DEFAULT_POPUP.find(manifestSource)?.range?.first ?: 0
        val unavailableLimitations = buildList {
            if (!hasContentScript) {
                add("기존 content script가 없어 host permission을 늘리지 않고 진입점을 추가할 수 없습니다.")
            }
            if ("tabs" !in profile.supportedApis) {
                add("tabs namespace가 없어 popup을 extension tab으로 열 수 없습니다.")
            }
            if (ActionPopupEntrySafety.hasWorkerUrlDependency(index)) {
                add("원본 service worker가 자신의 URL에 의존해 bootstrap semantics를 보존할 수 없습니다.")
            }
        }
        val unavailable = unavailableLimitations.isNotEmpty()
        val applied = !unavailable &&
            ActionPopupEntryAdapter().isExactlyApplied(
                manifestPath.parent,
                manifest,
                popup,
            )
        return AdapterDecision(
            id = ACTION_POPUP_ENTRY,
            version = 1,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = if (unavailable) {
                AdapterDisposition.UNAVAILABLE
            } else {
                AdapterDisposition.REQUIRES_APPROVAL
            },
            applied = applied,
            evidence = listOf(
                SourceEvidence(
                    relativePath = "manifest.json",
                    line = lineAt(manifestSource, popupOffset),
                    detail = "action.default_popup=$popup",
                ),
            ),
            changes = if (unavailable) {
                emptyList()
            } else {
                listOf("기존 content script에 action popup 보조 진입점을 추가합니다.")
            },
            remainingLimitations = if (unavailable) {
                unavailableLimitations
            } else {
                listOf("popup이 브라우저 action UI가 아닌 별도 탭에서 열립니다.")
            },
        )
    }

    private fun storageDecision(
        extensionRoot: Path,
        manifest: JsonObject,
        index: RuntimeResourceIndex,
        profile: BrowserProfile,
    ): StorageDecision {
        val rewriter = JavaScriptTokenRewriter()
        val safeEvidence = mutableListOf<SourceEvidence>()
        val unsafeEvidence = mutableListOf<SourceEvidence>()
        val convertedSources = mutableMapOf<String, String>()
        val trustedExclusions = ActionPopupEntryAdapter()
            .trustedStorageAnalysisExclusions(extensionRoot, manifest)
        val inspected = index.scripts
            .filterNot { it.relativePath in trustedExclusions }
            .map { script -> script to rewriter.inspect(script.source) }
        if (inspected.none { (_, inspection) -> inspection.storageIntent }) {
            return StorageDecision()
        }
        inspected.forEach { (script, inspection) ->
            val rewrite = rewriter.rewrite(script.source)
            if (isStandaloneJavaScript(script.relativePath)) {
                if (inspection.unsafeAccesses.isEmpty() &&
                    rewrite.replacements.isNotEmpty()
                ) {
                    convertedSources[script.relativePath] = rewrite.source
                }
                safeEvidence += inspection.syncAccesses.map { access ->
                    SourceEvidence(
                        relativePath = script.relativePath,
                        line = script.lineOffset + access.line,
                        detail = StorageFallbackEvidence.detail(
                            access.root,
                            script.source,
                            rewrite.source,
                        ),
                    )
                }
            } else {
                unsafeEvidence += inspection.syncAccesses.map { access ->
                    SourceEvidence(
                        relativePath = script.relativePath,
                        line = script.lineOffset + access.line,
                        detail = "인라인 script의 storage.sync 접근은 안전하게 치환할 수 없음",
                    )
                }
            }
            unsafeEvidence += inspection.unsafeAccesses.map { access ->
                SourceEvidence(
                    relativePath = script.relativePath,
                    line = script.lineOffset + access.line,
                    detail = access.detail,
                )
            }
        }
        if (STORAGE_SYNC_FEATURE in profile.supportedFeatures) {
            return StorageDecision()
        }
        val localSupported = STORAGE_LOCAL_FEATURE in profile.supportedFeatures
        val syncUnavailable = STORAGE_SYNC_FEATURE in profile.unavailableFeatures
        val profileEvidenceUnknown = !localSupported || !syncUnavailable
        if (unsafeEvidence.isNotEmpty()) {
            return StorageDecision(
                decision = AdapterDecision(
                    id = STORAGE_FALLBACK,
                    version = 1,
                    risk = AdapterRisk.SAFE,
                    disposition = AdapterDisposition.UNAVAILABLE,
                    applied = false,
                    evidence = unsafeEvidence
                        .distinct()
                        .sortedWith(
                            compareBy(
                                SourceEvidence::relativePath,
                                SourceEvidence::line,
                                SourceEvidence::detail,
                            ),
                        ),
                    changes = emptyList(),
                    remainingLimitations = listOf(
                        "동적 또는 계산된 storage 접근의 의미를 안전하게 보존할 수 없습니다.",
                    ),
                ),
                unknown = profileEvidenceUnknown,
            )
        }
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
                        evidence = safeEvidence,
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
        val capability = StorageFallbackCapability.create(
            extensionRoot,
            index,
            convertedSources,
            profile,
        )
        return StorageDecision(
            decision = AdapterDecision(
                id = STORAGE_FALLBACK,
                version = 1,
                risk = AdapterRisk.SAFE,
                disposition = AdapterDisposition.AUTOMATIC,
                applied = false,
                evidence = (
                    safeEvidence +
                        capability.evidence()
                    ).distinct().sortedWith(
                        compareBy(
                            SourceEvidence::relativePath,
                            SourceEvidence::line,
                            SourceEvidence::detail,
                        ),
                    ),
                changes = listOf("정적인 storage.sync member를 storage.local로 변경합니다."),
                remainingLimitations = listOf(
                    "기기 간 storage 동기화가 제공되지 않습니다.",
                    "기존 storage.sync 데이터는 자동 이관되지 않아 초기 설정이 사라진 것처럼 보일 수 있습니다.",
                ),
            ),
        )
    }

    private fun isStandaloneJavaScript(relativePath: String): Boolean =
        relativePath.substringAfterLast('.', "").lowercase() in
            setOf("js", "mjs", "cjs")

    private fun eventEvidence(
        script: RuntimeResourceIndex.RuntimeScript,
        event: Regex,
        detail: String,
    ): SourceEvidence? {
        val offset = eventInCode(script.source, event) ?: return null
        return evidence(script, offset, detail)
    }

    private fun sensitiveMouseEventEvidence(
        script: RuntimeResourceIndex.RuntimeScript,
    ): SourceEvidence? {
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            script.source,
            maskLiterals = false,
        )
        val codeOnly = codeOnly(script.source)
        MOUSEUP_LISTENER.findAll(sourceWithoutComments)
            .filter {
                codeOnly.getOrNull(it.range.first)?.isJavaIdentifierStart() == true
            }
            .forEach { listener ->
                val callback = readCallbackBody(
                    codeOnly,
                    listener.range.last + 1,
                ) ?: return evidence(
                    script,
                    listener.range.first,
                    "mouseup callback을 정적으로 해석할 수 없음",
                )
                val member = eventMemberRegex(callback.parameter)
                    .find(codeOnly, callback.bodyStart)
                    ?.takeIf { it.range.first < callback.bodyEnd }
                    ?: return@forEach
                return evidence(
                    script,
                    member.range.first,
                    "${member.groupValues[1]} mouse event 상태 의존",
                )
            }
        return null
    }

    private fun readCallbackBody(
        codeOnly: String,
        listenerEnd: Int,
    ): CallbackBody? {
        var cursor = skipWhitespace(codeOnly, listenerEnd)
        if (codeOnly.getOrNull(cursor) != ',') return null
        cursor = skipWhitespace(codeOnly, cursor + 1)
        if (codeOnly.startsWith("async", cursor) &&
            codeOnly.getOrNull(cursor + "async".length)
                ?.isJavaIdentifierPart() != true
        ) {
            cursor = skipWhitespace(codeOnly, cursor + "async".length)
            return readArrowCallback(codeOnly, cursor)
        }
        return if (codeOnly.startsWith("function", cursor) &&
            !codeOnly.getOrNull(cursor + "function".length)
                .let { it?.isJavaIdentifierPart() == true }
        ) {
            readFunctionCallback(codeOnly, cursor + "function".length)
        } else {
            val identifierEnd = codeOnly.getOrNull(cursor)
                ?.takeIf(Char::isJavaIdentifierStart)
                ?.let { readIdentifierEnd(codeOnly, cursor) }
            val afterIdentifier = identifierEnd?.let {
                skipWhitespace(codeOnly, it)
            }
            when {
                identifierEnd == null -> readArrowCallback(codeOnly, cursor)
                codeOnly.startsWith("=>", afterIdentifier!!) ->
                    readArrowCallback(codeOnly, cursor)
                codeOnly.getOrNull(afterIdentifier) == ',' ||
                    codeOnly.getOrNull(afterIdentifier) == ')' ->
                    readNamedFunctionCallback(
                        codeOnly,
                        codeOnly.substring(cursor, identifierEnd),
                    )
                else -> null
            }
        }
    }

    private fun readNamedFunctionCallback(
        codeOnly: String,
        name: String,
    ): CallbackBody? {
        val declaration = Regex(
            """\bfunction\s+${Regex.escape(name)}\s*\(""",
        ).find(codeOnly) ?: return null
        return readFunctionCallback(
            codeOnly,
            declaration.range.first + "function".length,
        )
    }

    private fun readFunctionCallback(
        codeOnly: String,
        start: Int,
    ): CallbackBody? {
        var cursor = skipWhitespace(codeOnly, start)
        if (codeOnly.getOrNull(cursor)?.isJavaIdentifierStart() == true) {
            cursor = readIdentifierEnd(codeOnly, cursor)
            cursor = skipWhitespace(codeOnly, cursor)
        }
        if (codeOnly.getOrNull(cursor) != '(') return null
        val parametersEnd = matchingDelimiter(codeOnly, cursor, '(', ')') ?: return null
        val parameter = singleParameter(
            codeOnly.substring(cursor + 1, parametersEnd),
        ) ?: return null
        cursor = skipWhitespace(codeOnly, parametersEnd + 1)
        return bracedCallbackBody(codeOnly, cursor, parameter)
    }

    private fun readArrowCallback(
        codeOnly: String,
        start: Int,
    ): CallbackBody? {
        var cursor = start
        val parameter = if (codeOnly.getOrNull(cursor) == '(') {
            val parametersEnd = matchingDelimiter(codeOnly, cursor, '(', ')')
                ?: return null
            val value = singleParameter(
                codeOnly.substring(cursor + 1, parametersEnd),
            ) ?: return null
            cursor = skipWhitespace(codeOnly, parametersEnd + 1)
            value
        } else {
            if (codeOnly.getOrNull(cursor)?.isJavaIdentifierStart() != true) return null
            val parameterEnd = readIdentifierEnd(codeOnly, cursor)
            val value = codeOnly.substring(cursor, parameterEnd)
            cursor = skipWhitespace(codeOnly, parameterEnd)
            value
        }
        if (!codeOnly.startsWith("=>", cursor)) return null
        cursor = skipWhitespace(codeOnly, cursor + 2)
        if (codeOnly.getOrNull(cursor) == '{') {
            return bracedCallbackBody(codeOnly, cursor, parameter)
        }
        val bodyEnd = callbackArgumentEnd(codeOnly, cursor)
        return CallbackBody(parameter, cursor, bodyEnd)
    }

    private fun callbackArgumentEnd(source: String, start: Int): Int {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        for (index in start until source.length) {
            when (source[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return index
                } else if (parentheses > 0) {
                    parentheses--
                }
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces--
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return index
                }
            }
        }
        return source.length
    }

    private fun bracedCallbackBody(
        codeOnly: String,
        openingBrace: Int,
        parameter: String,
    ): CallbackBody? {
        if (codeOnly.getOrNull(openingBrace) != '{') return null
        val closingBrace = matchingDelimiter(
            codeOnly,
            openingBrace,
            '{',
            '}',
        ) ?: return null
        return CallbackBody(parameter, openingBrace + 1, closingBrace)
    }

    private fun matchingDelimiter(
        source: String,
        start: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        for (index in start until source.length) {
            when (source[index]) {
                opening -> depth++
                closing -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun singleParameter(source: String): String? =
        source.trim()
            .removeSuffix(",")
            .trim()
            .takeIf { IDENTIFIER.matches(it) }

    private fun readIdentifierEnd(source: String, start: Int): Int {
        var cursor = start + 1
        while (source.getOrNull(cursor)?.isJavaIdentifierPart() == true) cursor++
        return cursor
    }

    private fun skipWhitespace(source: String, start: Int): Int {
        var cursor = start
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        return cursor
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
                it.disposition == AdapterDisposition.UNAVAILABLE
            } -> ConversionState.UNAVAILABLE
            decisions.any {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL
            } -> ConversionState.REQUIRES_APPROVAL
            decisions.any {
                it.disposition == AdapterDisposition.AUTOMATIC
            } -> ConversionState.AUTOMATIC
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

    private data class CallbackBody(
        val parameter: String,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    companion object {
        const val DESKTOP_SELECTION_EVENT_BRIDGE =
            "desktop-selection-event-bridge"
        const val ACTION_POPUP_ENTRY = "action-popup-entry"
        const val STORAGE_FALLBACK = "storage-fallback"

        val ADAPTER_IDS: Set<String> = listOf(
            ACTION_POPUP_ENTRY,
            DESKTOP_SELECTION_EVENT_BRIDGE,
            STORAGE_FALLBACK,
        ).sorted().toSet()

        private const val ACTION_POPUP_USER_ACCESSIBLE =
            "action.popup.user-accessible"
        private const val STORAGE_LOCAL_FEATURE = "storage.local"
        private const val STORAGE_SYNC_FEATURE = "storage.sync"

        private val MOUSEUP_LISTENER = eventListenerRegex("mouseup")
        private val POINTERUP_LISTENER = eventListenerRegex("pointerup")
        private val TOUCHEND_LISTENER = eventListenerRegex("touchend")
        private val SELECTIONCHANGE_LISTENER = eventListenerRegex("selectionchange")
        private val GET_SELECTION = Regex("""\bgetSelection\s*\(""")
        private val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")
        private val DEFAULT_POPUP = Regex(""""default_popup"\s*:""")

        private fun eventListenerRegex(eventName: String) = Regex(
            """\b(?:document|window)\s*\.\s*addEventListener\s*""" +
                """\(\s*(["'])${Regex.escape(eventName)}\1""",
        )

        private fun eventMemberRegex(parameter: String) = Regex(
            """\b${Regex.escape(parameter)}\s*\.\s*""" +
                """(isTrusted|button|buttons|movementX|movementY)\b""",
        )
    }
}
