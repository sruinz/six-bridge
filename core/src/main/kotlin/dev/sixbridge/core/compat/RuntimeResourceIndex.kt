package dev.sixbridge.core.compat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

class RuntimeResourceIndex private constructor(
    val resources: Set<Path>,
    val contentScriptResources: Set<Path>,
    val contentScriptEntryIndexesByRelativePath: Map<String, Set<Int>>,
    val scripts: List<RuntimeScript>,
    val unresolvedDependency: Boolean,
    val rolesByRelativePath: Map<String, Set<ResourceRole>>,
    val dependencyEdges: Set<RuntimeDependencyEdge>,
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

    data class RuntimeDependencyEdge(
        val fromRelativePath: String,
        val toRelativePath: String,
    )

    companion object {
        fun create(root: Path, manifest: JsonObject): RuntimeResourceIndex =
            create(root, manifest, AnalysisControl())

        fun create(
            root: Path,
            manifest: JsonObject,
            control: AnalysisControl,
        ): RuntimeResourceIndex = create(
            root,
            manifest,
            control,
            Files::readString,
            AnalysisBudgetLedger(control.policy),
        )

        internal fun createForTesting(
            root: Path,
            manifest: JsonObject,
            readSource: (Path) -> String,
            control: AnalysisControl = AnalysisControl(),
        ): RuntimeResourceIndex = create(
            root,
            manifest,
            control,
            readSource,
            AnalysisBudgetLedger(control.policy),
        )

        internal fun create(
            root: Path,
            manifest: JsonObject,
            control: AnalysisControl,
            ledger: AnalysisBudgetLedger,
        ): RuntimeResourceIndex = create(
            root,
            manifest,
            control,
            Files::readString,
            ledger,
        )

        private fun create(
            root: Path,
            manifest: JsonObject,
            control: AnalysisControl,
            readSource: (Path) -> String,
            ledger: AnalysisBudgetLedger,
        ): RuntimeResourceIndex {
            control.stage(AnalysisStage.RUNTIME_CLOSURE)
            val normalizedRoot = root.toAbsolutePath().normalize()
            val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull()
                ?: return RuntimeResourceIndex(
                    resources = emptySet(),
                    contentScriptResources = emptySet(),
                    contentScriptEntryIndexesByRelativePath = emptyMap(),
                    scripts = emptyList(),
                    unresolvedDependency = true,
                    rolesByRelativePath = emptyMap(),
                    dependencyEdges = emptySet(),
                )
            val rolesByResource = linkedMapOf<Path, MutableSet<ResourceRole>>()
            val contentScriptEntriesByResource = linkedMapOf<Path, MutableSet<Int>>()
            val pending = mutableListOf<Path>()
            val dependencyEdges = linkedSetOf<RuntimeDependencyEdge>()
            val sourceByResource = linkedMapOf<Path, String>()
            var pendingIndex = 0
            var unresolvedDependency = false

            fun enqueue(
                base: Path,
                resource: String,
                roles: Set<ResourceRole>,
                contentScriptEntryIndexes: Set<Int> = emptySet(),
            ): Path? {
                val path = resolveLocalRuntimeResource(
                    normalizedRoot,
                    realRoot,
                    base,
                    resource,
                ) ?: run {
                    unresolvedDependency = true
                    return null
                }
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
                return path
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
                control.checkCancelled()
                val resource = pending[pendingIndex++]
                val roles = rolesByResource.getValue(resource).toSet()
                val executableText = resource.extension.lowercase() in TEXT_EXTENSIONS
                ledger.reserveRuntimeDependency(
                    resource,
                    Files.size(resource),
                    executableText,
                )
                if (!executableText) {
                    unresolvedDependency = true
                    sourceByResource[resource] = ""
                    control.checkCancelled()
                    continue
                }
                control.checkCancelled()
                val source = try {
                    readSource(resource)
                } catch (error: java.util.concurrent.CancellationException) {
                    throw error
                } catch (error: AnalysisBudgetExceededException) {
                    throw error
                } catch (_: Exception) {
                        unresolvedDependency = true
                        ""
                }
                sourceByResource[resource] = source
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
                                    RuntimeSourceScanner.javaScriptDependencies(
                                        body,
                                        control::checkCancelled,
                                    )
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
                        RuntimeSourceScanner.javaScriptDependencies(
                            source,
                            control::checkCancelled,
                        )
                    else -> RuntimeSourceScanner.JavaScriptDependencyScan(
                        emptySet(),
                        false,
                    )
                }
                control.checkCancelled()
                if (dependencyScan.unresolvedDependency) unresolvedDependency = true
                dependencyScan.dependencies.forEach {
                    val dependency = enqueue(
                        resource.parent,
                        it,
                        roles,
                        contentScriptEntriesByResource[resource].orEmpty().toSet(),
                    )
                    if (dependency != null) {
                        dependencyEdges += RuntimeDependencyEdge(
                            fromRelativePath = relativePath(normalizedRoot, resource),
                            toRelativePath = relativePath(normalizedRoot, dependency),
                        )
                    }
                }
                control.checkCancelled()
            }

            val scripts = rolesByResource.entries
                .sortedBy { (path, _) -> relativePath(normalizedRoot, path) }
                .flatMap { (path, roles) ->
                    val source = sourceByResource[path].orEmpty()
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
                rolesByRelativePath = rolesByResource.entries.associate { (path, roles) ->
                    relativePath(normalizedRoot, path) to roles.toSortedSet()
                },
                dependencyEdges = dependencyEdges.toSortedSet(
                    compareBy(
                        RuntimeDependencyEdge::fromRelativePath,
                        RuntimeDependencyEdge::toRelativePath,
                    ),
                ),
            )
        }

        private val TEXT_EXTENSIONS = setOf("js", "mjs", "cjs", "html")

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
