package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import dev.sixbridge.core.compat.RuntimeSourceScanner
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension

internal class BrowserInternalUrlAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER
    override val version: Int = 1

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        val decision = selected.toDecision()
        check(
            decision.id == id &&
                decision.version == version &&
                decision.risk == AdapterRisk.BEHAVIOR_CHANGE &&
                decision.disposition == AdapterDisposition.AUTOMATIC,
        ) {
            "browser internal URL mapper 선재가 올바르지 않습니다."
        }
        val root = context.stagedRoot
        val index = BrowserInternalUrlProof.runtimeIndex(root)
        val existingProof = BrowserInternalUrlProof.detect(
            root,
            index,
            context.profile,
        )
        if (BrowserInternalUrlProof.hasOwnedState(root)) {
            check(existingProof != null) {
                "browser internal URL mapper 소유 상태가 일관되지 않습니다."
            }
            return decision.copy(applied = true)
        }

        val approved = approvedTargets(decision, context.profile)
        val occurrences = BrowserInternalUrlProof.scan(index, root)
            .filter { occurrence ->
                BrowserInternalUrlMapper.classify(
                    occurrence.value,
                    context.profile,
                ) != null
            }
        val actualKeys = occurrences.map { occurrence ->
            val rule = requireNotNull(
                BrowserInternalUrlMapper.classify(
                    occurrence.value,
                    context.profile,
                ),
            )
            EvidenceKey(
                occurrence.relativePath,
                occurrence.line,
                BrowserInternalUrlMapper.evidenceDetail(rule),
            )
        }.sortedWith(EVIDENCE_KEY_ORDER)
        check(actualKeys == approved.keys) {
            "승인된 browser internal URL 근거와 staged runtime span이 다릅니다."
        }

        val spans = approved.groups.map { group ->
            val rule = requireNotNull(BrowserInternalUrlMapper.ruleById(group.ruleId))
            BrowserInternalUrlMarkerSpan(
                path = group.path,
                rule = rule.id,
                occurrenceCount = group.count,
                originalSpanSha256 = BrowserInternalUrlProof.sha256(
                    rule.original.toByteArray(UTF_8),
                ),
                replacementSpanSha256 = BrowserInternalUrlProof.sha256(
                    rule.replacement.toByteArray(UTF_8),
                ),
            )
        }
        val markerBytes = BrowserInternalUrlProof.markerBytes(spans)
        val assets = approved.groups
            .mapNotNull { group ->
                BrowserInternalUrlProof.assetFor(
                    requireNotNull(BrowserInternalUrlMapper.ruleById(group.ruleId)),
                )
            }
            .distinctBy(BrowserInternalUrlAsset::path)
        (assets + BrowserInternalUrlAsset(BrowserInternalUrlProof.MARKER_PATH, markerBytes))
            .forEach { BrowserInternalUrlProof.preflight(root, it) }

        occurrences.groupBy(BrowserInternalUrlOccurrence::relativePath)
            .toSortedMap()
            .forEach { (relativePath, pathOccurrences) ->
                val path = BrowserInternalUrlProof.verifiedFile(root, relativePath)
                var source = Files.readString(path, UTF_8)
                pathOccurrences.sortedByDescending { it.contentRange.first }
                    .forEach { occurrence ->
                        val rule = requireNotNull(
                            BrowserInternalUrlMapper.classify(
                                occurrence.value,
                                context.profile,
                            ),
                        )
                        check(source.substring(occurrence.contentRange) == rule.original) {
                            "browser internal URL span이 rewrite 직전 변경되었습니다."
                        }
                        source = source.replaceRange(
                            occurrence.contentRange,
                            rule.replacement,
                        )
                    }
                Files.writeString(path, source, UTF_8)
            }
        assets.forEach { BrowserInternalUrlProof.install(root, it) }
        BrowserInternalUrlProof.install(
            root,
            BrowserInternalUrlAsset(BrowserInternalUrlProof.MARKER_PATH, markerBytes),
        )
        check(
            BrowserInternalUrlProof.detect(
                root,
                BrowserInternalUrlProof.runtimeIndex(root),
                context.profile,
            ) != null,
        ) {
            "browser internal URL 변환 proof를 재검증할 수 없습니다."
        }
        return decision.copy(applied = true)
    }

    private fun approvedTargets(
        decision: AdapterDecision,
        profile: BrowserProfile,
    ): ApprovedTargets {
        check(decision.evidence.isNotEmpty()) {
            "승인된 browser internal URL 근거가 없습니다."
        }
        val parsed = decision.evidence.map { evidence ->
            val rule = BrowserInternalUrlMapper.parseEvidenceDetail(evidence.detail)
                ?: throw IllegalStateException(
                    "browser internal URL 근거 형식이 올바르지 않습니다.",
                )
            check(BrowserInternalUrlMapper.classify(rule.original, profile) == rule) {
                "browser profile에서 승인된 internal URL rule을 확인할 수 없습니다."
            }
            val path = ActionPopupEntrySafety.canonicalResourcePath(evidence.relativePath)
            check(path == evidence.relativePath && evidence.line != null) {
                "browser internal URL 근거 path 또는 line이 올바르지 않습니다."
            }
            ParsedEvidence(
                key = EvidenceKey(path, evidence.line, evidence.detail),
                path = path,
                rule = rule,
            )
        }
        val groups = parsed.groupBy { it.path to it.rule.id }
            .map { (key, values) -> ApprovedGroup(key.first, key.second, values.size) }
            .sortedWith(compareBy(ApprovedGroup::path, ApprovedGroup::ruleId))
        return ApprovedTargets(
            keys = parsed.map(ParsedEvidence::key).sortedWith(EVIDENCE_KEY_ORDER),
            groups = groups,
        )
    }

    private data class ParsedEvidence(
        val key: EvidenceKey,
        val path: String,
        val rule: BrowserInternalUrlRule,
    )

    private data class EvidenceKey(
        val path: String,
        val line: Int,
        val detail: String,
    )

    private data class ApprovedGroup(
        val path: String,
        val ruleId: String,
        val count: Int,
    )

    private data class ApprovedTargets(
        val keys: List<EvidenceKey>,
        val groups: List<ApprovedGroup>,
    )

    private companion object {
        val EVIDENCE_KEY_ORDER = compareBy(
            EvidenceKey::path,
            EvidenceKey::line,
            EvidenceKey::detail,
        )
    }
}

