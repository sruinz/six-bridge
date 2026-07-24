package dev.sixbridge.core.compat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class RuntimeResourceIndex private constructor(
    val resources: Set<Path>,
    val contentScriptResources: Set<Path>,
    val contentScriptEntryIndexesByRelativePath: Map<String, Set<Int>>,
    val scripts: List<RuntimeScript>,
    val unresolvedDependency: Boolean,
) {
    data class RuntimeScript(
        val relativePath: String,
        val source: String,
        val lineOffset: Int,
        val roles: Set<ResourceRole>,
    )

    enum class ResourceRole {
        BACKGROUND,
        CONTENT_SCRIPT,
        ACTION_POPUP,
        OPTIONS_PAGE,
    }

    companion object {
        fun create(root: Path, manifest: JsonObject): RuntimeResourceIndex {
            val normalizedRoot = root.toAbsolutePath().normalize()
            val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull()
                ?: return RuntimeResourceIndex(
                    resources = emptySet(),
                    contentScriptResources = emptySet(),
                    contentScriptEntryIndexesByRelativePath = emptyMap(),
                    scripts = emptyList(),
                    unresolvedDependency = true,
                )
            val rolesByResource = linkedMapOf<Path, MutableSet<ResourceRole>>()
            val contentScriptEntriesByResource = linkedMapOf<Path, MutableSet<Int>>()
            val pending = mutableListOf<Path>()
            var pendingIndex = 0
            var unresolvedDependency = false

            fun enqueue(
                base: Path,
                resource: String,
                roles: Set<ResourceRole>,
                contentScriptEntryIndexes: Set<Int> = emptySet(),
            ) {
                val path = resolveLocalRuntimeResource(
                    normalizedRoot,
                    realRoot,
                    base,
                    resource,
                ) ?: return
                val existingRoles = rolesByResource.getOrPut(path) {
                    pending.add(path)
                    mutableSetOf()
                }
                val rolesChanged = existingRoles.addAll(roles)
                val entriesChanged = contentScriptEntriesByResource
                    .getOrPut(path, ::linkedSetOf)
                    .addAll(contentScriptEntryIndexes)
                if ((rolesChanged || entriesChanged) && path !in pending.drop(pendingIndex)) {
                    pending.add(path)
                }
            }

            (manifest["background"] as? JsonObject)
                ?.stringOrNull("service_worker")
                ?.let {
                    enqueue(
                        normalizedRoot,
                        it,
                        setOf(ResourceRole.BACKGROUND),
                    )
            }
            (manifest["content_scripts"] as? JsonArray)?.forEachIndexed { index, item ->
                val script = item as? JsonObject ?: return@forEachIndexed
                readStringSet(script["js"]).forEach {
                    enqueue(
                        normalizedRoot,
                        it,
                        setOf(ResourceRole.CONTENT_SCRIPT),
                        setOf(index),
                    )
                }
            }
            (manifest["action"] as? JsonObject)
                ?.stringOrNull("default_popup")
                ?.let {
                    enqueue(
                        normalizedRoot,
                        it,
                        setOf(ResourceRole.ACTION_POPUP),
                    )
                }
            manifest.stringOrNull("options_page")?.let {
                enqueue(
                    normalizedRoot,
                    it,
                    setOf(ResourceRole.OPTIONS_PAGE),
                )
            }
            (manifest["options_ui"] as? JsonObject)
                ?.stringOrNull("page")
                ?.let {
                    enqueue(
                        normalizedRoot,
                        it,
                        setOf(ResourceRole.OPTIONS_PAGE),
                    )
                }

            while (pendingIndex < pending.size) {
                val resource = pending[pendingIndex++]
                val roles = rolesByResource.getValue(resource).toSet()
                val source = runCatching { resource.readText() }.getOrDefault("")
                val dependencyScan = when (resource.extension.lowercase()) {
                    "html" -> {
                        val htmlScan = RuntimeSourceScanner.scanHtmlScripts(source)
                        val dependencies = mutableSetOf<String>()
                        htmlScan.records.filter { it.executable }.forEach { script ->
                            if (script.source != null) {
                                dependencies += script.source
                            } else if (!script.hasSource &&
                                script.normalizedType == "module"
                            ) {
                                val body = source.substring(script.bodyRange)
                                val inlineDependencies =
                                    RuntimeSourceScanner.javaScriptDependencies(body)
                                dependencies += inlineDependencies.dependencies
                                if (inlineDependencies.unresolvedDependency) {
                                    unresolvedDependency = true
                                }
                            }
                        }
                        if (htmlScan.unresolvedDependency) unresolvedDependency = true
                        RuntimeSourceScanner.JavaScriptDependencyScan(
                            dependencies,
                            false,
                        )
                    }
                    "js", "mjs", "cjs" ->
                        RuntimeSourceScanner.javaScriptDependencies(source)
                    else -> RuntimeSourceScanner.JavaScriptDependencyScan(
                        emptySet(),
                        false,
                    )
                }
                if (dependencyScan.unresolvedDependency) unresolvedDependency = true
                dependencyScan.dependencies.forEach {
                    enqueue(
                        resource.parent,
                        it,
                        roles,
                        contentScriptEntriesByResource[resource].orEmpty().toSet(),
                    )
                }
            }

            val scripts = rolesByResource.entries
                .sortedBy { (path, _) -> relativePath(normalizedRoot, path) }
                .flatMap { (path, roles) ->
                    val source = runCatching { path.readText() }.getOrDefault("")
                    when (path.extension.lowercase()) {
                        "js", "mjs", "cjs" -> listOf(
                            RuntimeScript(
                                relativePath = relativePath(normalizedRoot, path),
                                source = source,
                                lineOffset = 0,
                                roles = roles.toSet(),
                            ),
                        )
                        "html" -> RuntimeSourceScanner.scanHtmlScripts(source).records
                            .filter { it.executable && !it.hasSource }
                            .map { record ->
                                RuntimeScript(
                                    relativePath = relativePath(normalizedRoot, path),
                                    source = source.substring(record.bodyRange),
                                    lineOffset = source.countNewlinesBefore(
                                        record.bodyRange.first,
                                    ),
                                    roles = roles.toSet(),
                                )
                            }
                        else -> emptyList()
                    }
                }

            val resources = rolesByResource.keys.toSet()
            return RuntimeResourceIndex(
                resources = resources,
                contentScriptResources = rolesByResource
                    .filterValues { ResourceRole.CONTENT_SCRIPT in it }
                    .keys,
                contentScriptEntryIndexesByRelativePath = contentScriptEntriesByResource
                    .filterValues { it.isNotEmpty() }
                    .entries
                    .associate { (path, entries) ->
                        relativePath(normalizedRoot, path) to entries.toSortedSet()
                    },
                scripts = scripts,
                unresolvedDependency = unresolvedDependency,
            )
        }

        private fun resolveLocalRuntimeResource(
            root: Path,
            realRoot: Path,
            base: Path,
            resource: String,
        ): Path? {
            if (resource.isBlank() ||
                '\\' in resource ||
                '%' in resource ||
                ':' in resource ||
                '?' in resource ||
                '#' in resource ||
                resource.startsWith("//")
            ) {
                return null
            }
            val target = runCatching {
                if (resource.startsWith("/")) {
                    root.resolve(resource.removePrefix("/"))
                } else {
                    base.resolve(resource)
                }.toAbsolutePath().normalize()
            }.getOrNull() ?: return null
            if (!target.startsWith(root) || !target.isRegularFile()) return null
            val insideRealRoot = runCatching {
                target.toRealPath().startsWith(realRoot)
            }.getOrDefault(false)
            return target.takeIf { insideRealRoot }
        }

        private fun relativePath(root: Path, path: Path): String =
            root.relativize(path).joinToString("/") { it.toString() }

        private fun String.countNewlinesBefore(end: Int): Int =
            substring(0, end.coerceIn(0, length)).count { it == '\n' }

        private fun JsonObject.stringOrNull(name: String): String? =
            (get(name) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content

        private fun readStringSet(
            element: kotlinx.serialization.json.JsonElement?,
        ): Set<String> = (element as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.toSet()
            .orEmpty()
    }
}
