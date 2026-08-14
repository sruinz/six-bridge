package dev.sixbridge.core.conversion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.jvm.JvmSynthetic

internal class ActionPopupEntryAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.ACTION_POPUP_ENTRY
    override val version: Int = 2

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        val decision = selected.toDecision()
        if (
            decision.id != id ||
            decision.version != version ||
            decision.disposition != AdapterDisposition.REQUIRES_APPROVAL
        ) {
            return decision
        }

        val root = context.stagedRoot
        val manifestPath = root.resolve(MANIFEST)
        val manifest = runCatching {
            Json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull() ?: return decision
        val popup = (manifest[ACTION] as? JsonObject)
            ?.stringOrNull(DEFAULT_POPUP)
            ?.takeIf(String::isNotBlank)
            ?: return decision
        val canonicalPopup = ActionPopupEntrySafety.canonicalResourcePath(popup)
            ?: throw IllegalStateException("popup 경로가 안전하지 않습니다: $popup")
        verifyResource(root, canonicalPopup, "popup")
        if (isExactlyApplied(root, manifest, canonicalPopup)) {
            return decision.copy(applied = true)
        }
        if (OPTIONS_UI in manifest || OPTIONS_PAGE in manifest) {
            throw IllegalStateException("기존 option page를 덮어쓸 수 없습니다.")
        }

        val marker = Asset(
            PROVENANCE_RELATIVE_PATH,
            provenanceBytes(canonicalPopup),
        )
        preflightAsset(root, marker)
        val options = JsonObject(
            linkedMapOf(
                PAGE to JsonPrimitive(canonicalPopup),
                OPEN_IN_TAB to JsonPrimitive(true),
            ),
        )
        installAsset(root, marker)
        writeManifestUtf8Strict(
            manifestPath,
            JsonObject(manifest + (OPTIONS_UI to options)),
        )
        return decision.copy(applied = true)
    }

    @JvmSynthetic
    internal fun isExactlyApplied(
        workingRoot: Path,
        manifest: JsonObject,
        popupPath: String,
    ): Boolean = runCatching {
        val canonicalPopup = ActionPopupEntrySafety.canonicalResourcePath(popupPath)
            ?: return false
        val options = manifest[OPTIONS_UI] as? JsonObject ?: return false
        val openInTab = options[OPEN_IN_TAB] as? JsonPrimitive
        if (
            OPTIONS_PAGE in manifest ||
            options.keys != setOf(PAGE, OPEN_IN_TAB) ||
            options.stringOrNull(PAGE) != canonicalPopup ||
            openInTab == null ||
            openInTab.isString ||
            openInTab.booleanOrNull != true
        ) {
            return false
        }
        hasExactBytes(
            workingRoot.resolve(PROVENANCE_RELATIVE_PATH),
            provenanceBytes(canonicalPopup),
        )
    }.getOrDefault(false)

    private fun hasExactBytes(path: Path, expected: ByteArray): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.readAllBytes(path).contentEquals(expected)

    private fun provenanceBytes(popup: String): ByteArray =
        JsonObject(
            linkedMapOf(
                "adapter" to JsonPrimitive(id),
                "version" to JsonPrimitive(version),
                "popup" to JsonPrimitive(popup),
            ),
        ).toString().toByteArray(UTF_8)

    private fun verifyResource(
        workingRoot: Path,
        relativePath: String,
        label: String,
    ): Path {
        val root = workingRoot.toAbsolutePath().normalize()
        val resource = root.resolve(relativePath).normalize()
        if (
            !resource.startsWith(root) ||
            !Files.isRegularFile(resource, NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("$label 리소스를 확인할 수 없습니다: $relativePath")
        }
        return resource
    }

    private fun preflightAsset(workingRoot: Path, asset: Asset) {
        var parent = workingRoot
        asset.relativePath.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (
                Files.exists(parent, NOFOLLOW_LINKS) &&
                !Files.isDirectory(parent, NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException(
                    "adapter asset parent가 디렉터리가 아닙니다: $parent",
                )
            }
        }
        val destination = workingRoot.resolve(asset.relativePath)
        if (!Files.exists(destination, NOFOLLOW_LINKS)) return
        if (
            !Files.isRegularFile(destination, NOFOLLOW_LINKS) ||
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

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content

    private data class Asset(
        val relativePath: String,
        val bytes: ByteArray,
    )

    private companion object {
        const val MANIFEST = "manifest.json"
        const val ACTION = "action"
        const val DEFAULT_POPUP = "default_popup"
        const val OPTIONS_UI = "options_ui"
        const val OPTIONS_PAGE = "options_page"
        const val PAGE = "page"
        const val OPEN_IN_TAB = "open_in_tab"
        const val PROVENANCE_RELATIVE_PATH =
            "sixbridge/action-popup-entry.v2.json"
    }
}

internal object ActionPopupEntrySafety {
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