internal data class BrowserInternalUrlOccurrence(
    val relativePath: String,
    val value: String,
    val contentRange: IntRange,
    val line: Int,
)

internal data class BrowserInternalUrlMarkerSpan(
    val path: String,
    val rule: String,
    val occurrenceCount: Int,
    val originalSpanSha256: String,
    val replacementSpanSha256: String,
)

internal data class BrowserInternalUrlAsset(
    val path: String,
    val bytes: ByteArray,
)

internal object BrowserInternalUrlProof {
    const val MARKER_PATH = "sixbridge/browser-internal-url-mapper.v1.json"

    fun runtimeIndex(root: Path): RuntimeResourceIndex {
        val manifest = Json.parseToJsonElement(
            Files.readString(verifiedFile(root, "manifest.json"), UTF_8),
        ) as JsonObject
        return RuntimeResourceIndex.create(root, manifest)
    }

    fun scan(index: RuntimeResourceIndex, root: Path): List<BrowserInternalUrlOccurrence> =
        index.resources.sortedBy { relativePath(root, it) }.flatMap { resource ->
            val relativePath = relativePath(root, resource)
            val source = Files.readString(resource, UTF_8)
            when (resource.extension.lowercase()) {
                "html" -> SafeHtmlDocument.parse(source) {}.urlAttributes.map {
                    BrowserInternalUrlOccurrence(
                        relativePath,
                        it.value,
                        it.contentRange,
                        lineAt(source, it.contentRange.first),
                    )
                }
                "js", "mjs", "cjs" -> RuntimeSourceScanner
                    .scanStaticStringLiterals(source) {}
                    .map {
                        BrowserInternalUrlOccurrence(
                            relativePath,
                            it.value,
                            it.contentRange,
                            lineAt(source, it.contentRange.first),
                        )
                    }
                else -> emptyList()
            }
        }

