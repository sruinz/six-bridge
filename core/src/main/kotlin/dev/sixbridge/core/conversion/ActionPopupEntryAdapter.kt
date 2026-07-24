package dev.sixbridge.core.conversion

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

    override fun apply(workingRoot: Path, decision: AdapterDecision): AdapterDecision {
        if (decision.id != id || decision.version != version ||
            decision.disposition != AdapterDisposition.REQUIRES_APPROVAL
        ) {
            return decision
        }
        val manifestPath = workingRoot.resolve(MANIFEST)
        val manifest = runCatching {
            Json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull() ?: return decision
        val popupPath = (manifest[ACTION] as? JsonObject)
            ?.stringOrNull(DEFAULT_POPUP)
            ?.takeIf { it.isNotBlank() }
            ?: return decision
        val contentScripts = manifest[CONTENT_SCRIPTS] as? JsonArray ?: return decision
        if (contentScripts.none(::hasJavaScript)) return decision

        val contentBytes = resourceBytes(CONTENT_RESOURCE_PATH)
        val backgroundBytes = generatedBackgroundBytes(popupPath)
        val backgroundPlan = backgroundPlan(workingRoot, manifest)
        val assets = buildList {
            add(Asset(CONTENT_RELATIVE_PATH, contentBytes))
            add(Asset(BACKGROUND_RELATIVE_PATH, backgroundBytes))
            backgroundPlan.bootstrapBytes?.let {
                add(Asset(BOOTSTRAP_RELATIVE_PATH, it))
            }
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
        val canonicalWorker = canonicalResourcePath(worker)
            ?: throw IllegalStateException("service worker 경로가 안전하지 않습니다: $worker")
        val module = background.stringOrNull(TYPE) == MODULE
        return when (canonicalWorker) {
            BACKGROUND_RELATIVE_PATH -> BackgroundPlan(BACKGROUND_RELATIVE_PATH, null)
            BOOTSTRAP_RELATIVE_PATH -> {
                val bootstrapPath = workingRoot.resolve(BOOTSTRAP_RELATIVE_PATH)
                if (!Files.isRegularFile(bootstrapPath, NOFOLLOW_LINKS)) {
                    throw IllegalStateException("generated bootstrap provenance를 확인할 수 없습니다.")
                }
                val originalWorker = parseOriginalWorker(
                    Files.readString(bootstrapPath),
                    module,
                )
                verifyOriginalWorker(workingRoot, originalWorker)
                BackgroundPlan(
                    BOOTSTRAP_RELATIVE_PATH,
                    bootstrapBytes(originalWorker, module),
                )
            }
            CONTENT_RELATIVE_PATH ->
                throw IllegalStateException("content adapter를 service worker로 사용할 수 없습니다.")
            else -> {
                verifyOriginalWorker(workingRoot, canonicalWorker)
                BackgroundPlan(
                    BOOTSTRAP_RELATIVE_PATH,
                    bootstrapBytes(canonicalWorker, module),
                )
            }
        }
    }

    private fun verifyOriginalWorker(workingRoot: Path, relativePath: String) {
        val worker = workingRoot.resolve(relativePath).normalize()
        if (!worker.startsWith(workingRoot.normalize()) ||
            !Files.isRegularFile(worker, NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("원본 service worker를 확인할 수 없습니다: $relativePath")
        }
    }

    private fun bootstrapBytes(originalWorker: String, module: Boolean): ByteArray {
        val originalImport = JsonPrimitive("../$originalWorker").toString()
        val adapterImport = JsonPrimitive("./$BACKGROUND_FILE_NAME").toString()
        val source = if (module) {
            "import $originalImport;\nimport $adapterImport;\n"
        } else {
            "importScripts($originalImport, $adapterImport);\n"
        }
        return source.toByteArray(UTF_8)
    }

    private fun parseOriginalWorker(source: String, module: Boolean): String {
        val adapterImport = JsonPrimitive("./$BACKGROUND_FILE_NAME").toString()
        val encodedOriginal = if (module) {
            val suffix = ";\nimport $adapterImport;\n"
            if (!source.startsWith("import ") || !source.endsWith(suffix)) {
                throw IllegalStateException("generated module bootstrap provenance가 일치하지 않습니다.")
            }
            source.removePrefix("import ").removeSuffix(suffix)
        } else {
            val prefix = "importScripts("
            val suffix = ", $adapterImport);\n"
            if (!source.startsWith(prefix) || !source.endsWith(suffix)) {
                throw IllegalStateException("generated classic bootstrap provenance가 일치하지 않습니다.")
            }
            source.removePrefix(prefix).removeSuffix(suffix)
        }
        val importPath = runCatching {
            (Json.parseToJsonElement(encodedOriginal) as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
        }.getOrNull()
            ?: throw IllegalStateException("generated bootstrap import를 해석할 수 없습니다.")
        if (!importPath.startsWith("../")) {
            throw IllegalStateException("generated bootstrap import 기준 경로가 올바르지 않습니다.")
        }
        val original = canonicalResourcePath(importPath.removePrefix("../"))
            ?: throw IllegalStateException("generated bootstrap 원본 경로가 안전하지 않습니다.")
        if (JsonPrimitive("../$original").toString() != encodedOriginal) {
            throw IllegalStateException("generated bootstrap 원본 경로가 canonical하지 않습니다.")
        }
        return original
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
        ?.let(::canonicalResourcePath) == CONTENT_RELATIVE_PATH

    private fun canonicalResourcePath(value: String): String? {
        if (value.isBlank() ||
            '\\' in value ||
            ':' in value ||
            '?' in value ||
            '#' in value ||
            value.startsWith("//")
        ) {
            return null
        }
        return runCatching {
            Path.of(value.removePrefix("/"))
                .normalize()
                .invariantSeparatorsPathString
                .takeIf { it.isNotBlank() && it != ".." && !it.startsWith("../") }
        }.getOrNull()
    }

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
        val bootstrapBytes: ByteArray?,
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
        const val BOOTSTRAP_FILE_NAME = "action-popup-entry-bootstrap.v1.js"
        const val CONTENT_RELATIVE_PATH = "_sixbridge/$CONTENT_FILE_NAME"
        const val BACKGROUND_RELATIVE_PATH = "_sixbridge/$BACKGROUND_FILE_NAME"
        const val BOOTSTRAP_RELATIVE_PATH = "_sixbridge/$BOOTSTRAP_FILE_NAME"
        const val CONTENT_RESOURCE_PATH = "dev/sixbridge/adapters/$CONTENT_FILE_NAME"
        const val BACKGROUND_RESOURCE_PATH = "dev/sixbridge/adapters/$BACKGROUND_FILE_NAME"
        const val POPUP_PLACEHOLDER = "__SIXBRIDGE_POPUP_PATH_JSON__"
    }
}
