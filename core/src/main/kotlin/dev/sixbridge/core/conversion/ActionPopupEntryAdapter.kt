package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.JavaScriptLexicalMask
import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

class ActionPopupEntryAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.ACTION_POPUP_ENTRY
    override val version: Int = 1

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        val workingRoot = context.requireActive().stagedRoot
        val decision = selected.toDecision()
        if (decision.id != id || decision.version != version ||
            decision.disposition != AdapterDisposition.REQUIRES_APPROVAL
        ) {
            return decision
        }
        val manifestPath = workingRoot.resolve(MANIFEST)
        val manifest = runCatching {
            Json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull() ?: return decision
        val runtimeIndex = RuntimeResourceIndex.create(workingRoot, manifest)
        if (runtimeIndex.unresolvedDependency) {
            throw IllegalStateException("background runtime dependency를 완전히 해석할 수 없습니다.")
        }
        if (ActionPopupEntrySafety.hasWorkerUrlDependency(runtimeIndex)) {
            throw IllegalStateException(
                "background runtime closure가 worker URL에 의존해 bootstrap semantics를 보존할 수 없습니다.",
            )
        }
        val popupPath = (manifest[ACTION] as? JsonObject)
            ?.stringOrNull(DEFAULT_POPUP)
            ?.takeIf { it.isNotBlank() }
            ?: return decision
        val canonicalPopupPath = ActionPopupEntrySafety.canonicalResourcePath(popupPath)
            ?: throw IllegalStateException("popup 경로가 안전하지 않습니다: $popupPath")
        verifyResource(workingRoot, canonicalPopupPath, "popup")
        val contentScripts = manifest[CONTENT_SCRIPTS] as? JsonArray ?: return decision
        if (contentScripts.none(::hasJavaScript)) return decision

        val contentBytes = resourceBytes(CONTENT_RESOURCE_PATH)
        val backgroundBytes = generatedBackgroundBytes(canonicalPopupPath)
        val backgroundPlan = backgroundPlan(workingRoot, manifest)
        val assets = buildList {
            add(Asset(CONTENT_RELATIVE_PATH, contentBytes))
            add(Asset(BACKGROUND_RELATIVE_PATH, backgroundBytes))
            backgroundPlan.bootstrapAsset?.let(::add)
        }
        assets.forEach { preflightAsset(workingRoot, it) }

        var changed = false
        val updatedContentScripts = JsonArray(contentScripts.map { entry ->
            val block = entry as? JsonObject ?: return@map entry
            val scripts = block[SCRIPTS] as? JsonArray ?: return@map entry
            if (scripts.isEmpty()) return@map entry
            val canonicalScripts = JsonArray(
                listOf(JsonPrimitive(CONTENT_RELATIVE_PATH)) +
                    scripts.filterNot(::isContentAssetAlias),
            )
            if (scripts == canonicalScripts) return@map entry
            changed = true
            JsonObject(block + (SCRIPTS to canonicalScripts))
        })
        val existingBackground = manifest[BACKGROUND] as? JsonObject
        if (manifest.containsKey(BACKGROUND) && existingBackground == null) {
            throw IllegalStateException("manifest background가 객체가 아닙니다.")
        }
        val updatedBackground = JsonObject(
            existingBackground.orEmpty() +
                (SERVICE_WORKER to JsonPrimitive(backgroundPlan.manifestWorker)),
        )
        if (existingBackground != updatedBackground) changed = true

        assets.forEach { installAsset(workingRoot, it) }
        if (changed) {
            Files.writeString(
                manifestPath,
                JsonObject(
                    manifest +
                        (CONTENT_SCRIPTS to updatedContentScripts) +
                        (BACKGROUND to updatedBackground),
                ).toString(),
            )
        }
        return decision.copy(applied = true)
    }

    private fun backgroundPlan(
        workingRoot: Path,
        manifest: JsonObject,
    ): BackgroundPlan {
        val background = manifest[BACKGROUND] as? JsonObject
        val worker = background?.stringOrNull(SERVICE_WORKER)
            ?: return BackgroundPlan(BACKGROUND_RELATIVE_PATH, null)
        val canonicalWorker = ActionPopupEntrySafety.canonicalResourcePath(worker)
            ?: throw IllegalStateException("service worker 경로가 안전하지 않습니다: $worker")
        val module = background.stringOrNull(TYPE) == MODULE
        return when (canonicalWorker) {
            BACKGROUND_RELATIVE_PATH -> BackgroundPlan(BACKGROUND_RELATIVE_PATH, null)
            CONTENT_RELATIVE_PATH ->
                throw IllegalStateException("content adapter를 service worker로 사용할 수 없습니다.")
            else -> if (Path.of(canonicalWorker).fileName.toString() == BOOTSTRAP_FILE_NAME) {
                val bootstrapPath = workingRoot.resolve(canonicalWorker)
                if (!Files.isRegularFile(bootstrapPath, NOFOLLOW_LINKS)) {
                    throw IllegalStateException("generated bootstrap provenance를 확인할 수 없습니다.")
                }
                val originalWorker = parseOriginalWorker(
                    Files.readString(bootstrapPath),
                    module,
                    canonicalWorker,
                )
                verifyOriginalWorker(workingRoot, originalWorker)
                val bootstrapBytes = bootstrapBytes(originalWorker, module)
                BackgroundPlan(
                    canonicalWorker,
                    Asset(canonicalWorker, bootstrapBytes),
                )
            } else {
                verifyOriginalWorker(workingRoot, canonicalWorker)
                val bootstrapPath = generatedBootstrapPath(canonicalWorker)
                BackgroundPlan(
                    bootstrapPath,
                    Asset(bootstrapPath, bootstrapBytes(canonicalWorker, module)),
                )
            }
        }
    }

    private fun verifyOriginalWorker(workingRoot: Path, relativePath: String) {
        verifyResource(workingRoot, relativePath, "원본 service worker")
    }

    private fun verifyResource(
        workingRoot: Path,
        relativePath: String,
        label: String,
    ): Path {
        val root = workingRoot.toAbsolutePath().normalize()
        val resource = root.resolve(relativePath).normalize()
        if (!resource.startsWith(root) ||
            !Files.isRegularFile(resource, NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("$label 리소스를 확인할 수 없습니다: $relativePath")
        }
        return resource
    }

    private fun bootstrapBytes(originalWorker: String, module: Boolean): ByteArray {
        val bootstrapPath = generatedBootstrapPath(originalWorker)
        val bootstrapDirectory = relativeParent(bootstrapPath)
        val originalImport = JsonPrimitive(
            relativeImport(bootstrapDirectory, originalWorker),
        ).toString()
        val adapterImport = JsonPrimitive(
            relativeImport(bootstrapDirectory, BACKGROUND_RELATIVE_PATH),
        ).toString()
        val source = if (module) {
            "import $originalImport;\nimport $adapterImport;\n"
        } else {
            "importScripts($originalImport, $adapterImport);\n"
        }
        return source.toByteArray(UTF_8)
    }

    private fun parseOriginalWorker(
        source: String,
        module: Boolean,
        bootstrapPath: String,
    ): String {
        val bootstrapDirectory = relativeParent(bootstrapPath)
        val encodedImports = if (module) {
            if (!source.startsWith("import ") || !source.endsWith(";\n")) {
                throw IllegalStateException("generated module bootstrap provenance가 일치하지 않습니다.")
            }
            source.removeSuffix(";\n")
                .split(";\nimport ")
                .takeIf { it.size == 2 }
                ?.mapIndexed { index, value ->
                    if (index == 0) value.removePrefix("import ") else value
                }
        } else {
            val prefix = "importScripts("
            val suffix = ");\n"
            if (!source.startsWith(prefix) || !source.endsWith(suffix)) {
                throw IllegalStateException("generated classic bootstrap provenance가 일치하지 않습니다.")
            }
            runCatching {
                Json.parseToJsonElement(
                    "[${source.removePrefix(prefix).removeSuffix(suffix)}]",
                ) as? JsonArray
            }.getOrNull()
                ?.takeIf { it.size == 2 }
                ?.map { it.toString() }
        }
            ?: throw IllegalStateException("generated bootstrap import 개수가 일치하지 않습니다.")
        val imports = encodedImports.map { encoded ->
            runCatching {
                (Json.parseToJsonElement(encoded) as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
            }.getOrNull()
                ?: throw IllegalStateException("generated bootstrap import를 해석할 수 없습니다.")
        }
        val original = resolveGeneratedImport(bootstrapDirectory, imports[0])
        val adapter = resolveGeneratedImport(bootstrapDirectory, imports[1])
        if (adapter != BACKGROUND_RELATIVE_PATH ||
            generatedBootstrapPath(original) != bootstrapPath ||
            !bootstrapBytes(original, module).contentEquals(source.toByteArray(UTF_8))
        ) {
            throw IllegalStateException("generated bootstrap provenance가 일치하지 않습니다.")
        }
        return original
    }

    private fun generatedBootstrapPath(originalWorker: String): String {
        val parent = relativeParent(originalWorker)
        return if (parent.isEmpty()) {
            BOOTSTRAP_FILE_NAME
        } else {
            "$parent/$BOOTSTRAP_FILE_NAME"
        }
    }

    private fun relativeParent(relativePath: String): String =
        Path.of(relativePath).parent
            ?.invariantSeparatorsPathString
            .orEmpty()

    private fun relativeImport(fromDirectory: String, target: String): String {
        val from = Path.of(fromDirectory.ifEmpty { "." })
        val relative = from.relativize(Path.of(target))
            .normalize()
            .invariantSeparatorsPathString
        return if (relative.startsWith("../")) relative else "./$relative"
    }

    private fun resolveGeneratedImport(baseDirectory: String, importPath: String): String {
        if (!ActionPopupEntrySafety.isSafeUrlPath(importPath) ||
            importPath.startsWith("/")
        ) {
            throw IllegalStateException("generated bootstrap import 경로가 안전하지 않습니다.")
        }
        val base = Path.of(baseDirectory.ifEmpty { "." })
        val resolved = base.resolve(importPath)
            .normalize()
            .invariantSeparatorsPathString
        if (resolved == ".." || resolved.startsWith("../")) {
            throw IllegalStateException("generated bootstrap import가 확장 루트를 벗어납니다.")
        }
        if (relativeImport(baseDirectory, resolved) != importPath) {
            throw IllegalStateException("generated bootstrap import가 canonical하지 않습니다.")
        }
        return resolved.removePrefix("./")
    }

    private fun generatedBackgroundBytes(popupPath: String): ByteArray {
        val template = resourceBytes(BACKGROUND_RESOURCE_PATH).toString(UTF_8)
        check(template.countOccurrences(POPUP_PLACEHOLDER) == 1) {
            "background adapter template placeholder가 하나가 아닙니다."
        }
        return template.replace(
            POPUP_PLACEHOLDER,
            JsonPrimitive(popupPath).toString(),
        ).toByteArray(UTF_8)
    }

    private fun resourceBytes(resourcePath: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(resourcePath)
            ?.use { it.readBytes() }
            ?: error("adapter resource를 찾지 못했습니다: $resourcePath")

    private fun preflightAsset(workingRoot: Path, asset: Asset) {
        var parent = workingRoot
        asset.relativePath.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (Files.exists(parent, NOFOLLOW_LINKS) &&
                !Files.isDirectory(parent, NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException("adapter asset parent가 디렉터리가 아닙니다: $parent")
            }
        }
        val destination = workingRoot.resolve(asset.relativePath)
        if (!Files.exists(destination, NOFOLLOW_LINKS)) return
        if (!Files.isRegularFile(destination, NOFOLLOW_LINKS) ||
            !Files.readAllBytes(destination).contentEquals(asset.bytes)
        ) {
            throw IllegalStateException(
                "기존 adapter asset과 bytes가 다릅니다: ${asset.relativePath}",
            )
        }
    }

    private fun installAsset(workingRoot: Path, asset: Asset) {
        val destination = workingRoot.resolve(asset.relativePath)
        if (Files.exists(destination, NOFOLLOW_LINKS)) return
        if (!Files.exists(destination.parent, NOFOLLOW_LINKS)) {
            Files.createDirectory(destination.parent)
        }
        Files.write(destination, asset.bytes)
    }

    private fun hasJavaScript(element: kotlinx.serialization.json.JsonElement): Boolean =
        ((element as? JsonObject)?.get(SCRIPTS) as? JsonArray)?.isNotEmpty() == true

    private fun isContentAssetAlias(
        element: kotlinx.serialization.json.JsonElement,
    ): Boolean = (element as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.let(ActionPopupEntrySafety::canonicalResourcePath) == CONTENT_RELATIVE_PATH

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }

    private data class Asset(
        val relativePath: String,
        val bytes: ByteArray,
    )

    private data class BackgroundPlan(
        val manifestWorker: String,
        val bootstrapAsset: Asset?,
    )

    private companion object {
        const val MANIFEST = "manifest.json"
        const val ACTION = "action"
        const val DEFAULT_POPUP = "default_popup"
        const val CONTENT_SCRIPTS = "content_scripts"
        const val SCRIPTS = "js"
        const val BACKGROUND = "background"
        const val SERVICE_WORKER = "service_worker"
        const val TYPE = "type"
        const val MODULE = "module"

        const val CONTENT_FILE_NAME = "action-popup-entry-content.v1.js"
        const val BACKGROUND_FILE_NAME = "action-popup-entry-background.v1.js"
        const val BOOTSTRAP_FILE_NAME = "_sixbridge-action-popup-entry-bootstrap.v1.js"
        const val CONTENT_RELATIVE_PATH = "_sixbridge/$CONTENT_FILE_NAME"
        const val BACKGROUND_RELATIVE_PATH = "_sixbridge/$BACKGROUND_FILE_NAME"
        const val CONTENT_RESOURCE_PATH = "dev/sixbridge/adapters/$CONTENT_FILE_NAME"
        const val BACKGROUND_RESOURCE_PATH = "dev/sixbridge/adapters/$BACKGROUND_FILE_NAME"
        const val POPUP_PLACEHOLDER = "__SIXBRIDGE_POPUP_PATH_JSON__"
    }
}

internal object ActionPopupEntrySafety {
    private val LOCATION_IDENTIFIER = Regex("""\blocation\b""")
    private val IMPORT_META = Regex("""\bimport\s*\.\s*meta\b""")
    private val LOCATION_PROPERTY = Regex("""\[\s*(["'])location\1\s*]""")

    fun hasWorkerUrlDependency(index: RuntimeResourceIndex): Boolean =
        index.scripts.any {
            RuntimeResourceIndex.ResourceRole.BACKGROUND in it.roles &&
                usesWorkerUrl(it.source)
        }

    private fun usesWorkerUrl(source: String): Boolean {
        val commentsMasked = JavaScriptLexicalMask.mask(source, maskLiterals = false)
        val codeOnly = JavaScriptLexicalMask.mask(source, maskLiterals = true)
        return LOCATION_IDENTIFIER.containsMatchIn(codeOnly) ||
            IMPORT_META.containsMatchIn(codeOnly) ||
            LOCATION_PROPERTY.findAll(commentsMasked).any {
                codeOnly.getOrNull(it.range.first) == '['
            }
    }

    fun canonicalResourcePath(value: String): String? {
        if (!isSafeUrlPath(value)) return null
        return runCatching {
            Path.of(value.removePrefix("/"))
                .normalize()
                .invariantSeparatorsPathString
                .takeIf { it.isNotBlank() && it != ".." && !it.startsWith("../") }
        }.getOrNull()
    }

    fun isSafeUrlPath(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith("//") &&
            '\\' !in value &&
            '%' !in value &&
            ':' !in value &&
            '?' !in value &&
            '#' !in value
}