    fun detect(
        root: Path,
        index: RuntimeResourceIndex,
        profile: BrowserProfile,
    ): List<BrowserInternalUrlMarkerSpan>? = runCatching {
        val marker = root.resolve(MARKER_PATH)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS)) return null
        val json = Json.parseToJsonElement(Files.readString(marker, UTF_8)) as JsonObject
        val version = json["version"] as? JsonPrimitive
        val array = json["spans"] as? JsonArray
        if (json.keys != setOf("adapter", "version", "spans") ||
            json.string("adapter") != MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER ||
            version == null || version.isString || version.intOrNull != 1 ||
            array == null || array.isEmpty()
        ) {
            return null
        }
        val spans = array.map { element ->
            val span = element as? JsonObject ?: return null
            if (span.keys != SPAN_KEYS) return null
            val count = span["occurrenceCount"] as? JsonPrimitive ?: return null
            if (count.isString || count.intOrNull == null || count.intOrNull!! <= 0) return null
            val path = span.string("path") ?: return null
            val ruleId = span.string("rule") ?: return null
            val rule = BrowserInternalUrlMapper.ruleById(ruleId) ?: return null
            if (ActionPopupEntrySafety.canonicalResourcePath(path) != path ||
                BrowserInternalUrlMapper.classify(rule.original, profile) != rule
            ) {
                return null
            }
            BrowserInternalUrlMarkerSpan(
                path = path,
                rule = ruleId,
                occurrenceCount = count.intOrNull!!,
                originalSpanSha256 = span.string("originalSpanSha256") ?: return null,
                replacementSpanSha256 = span.string("replacementSpanSha256") ?: return null,
            ).also {
                if (it.originalSpanSha256 != sha256(rule.original.toByteArray(UTF_8)) ||
                    it.replacementSpanSha256 != sha256(rule.replacement.toByteArray(UTF_8))
                ) {
                    return null
                }
            }
        }.sortedWith(compareBy(BrowserInternalUrlMarkerSpan::path, BrowserInternalUrlMarkerSpan::rule))
        if (spans.map { it.path to it.rule }.distinct().size != spans.size) return null
        if (!markerBytes(spans).contentEquals(Files.readAllBytes(marker))) return null
        spans.mapNotNull { assetFor(requireNotNull(BrowserInternalUrlMapper.ruleById(it.rule))) }
            .distinctBy(BrowserInternalUrlAsset::path)
            .forEach { asset ->
                if (!exactBytes(root.resolve(asset.path), asset.bytes)) return null
            }
        val occurrences = scan(index, root)
        val resources = index.resources.mapTo(mutableSetOf()) { relativePath(root, it) }
        if (spans.any { it.path !in resources }) return null
        val expectedReplacements = spans.groupBy { span ->
            val rule = requireNotNull(BrowserInternalUrlMapper.ruleById(span.rule))
            span.path to rule.replacement
        }.mapValues { (_, values) -> values.sumOf(BrowserInternalUrlMarkerSpan::occurrenceCount) }
        val actualReplacements = occurrences
            .filter { occurrence ->
                expectedReplacements.containsKey(occurrence.relativePath to occurrence.value)
            }
            .groupingBy { it.relativePath to it.value }
            .eachCount()
        if (actualReplacements != expectedReplacements) return null
        if (occurrences.any { occurrence ->
                spans.any { span ->
                    val rule = requireNotNull(BrowserInternalUrlMapper.ruleById(span.rule))
                    occurrence.relativePath == span.path && occurrence.value == rule.original
                }
            }
        ) {
            return null
        }
        spans
    }.getOrNull()

    fun evidence(
        spans: List<BrowserInternalUrlMarkerSpan>,
        index: RuntimeResourceIndex,
        root: Path,
    ): List<SourceEvidence> {
        val occurrences = scan(index, root)
        return spans.flatMap { span ->
            val rule = requireNotNull(BrowserInternalUrlMapper.ruleById(span.rule))
            val lines = occurrences.filter {
                it.relativePath == span.path && it.value == rule.replacement
            }.map(BrowserInternalUrlOccurrence::line)
            List(span.occurrenceCount) { indexInRule ->
                SourceEvidence(
                    relativePath = span.path,
                    line = lines.getOrNull(indexInRule) ?: lines.firstOrNull(),
                    detail = BrowserInternalUrlMapper.evidenceDetail(rule),
                )
            }
        }.sortedWith(
            compareBy(SourceEvidence::relativePath, SourceEvidence::line, SourceEvidence::detail),
        )
    }

    fun hasOwnedState(root: Path): Boolean =
        Files.exists(root.resolve(MARKER_PATH), NOFOLLOW_LINKS) ||
            ASSET_PATHS.any { Files.exists(root.resolve(it), NOFOLLOW_LINKS) }

    fun markerBytes(spans: List<BrowserInternalUrlMarkerSpan>): ByteArray = JsonObject(
        linkedMapOf(
            "adapter" to JsonPrimitive(MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER),
            "version" to JsonPrimitive(1),
            "spans" to JsonArray(
                spans.sortedWith(
                    compareBy(BrowserInternalUrlMarkerSpan::path, BrowserInternalUrlMarkerSpan::rule),
                ).map { span ->
                    JsonObject(
                        linkedMapOf(
                            "path" to JsonPrimitive(span.path),
                            "rule" to JsonPrimitive(span.rule),
                            "occurrenceCount" to JsonPrimitive(span.occurrenceCount),
                            "originalSpanSha256" to JsonPrimitive(span.originalSpanSha256),
                            "replacementSpanSha256" to JsonPrimitive(span.replacementSpanSha256),
                        ),
                    )
                },
            ),
        ),
    ).toString().toByteArray(UTF_8)

    fun assetFor(rule: BrowserInternalUrlRule): BrowserInternalUrlAsset? =
        rule.replacement.removePrefix("/").takeIf { it in ASSET_PATHS }?.let { path ->
            val resource = "dev/sixbridge/adapters/${path.substringAfterLast('/')}"
            BrowserInternalUrlAsset(
                path,
                requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                    "browser internal URL 안내 asset을 찾을 수 없습니다: $path"
                }.use { it.readBytes() },
            )
        }

    fun preflight(root: Path, asset: BrowserInternalUrlAsset) {
        var parent = root
        asset.path.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (Files.exists(parent, NOFOLLOW_LINKS) &&
                !Files.isDirectory(parent, NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException("browser internal URL asset parent가 디렉터리가 아닙니다.")
            }
        }
        val target = root.resolve(asset.path)
        if (Files.exists(target, NOFOLLOW_LINKS) && !exactBytes(target, asset.bytes)) {
            throw IllegalStateException("browser internal URL asset bytes가 다릅니다: ${asset.path}")
        }
    }

    fun install(root: Path, asset: BrowserInternalUrlAsset) {
        val target = root.resolve(asset.path)
        if (Files.exists(target, NOFOLLOW_LINKS)) return
        Files.createDirectories(requireNotNull(target.parent))
        Files.write(target, asset.bytes)
    }

    fun verifiedFile(root: Path, relativePath: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(relativePath).normalize()
        check(target.startsWith(normalizedRoot) && Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            "browser internal URL runtime resource를 확인할 수 없습니다: $relativePath"
        }
        return target
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun exactBytes(path: Path, expected: ByteArray): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.readAllBytes(path).contentEquals(expected)

    private fun relativePath(root: Path, path: Path): String =
        root.toAbsolutePath().normalize()
            .relativize(path.toAbsolutePath().normalize())
            .joinToString("/") { it.toString() }

    private fun lineAt(source: String, offset: Int): Int =
        1 + source.substring(0, offset.coerceIn(0, source.length)).count { it == '\n' }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private val SPAN_KEYS = setOf(
        "path",
        "rule",
        "occurrenceCount",
        "originalSpanSha256",
        "replacementSpanSha256",
    )
    private val ASSET_PATHS = setOf(
        "sixbridge/internal-url-manual-password-manager.v1.html",
        "sixbridge/internal-url-unsupported-flags.v1.html",
        "sixbridge/internal-url-unsupported-inspect.v1.html",
    )
}
