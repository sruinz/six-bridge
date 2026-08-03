package dev.sixbridge.probe.generate

import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import dev.sixbridge.probe.catalog.ProbeCatalogCase
import dev.sixbridge.probe.catalog.ProbeCatalogEntry
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ProbeSourceGenerator(
    private val assetLoader: (String) -> ByteArray = ::loadBundledAsset,
    private val directoryMover: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, ATOMIC_MOVE)
        Unit
    },
) {
    fun generate(catalog: ApiProbeCatalog, outputRoot: Path): List<GeneratedProbe> {
        val output = outputRoot.toAbsolutePath().normalize()
        val parent = requireNotNull(output.parent) { "API probe output root에 parent가 필요합니다." }
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(parent)) {
            "API probe output root의 parent는 symlink일 수 없습니다."
        }
        require(!Files.exists(output, NOFOLLOW_LINKS)) {
            "API probe output root는 존재하지 않아야 합니다."
        }

        val staging = Files.createTempDirectory(parent, ".${output.fileName}.staging-")
        try {
            val groups = catalog.entries
                .filter { it.disposition == CatalogDisposition.PROBE }
                .groupBy { requireNotNull(it.groupId) }
            require(groups.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
                "API probe catalog에 정확한 6개 group이 필요합니다."
            }
            groups.toSortedMap().forEach { (groupId, entries) ->
                writeGroup(staging.resolve(groupId), groupId, entries, catalog.sha256)
            }

            directoryMover(staging, output)
            require(Files.isDirectory(output, NOFOLLOW_LINKS)) {
                "API probe atomic move가 target을 생성하지 않았습니다."
            }
            require(!Files.exists(staging, NOFOLLOW_LINKS)) {
                "API probe atomic move 후 staging directory가 남았습니다."
            }
        } catch (error: Exception) {
            deleteTree(staging)
            throw error
        }

        return ApiProbeCatalog.PROBE_GROUP_IDS.sorted().map { groupId ->
            val sourceRoot = output.resolve(groupId)
            GeneratedProbe(
                groupId = groupId,
                sourceRoot = sourceRoot,
                manifestSha256 = sha256(Files.readAllBytes(sourceRoot.resolve("manifest.json"))),
                catalogSha256 = catalog.sha256,
            )
        }
    }

    private fun writeGroup(
        root: Path,
        groupId: String,
        entries: List<ProbeCatalogEntry>,
        catalogSha256: String,
    ) {
        Files.createDirectory(root)
        Files.writeString(root.resolve("manifest.json"), manifest(groupId, entries), UTF_8)
        Files.writeString(
            root.resolve("probe-cases.json"),
            groupDescriptor(groupId, entries, catalogSha256),
            UTF_8,
        )
        COMMON_ASSETS.forEach { name ->
            val target = root.resolve(name)
            target.parent?.let(Files::createDirectories)
            val asset = assetLoader("probe/$name")
            Files.write(
                target,
                if (name in GROUP_TEMPLATED_ASSETS) {
                    asset.toString(UTF_8)
                        .replace(GROUP_PLACEHOLDER, groupId)
                        .encodeToByteArray()
                } else {
                    asset
                },
            )
        }
        val iconDirectory = root.resolve("icons")
        Files.createDirectory(iconDirectory)
        ICON_NAMES.forEach { name -> Files.write(iconDirectory.resolve(name), PROBE_ICON) }
    }

    private fun manifest(groupId: String, entries: List<ProbeCatalogEntry>): String {
        val contexts = entries.flatMap { entry -> entry.cases.map(ProbeCatalogCase::context) }.toSet()
        val permissions = (entries.flatMap { entry ->
            entry.cases.flatMap(ProbeCatalogCase::permissions)
        } + buildList {
            if (ApiExecutionContext.CONTENT_SCRIPT in contexts) add("scripting")
            if (ApiExecutionContext.OFFSCREEN_DOCUMENT in contexts) add("offscreen")
        }).distinct().sorted()
        val manifestKeys = entries.flatMap { entry ->
            entry.cases.flatMap(ProbeCatalogCase::manifestKeys)
        }.toSet()
        val needsFixtureHost = groupId == "tab-page" || ApiExecutionContext.CONTENT_SCRIPT in contexts
        val iconMap = jsonObject(
            "128" to JsonPrimitive("icons/probe-128.png"),
            "16" to JsonPrimitive("icons/probe-16.png"),
            "48" to JsonPrimitive("icons/probe-48.png"),
        )
        val fields = linkedMapOf<String, JsonElement>(
            "action" to jsonObject(
                "default_icon" to iconMap,
                "default_popup" to JsonPrimitive("probe.html"),
            ),
            "background" to jsonObject(
                "service_worker" to JsonPrimitive("service-worker.js"),
                "type" to JsonPrimitive("module"),
            ),
        )
        if ("commands" in manifestKeys) {
            fields["commands"] = jsonObject(
                "six-probe-open" to jsonObject(
                    "description" to JsonPrimitive("SIX API Probe 열기"),
                    "suggested_key" to jsonObject(
                        "default" to JsonPrimitive("Ctrl+Shift+U"),
                    ),
                ),
            )
        }
        fields["content_security_policy"] = jsonObject(
            "extension_pages" to JsonPrimitive(
                "script-src 'self'; object-src 'self'; connect-src http://127.0.0.1:47171",
            ),
        )
        fields["host_permissions"] = JsonArray(
            buildList {
                add(LOOPBACK_MATCH)
                if (needsFixtureHost) add(FIXTURE_MATCH)
            }.sorted().map(::JsonPrimitive),
        )
        if (ApiExecutionContext.CONTENT_SCRIPT in contexts) {
            fields["web_accessible_resources"] = JsonArray(
                listOf(
                    jsonObject(
                        "matches" to JsonArray(listOf(JsonPrimitive(FIXTURE_MATCH))),
                        "resources" to JsonArray(
                            listOf(JsonPrimitive("cases/*.js"), JsonPrimitive("probe-runtime.js")),
                        ),
                    ),
                ),
            )
        }
        fields += linkedMapOf(
            "icons" to iconMap,
            "manifest_version" to JsonPrimitive(3),
            "name" to JsonPrimitive("SIX API Probe · $groupId"),
            "permissions" to JsonArray(permissions.map(::JsonPrimitive)),
            "version" to JsonPrimitive("1.0.0"),
        )
        return canonicalJson(JsonObject(fields))
    }

    private fun groupDescriptor(
        groupId: String,
        entries: List<ProbeCatalogEntry>,
        catalogSha256: String,
    ): String = canonicalJson(
        jsonObject(
            "catalogSha256" to JsonPrimitive(catalogSha256),
            "cases" to JsonArray(
                entries.flatMap { entry ->
                    entry.cases.map { case -> descriptor(entry.namespace, case) }
                }.sortedBy { it.getValue("id").toString() },
            ),
            "groupId" to JsonPrimitive(groupId),
            "schemaVersion" to JsonPrimitive(1),
        ),
    )

    private fun descriptor(namespace: String, case: ProbeCatalogCase): JsonObject = jsonObject(
        "cleanup" to (
            case.cleanup?.let { cleanup ->
                jsonObject(
                    "fixtureId" to (cleanup.fixtureId?.let(::JsonPrimitive) ?: JsonNull),
                    "member" to JsonPrimitive(cleanup.member),
                    "target" to JsonPrimitive(cleanup.target.name.lowercase()),
                )
            } ?: JsonNull
        ),
        "context" to JsonPrimitive(case.context.name.lowercase()),
        "expectedOutcome" to JsonPrimitive(case.expectedOutcome.name.lowercase()),
        "id" to JsonPrimitive(case.id),
        "manifestKeys" to JsonArray(case.manifestKeys.sorted().map(::JsonPrimitive)),
        "member" to JsonPrimitive(case.member),
        "mode" to JsonPrimitive(case.mode.name.lowercase()),
        "namespace" to JsonPrimitive(namespace),
        "permissions" to JsonArray(case.permissions.sorted().map(::JsonPrimitive)),
    )

    private fun canonicalJson(value: JsonElement): String = Json.encodeToString(
        JsonElement.serializer(),
        canonicalize(value),
    ) + "\n"

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalize(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    private fun jsonObject(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(
        linkedMapOf(*fields),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val FIXTURE_MATCH = "https://example.com/*"
        const val LOOPBACK_MATCH = "http://127.0.0.1:47171/*"
        const val GROUP_PLACEHOLDER = "__SIXBRIDGE_PROBE_GROUP__"
        val GROUP_TEMPLATED_ASSETS = setOf("probe-ui.js", "content-probe.js")
        val COMMON_ASSETS = listOf(
            "probe.html",
            "probe.css",
            "probe-ui.js",
            "probe-submission.js",
            "probe-runtime.js",
            "service-worker.js",
            "content-probe.js",
            "offscreen-probe.html",
            "offscreen-probe.js",
            "cases/advanced-runtime.js",
            "cases/base-runtime.js",
            "cases/identity-privacy.js",
            "cases/network.js",
            "cases/tab-page.js",
            "cases/user-interface.js",
        )
        val ICON_NAMES = listOf("probe-16.png", "probe-48.png", "probe-128.png")
        val PROBE_ICON: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )

        fun loadBundledAsset(name: String): ByteArray = checkNotNull(
            ProbeSourceGenerator::class.java.classLoader.getResourceAsStream(name),
        ) { "API probe resource를 찾을 수 없습니다: $name" }.use { it.readBytes() }
    }
}
