package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.core.compat.AnalysisBudgetExceededException
import dev.sixbridge.core.compat.AnalysisBudgetLedger
import dev.sixbridge.core.compat.analysisBudgetExceededReport
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.RuntimeResourceIndex
import dev.sixbridge.core.compat.StableExtensionAnalysis
import dev.sixbridge.core.input.SourceProjectIdentityStore
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.jvm.JvmSynthetic

internal data class MobileArchiveAnalysis(
    val report: CompatibilityReport,
    val sourceProjectIdentity: String?,
)

class MobilePatternAnalyzer private constructor(
    private val control: AnalysisControl,
    private val compatibilityAnalyzer: CompatibilityAnalyzer,
    private val json: Json,
    private val tempTreeFactory: ConversionTempTreeFactory,
    @Suppress("UNUSED_PARAMETER") private val internalConstructorMarker: Unit,
) {
    constructor(
        control: AnalysisControl = AnalysisControl(),
        compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(control),
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(
        control,
        compatibilityAnalyzer,
        json,
        ConversionTempTreeFactory(),
        Unit,
    )

    internal constructor(
        control: AnalysisControl,
        compatibilityAnalyzer: CompatibilityAnalyzer,
        json: Json = Json { ignoreUnknownKeys = true },
        tempTreeFactory: ConversionTempTreeFactory,
    ) : this(
        control,
        compatibilityAnalyzer,
        json,
        tempTreeFactory,
        Unit,
    )

    fun analyze(
        extensionRoot: Path,
        profile: BrowserProfile,
    ): CompatibilityReport {
        control.beginOperation()
        return StableExtensionAnalysis.captureLive(extensionRoot, control, tempTreeFactory).use { stable ->
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
        emitFinalizingStage: Boolean = true,
        beginOperation: Boolean = true,
    ): CompatibilityReport = analyzeArchiveWithIdentity(
        sourceArchive,
        profile,
        emitFinalizingStage,
        beginOperation,
    ).report

    @JvmSynthetic
    internal fun analyzeArchiveWithIdentity(
        sourceArchive: ByteArray,
        profile: BrowserProfile,
        emitFinalizingStage: Boolean = true,
        beginOperation: Boolean = true,
    ): MobileArchiveAnalysis {
        if (beginOperation) control.beginOperation()
        return StableExtensionAnalysis.fromArchive(sourceArchive, control, tempTreeFactory).use { stable ->
            val report = analyzeStable(
                stable.root,
                profile,
                stable.sourceSha256,
                emitFinalizingStage,
            )
            control.checkCancelled()
            MobileArchiveAnalysis(
                report = report,
                sourceProjectIdentity = SourceProjectIdentityStore.readSourceMarker(
                    stable.root,
                    requireOwnerOnly = false,
                ),
            )
        }
    }

    @JvmSynthetic
    internal fun analyzeStableForTesting(
        extensionRoot: Path,
        profile: BrowserProfile,
    ): CompatibilityReport {
        control.beginOperation()
        return analyzeStable(
            extensionRoot,
            profile,
            sourceSha256 = null,
        )
    }

    private fun analyzeStable(
        extensionRoot: Path,
        profile: BrowserProfile,
        sourceSha256: String?,
        emitFinalizingStage: Boolean = true,
    ): CompatibilityReport {
        control.checkCancelled()
        val ledger = AnalysisBudgetLedger(control.policy)
        val compatibility = compatibilityAnalyzer.analyzeStable(
            extensionRoot,
            profile,
            sourceSha256,
            ledger,
            emitFinalizingStage = false,
        )
        control.checkCancelled()
        val manifestPath = extensionRoot.resolve("manifest.json")
        val manifest = runCatching {
            json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull()
        if (compatibility.status == CompatibilityStatus.BLOCKED ||
            compatibility.status == CompatibilityStatus.UNKNOWN
        ) {
            control.stage(AnalysisStage.MOBILE_PATTERNS)
            val invalidOptionPage = manifest?.let {
                invalidExistingOptionPageDecision(manifestPath, it, profile)
            }
            if (invalidOptionPage != null) {
                return finalizeReport(
                    compatibility.copy(
                        conversionState = ConversionState.UNAVAILABLE,
                        adapterDecisions = listOf(invalidOptionPage),
                    ),
                    emitFinalizingStage,
                )
            }
            return finalizeReport(compatibility, emitFinalizingStage)
        }
        manifest ?: return finalizeReport(compatibility, emitFinalizingStage)
        control.checkCancelled()
        val index = try {
            RuntimeResourceIndex.create(
                extensionRoot,
                manifest,
                control,
                ledger,
                emitStage = false,
            )
        } catch (error: AnalysisBudgetExceededException) {
            return finalizeReport(
                analysisBudgetExceededReport(
                    error.exceeded,
                    compatibility.manifestVersion,
                    compatibility.extensionName,
                    compatibility.extensionVersion,
                    compatibility.declaredPermissions,
                ).copy(
                    sourceSha256 = sourceSha256,
                    browserProfileSha256 = profile.canonicalSha256(),
                ).also(CompatibilityReport::validate),
                emitFinalizingStage,
            )
        }
        control.checkCancelled()
        if (index.unresolvedDependency) {
            return finalizeReport(compatibility, emitFinalizingStage)
        }

        control.stage(AnalysisStage.MOBILE_PATTERNS)
        val decisions = mutableListOf<AdapterDecision>()
        val issues = compatibility.issues.toMutableList()
        selectionDecision(index)?.let(decisions::add)
        control.checkCancelled()
        popupDecision(manifestPath, manifest, profile)?.let(decisions::add)
        control.checkCancelled()
        val storage = storageDecision(extensionRoot, manifest, index, profile)
        control.checkCancelled()
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
        return finalizeReport(
            compatibility.copy(
                issues = issues,
                conversionState = conversionState(sortedDecisions),
                adapterDecisions = sortedDecisions,
            ),
            emitFinalizingStage,
        )
    }

    private fun finalizeReport(
        report: CompatibilityReport,
        emitFinalizingStage: Boolean,
    ): CompatibilityReport {
        control.checkCancelled()
        if (emitFinalizingStage) control.stage(AnalysisStage.FINALIZING)
        return report
    }

    private fun selectionDecision(
        index: RuntimeResourceIndex,
    ): AdapterDecision? {
        val scripts = index.scripts.filter {
            control.checkCancelled()
            RuntimeResourceIndex.ResourceRole.CONTENT_SCRIPT in it.roles
        }
        val mouseEvidence = scripts.firstNotNullOfOrNull { script ->
            control.checkCancelled()
            eventEvidence(script, MOUSEUP_LISTENER, "정적인 mouseup 선택 listener")
        } ?: return null
        val usesSelection = scripts.any { script ->
            control.checkCancelled()
            GET_SELECTION.containsMatchIn(codeOnly(script.source))
        }
        if (!usesSelection) return null
        val handlesMobileInput = scripts.any { script ->
            control.checkCancelled()
            listOf(POINTERUP_LISTENER, TOUCHEND_LISTENER, SELECTIONCHANGE_LISTENER)
                .any { event ->
                    control.checkCancelled()
                    eventInCode(script.source, event) != null
                }
        }
        if (handlesMobileInput) return null

        val unsafeEvidence = scripts.firstNotNullOfOrNull { script ->
            control.checkCancelled()
            sensitiveMouseEventEvidence(script)
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
            ?.takeIf(String::isNotBlank)
            ?: return null
        val applied = ActionPopupEntryAdapter().isExactlyApplied(
            manifestPath.parent,
            manifest,
            popup,
        )
        if (applied) {
            if (OPTIONS_PAGE_ACCESSIBLE_FEATURE !in profile.supportedFeatures) {
                return optionEntryDecision(
                    manifestPath = manifestPath,
                    popup = popup,
                    disposition = AdapterDisposition.UNAVAILABLE,
                    applied = false,
                    limitations = listOf(
                        "browser profile에서 삼성 Add-ons option page 진입을 확인하지 못했습니다.",
                    ),
                )
            }
            return optionEntryDecision(
                manifestPath = manifestPath,
                popup = popup,
                disposition = AdapterDisposition.REQUIRES_APPROVAL,
                applied = true,
            )
        }
        when (
            val existing = existingOptionPageState(
                manifest,
                manifestPath.parent,
            )
        ) {
            ExistingOptionPage.Absent -> Unit
            ExistingOptionPage.Valid -> return null
            is ExistingOptionPage.Invalid -> {
                return optionEntryDecision(
                    manifestPath = manifestPath,
                    popup = popup,
                    disposition = AdapterDisposition.UNAVAILABLE,
                    applied = false,
                    limitations = listOf(existing.reason),
                )
            }
        }
        if (OPTIONS_PAGE_ACCESSIBLE_FEATURE !in profile.supportedFeatures) {
            return optionEntryDecision(
                manifestPath = manifestPath,
                popup = popup,
                disposition = AdapterDisposition.UNAVAILABLE,
                applied = false,
                limitations = listOf(
                    "browser profile에서 삼성 Add-ons option page 진입을 확인하지 못했습니다.",
                ),
            )
        }
        return optionEntryDecision(
            manifestPath = manifestPath,
            popup = popup,
            disposition = AdapterDisposition.REQUIRES_APPROVAL,
            applied = false,
        )
    }

    private fun invalidExistingOptionPageDecision(
        manifestPath: Path,
        manifest: JsonObject,
        profile: BrowserProfile,
    ): AdapterDecision? {
        if (ACTION_POPUP_USER_ACCESSIBLE in profile.supportedFeatures) return null
        val popup = (manifest["action"] as? JsonObject)
            ?.stringOrNull("default_popup")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val invalid = existingOptionPageState(
            manifest,
            manifestPath.parent,
        ) as? ExistingOptionPage.Invalid ?: return null
        return optionEntryDecision(
            manifestPath = manifestPath,
            popup = popup,
            disposition = AdapterDisposition.UNAVAILABLE,
            applied = false,
            limitations = listOf(invalid.reason),
        )
    }

    private fun existingOptionPageState(
        manifest: JsonObject,
        root: Path,
    ): ExistingOptionPage {
        if ("options_ui" !in manifest && "options_page" !in manifest) {
            return ExistingOptionPage.Absent
        }
        if ("options_ui" in manifest) {
            val options = manifest["options_ui"] as? JsonObject
                ?: return ExistingOptionPage.Invalid("options_ui가 객체가 아닙니다.")
            val page = options.stringOrNull("page")
                ?: return ExistingOptionPage.Invalid(
                    "options_ui.page가 문자열이 아닙니다.",
                )
            if ("open_in_tab" in options) {
                val openInTab = options["open_in_tab"] as? JsonPrimitive
                if (openInTab == null ||
                    openInTab.isString ||
                    openInTab.booleanOrNull == null
                ) {
                    return ExistingOptionPage.Invalid(
                        "options_ui.open_in_tab이 boolean이 아닙니다.",
                    )
                }
            }
            invalidOptionPageResource(root, "options_ui.page", page)?.let {
                return ExistingOptionPage.Invalid(it)
            }
        }
        if ("options_page" in manifest) {
            val page = manifest.stringOrNull("options_page")
                ?: return ExistingOptionPage.Invalid(
                    "options_page가 문자열이 아닙니다.",
                )
            invalidOptionPageResource(root, "options_page", page)?.let {
                return ExistingOptionPage.Invalid(it)
            }
        }
        return ExistingOptionPage.Valid
    }

    private fun invalidOptionPageResource(
        root: Path,
        name: String,
        value: String,
    ): String? {
        val path = ActionPopupEntrySafety.canonicalResourcePath(value)
            ?: return "$name 경로가 안전한 내부 리소스가 아닙니다: $value"
        if (!Files.isRegularFile(root.resolve(path), NOFOLLOW_LINKS)) {
            return "$name 리소스를 확인할 수 없습니다: $value"
        }
        return null
    }

    private fun optionEntryDecision(
        manifestPath: Path,
        popup: String,
        disposition: AdapterDisposition,
        applied: Boolean,
        limitations: List<String> = emptyList(),
    ): AdapterDecision {
        val manifestSource = runCatching { manifestPath.readText() }.getOrDefault("")
        val popupOffset = DEFAULT_POPUP.find(manifestSource)?.range?.first ?: 0
        val unavailable = disposition == AdapterDisposition.UNAVAILABLE
        return AdapterDecision(
            id = ACTION_POPUP_ENTRY,
            version = 2,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = disposition,
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
                listOf("삼성 Add-ons 설정 메뉴에 기존 action popup을 연결합니다.")
            },
            remainingLimitations = if (unavailable) {
                limitations
            } else {
                listOf("popup은 삼성 Add-ons 상세 화면의 설정 메뉴에서 별도 탭으로 열립니다.")
            },
        )
    }

    private fun storageDecision(
        extensionRoot: Path,
        manifest: JsonObject,
        index: RuntimeResourceIndex,
        profile: BrowserProfile,
    ): StorageDecision {
        val rewriter = JavaScriptTokenRewriter.ControlledFactory.create(
            control::checkCancelled,
        )
        val safeEvidence = mutableListOf<SourceEvidence>()
        val unsafeEvidence = mutableListOf<SourceEvidence>()
        val convertedSources = mutableMapOf<String, String>()
        val inspected = index.scripts
            .map { script ->
                control.checkCancelled()
                script to rewriter.inspect(script.source)
            }
        if (inspected.none { (_, inspection) ->
                control.checkCancelled()
                inspection.storageIntent
            }
        ) {
            return StorageDecision()
        }
        inspected.forEach { (script, inspection) ->
            control.checkCancelled()
            val rewrite = rewriter.rewrite(script.source)
            if (isStandaloneJavaScript(script.relativePath)) {
                if (inspection.unsafeAccesses.isEmpty() &&
                    rewrite.replacements.isNotEmpty()
                ) {
                    convertedSources[script.relativePath] = rewrite.source
                }
                safeEvidence += inspection.syncAccesses.map { access ->
                    control.checkCancelled()
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
                    control.checkCancelled()
                    SourceEvidence(
                        relativePath = script.relativePath,
                        line = script.lineOffset + access.line,
                        detail = "인라인 script의 storage.sync 접근은 안전하게 치환할 수 없음",
                    )
                }
            }
            unsafeEvidence += inspection.unsafeAccesses.map { access ->
                control.checkCancelled()
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
        val bothUnavailable =
            STORAGE_LOCAL_FEATURE in profile.unavailableFeatures && syncUnavailable
        if (profileEvidenceUnknown && !bothUnavailable) {
            return StorageDecision(unknown = true)
        }
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
            checkCancelled = control::checkCancelled,
        )
        val codeOnly = codeOnly(script.source)
        MOUSEUP_LISTENER.findAll(sourceWithoutComments)
            .filter {
                control.checkCancelled()
                codeOnly.getOrNull(it.range.first)?.isJavaIdentifierStart() == true
            }
            .forEach { listener ->
                control.checkCancelled()
                val callback = readCallbackBody(
                    codeOnly,
                    listener.range.last + 1,
                ) ?: return evidence(
                    script,
                    listener.range.first,
                    "mouseup callback을 정적으로 해석할 수 없음",
                )
                control.checkCancelled()
                val member = eventMemberRegex(callback.parameter)
                    .find(codeOnly, callback.bodyStart)
                    .also { control.checkCancelled() }
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
        control.checkCancelled()
        val declaration = Regex(
            """\bfunction\s+${Regex.escape(name)}\s*\(""",
        ).find(codeOnly) ?: return null
        control.checkCancelled()
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
            checkLoop(index, start)
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
            checkLoop(index, start)
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
        while (source.getOrNull(cursor)?.isJavaIdentifierPart() == true) {
            checkLoop(cursor, start)
            cursor++
        }
        return cursor
    }

    private fun skipWhitespace(source: String, start: Int): Int {
        var cursor = start
        while (source.getOrNull(cursor)?.isWhitespace() == true) {
            checkLoop(cursor, start)
            cursor++
        }
        return cursor
    }

    private fun eventInCode(source: String, event: Regex): Int? {
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            source,
            maskLiterals = false,
            checkCancelled = control::checkCancelled,
        )
        val codeOnly = codeOnly(source)
        return event.findAll(sourceWithoutComments)
            .firstOrNull {
                control.checkCancelled()
                codeOnly.getOrNull(it.range.first)?.isJavaIdentifierStart() == true
            }
            ?.range
            ?.first
    }

    private fun codeOnly(source: String): String =
        JavaScriptLexicalMask.mask(
            source,
            maskLiterals = true,
            checkCancelled = control::checkCancelled,
        )

    private fun evidence(
        script: RuntimeResourceIndex.RuntimeScript,
        offset: Int,
        detail: String,
    ) = SourceEvidence(
        relativePath = script.relativePath,
        line = script.lineOffset + lineAt(script.source, offset),
        detail = detail,
    )

    private fun lineAt(source: String, offset: Int): Int {
        var lines = 1
        val end = offset.coerceIn(0, source.length)
        for (index in 0 until end) {
            checkLoop(index, 0)
            if (source[index] == '\n') lines++
        }
        return lines
    }

    private fun checkLoop(position: Int, start: Int) {
        if ((position - start) % CHECKPOINT_INTERVAL == 0) {
            control.checkCancelled()
        }
    }

    private fun conversionState(decisions: List<AdapterDecision>): ConversionState =
        when {
            decisions.any {
                it.disposition == AdapterDisposition.UNAVAILABLE
            } -> ConversionState.UNAVAILABLE
            decisions.any {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL ||
                    it.disposition == AdapterDisposition.OPTIONAL_APPROVAL
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

    private sealed interface ExistingOptionPage {
        data object Absent : ExistingOptionPage
        data object Valid : ExistingOptionPage
        data class Invalid(val reason: String) : ExistingOptionPage
    }

    private data class CallbackBody(
        val parameter: String,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    companion object {
        const val DESKTOP_SELECTION_EVENT_BRIDGE =
            "desktop-selection-event-bridge"
        const val ACTION_POPUP_ENTRY = "action-popup-entry"
        const val POPUP_NATIVE_AUTOFILL_GUARD = "popup-native-autofill-guard"
        const val STORAGE_FALLBACK = "storage-fallback"

        val ADAPTER_IDS: Set<String> = listOf(
            ACTION_POPUP_ENTRY,
            DESKTOP_SELECTION_EVENT_BRIDGE,
            POPUP_NATIVE_AUTOFILL_GUARD,
            STORAGE_FALLBACK,
        ).sorted().toSet()

        private const val ACTION_POPUP_USER_ACCESSIBLE =
            "action.popup.user-accessible"
        private const val OPTIONS_PAGE_ACCESSIBLE_FEATURE =
            "extension.options-page.user-accessible"
        private const val STORAGE_LOCAL_FEATURE = "storage.local"
        private const val STORAGE_SYNC_FEATURE = "storage.sync"
        private const val CHECKPOINT_INTERVAL = 16_384

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
