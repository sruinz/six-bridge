package dev.sixbridge.core.conversion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class PopupNativeAutofillGuardAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD
    override val version: Int = 1

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        val decision = selected.toDecision()
        require(
            selected.id == id &&
                selected.version == version &&
                selected.disposition == AdapterDisposition.OPTIONAL_APPROVAL,
        ) {
            "popup native autofill guard 선재가 지원하지 않습니다."
        }
        val root = context.stagedRoot
        val popupPath = PopupAutofillProof.parse(selected.evidence).popupPath
        val popup = verifiedFile(root, popupPath)
        val current = Files.newBufferedReader(popup, UTF_8).use { it.readText() }
        val assetBytes = PopupNativeAutofillGuardContract.assetBytes()
        val proof = PopupAutofillProof.fromApplied(
            popupPath,
            PopupNativeAutofillGuardContract.SCRIPT_TAG,
            assetBytes,
        )
        val markerBytes = PopupNativeAutofillGuardContract.markerBytes(proof)
        val hasAsset = Files.exists(
            root.resolve(PopupNativeAutofillGuardContract.ASSET_PATH),
            NOFOLLOW_LINKS,
        )
        val hasMarker = Files.exists(
            root.resolve(PopupNativeAutofillGuardContract.MARKER_PATH),
            NOFOLLOW_LINKS,
        )
        val hasTag = PopupNativeAutofillGuardContract.SCRIPT_TAG in current
        if (hasAsset || hasMarker || hasTag) {
            check(
                hasAsset &&
                    hasMarker &&
                    hasTag &&
                    exactBytes(
                        root.resolve(PopupNativeAutofillGuardContract.ASSET_PATH),
                        assetBytes,
                    ) &&
                    exactBytes(
                        root.resolve(PopupNativeAutofillGuardContract.MARKER_PATH),
                        markerBytes,
                    ) &&
                    hasSingleTagAtHead(current),
            ) {
                "popup native autofill guard 소유 상태가 일관되지 않습니다."
            }
            return applied(decision, popupPath, proof)
        }

        val transformed = SafeHtmlDocument.insertExternalScript(
            current,
            PopupNativeAutofillGuardContract.ASSET_URL,
        )
        preflightOwnedAsset(
            root,
            PopupNativeAutofillGuardContract.ASSET_PATH,
            assetBytes,
        )
        preflightOwnedAsset(
            root,
            PopupNativeAutofillGuardContract.MARKER_PATH,
            markerBytes,
        )
        installOwnedAsset(
            root,
            PopupNativeAutofillGuardContract.ASSET_PATH,
            assetBytes,
        )
        Files.write(popup, transformed.toByteArray(UTF_8))
        writeMarker(root, proof)
        return applied(decision, popupPath, proof)
    }

    private fun applied(
        decision: AdapterDecision,
        popupPath: String,
        proof: PopupAutofillProof,
    ): AdapterDecision = decision.copy(
        applied = true,
        evidence = decision.evidence.filterNot {
            it.detail.startsWith("popup_guard_sha256=")
        } + proof.evidence(),
        changes = listOf(
            spanHashChange(
                popupPath,
                "",
                PopupNativeAutofillGuardContract.SCRIPT_TAG,
            ),
        ),
    )

    private fun preflightOwnedAsset(
        root: Path,
        relativePath: String,
        expected: ByteArray,
    ) {
        var parent = root
        relativePath.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (Files.exists(parent, NOFOLLOW_LINKS) &&
                !Files.isDirectory(parent, NOFOLLOW_LINKS)
            ) {
                throw IllegalStateException("SIX Bridge asset parent가 디렉터리가 아닙니다: $relativePath")
            }
        }
        val target = root.resolve(relativePath)
        if (Files.exists(target, NOFOLLOW_LINKS) && !exactBytes(target, expected)) {
            throw IllegalStateException("SIX Bridge asset bytes가 다릅니다: $relativePath")
        }
    }

    private fun writeMarker(
        root: Path,
        proof: PopupAutofillProof,
    ) {
        installOwnedAsset(
            root,
            PopupNativeAutofillGuardContract.MARKER_PATH,
            PopupNativeAutofillGuardContract.markerBytes(proof),
        )
    }

    private fun installOwnedAsset(
        root: Path,
        relativePath: String,
        bytes: ByteArray,
    ) {
        val target = root.resolve(relativePath)
        if (Files.exists(target, NOFOLLOW_LINKS)) return
        Files.createDirectories(requireNotNull(target.parent))
        Files.write(target, bytes)
    }

    private fun exactBytes(path: Path, expected: ByteArray): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) &&
            Files.readAllBytes(path).contentEquals(expected)

    private fun hasSingleTagAtHead(source: String): Boolean {
        val document = SafeHtmlDocument.parse(source) {}
        val offset = document.headContentOffset ?: return false
        val tag = PopupNativeAutofillGuardContract.SCRIPT_TAG
        return source.startsWith(tag, offset) && source.indexOf(tag) == source.lastIndexOf(tag)
    }

    private fun verifiedFile(root: Path, relativePath: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(relativePath).normalize()
        check(
            target.startsWith(normalizedRoot) &&
                Files.isRegularFile(target, NOFOLLOW_LINKS),
        ) {
            "popup resource를 확인할 수 없습니다: $relativePath"
        }
        return target
    }
}

internal object PopupNativeAutofillGuardContract {
    const val ASSET_URL = "/sixbridge/popup-native-autofill-guard.v1.js"
    const val ASSET_PATH = "sixbridge/popup-native-autofill-guard.v1.js"
    const val MARKER_PATH = "sixbridge/popup-native-autofill-guard.v1.json"
    const val SCRIPT_TAG = "<script src=\"$ASSET_URL\"></script>"
    private const val ASSET_RESOURCE =
        "dev/sixbridge/adapters/popup-native-autofill-guard.v1.js"

    fun assetBytes(): ByteArray = requireNotNull(
        PopupNativeAutofillGuardAdapter::class.java.classLoader
            .getResourceAsStream(ASSET_RESOURCE),
    ) {
        "popup native autofill guard asset을 찾을 수 없습니다."
    }.use { it.readBytes() }

    fun markerBytes(proof: PopupAutofillProof): ByteArray = JsonObject(
        linkedMapOf(
            "adapter" to JsonPrimitive(
                MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
            ),
            "version" to JsonPrimitive(1),
            "popupPath" to JsonPrimitive(proof.popupPath),
            "scriptTagSha256" to JsonPrimitive(proof.scriptTagSha256),
            "assetSha256" to JsonPrimitive(proof.assetSha256),
        ),
    ).toString().toByteArray(UTF_8)
}
