package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupNativeAutofillGuardAdapterTest {
    @Test
    fun `승인된 popup에만 외부 guard를 head 시작부에 연결한다`() {
        val input = fixtureInput()
        val original = extract(input.canonicalOriginalZip)
        val optionsBefore = original.resolve("options.html").readBytes()
        val popupScriptBefore = original.resolve("popup.js").readBytes()

        val prepared = prepare(input, setOf(ADAPTER_ID))
        val transformed = extract(prepared.transformedSourceZip)
        val popup = transformed.resolve("popup.html").readText()

        assertTrue(popup.startsWith("<!doctype html>\n<html>\n<head>$SCRIPT_TAG"))
        assertEquals(1, popup.windowed(SCRIPT_TAG.length).count { it == SCRIPT_TAG })
        assertFalse(SCRIPT_TAG in transformed.resolve("options.html").readText())
        assertContentEquals(optionsBefore, transformed.resolve("options.html").readBytes())
        assertContentEquals(popupScriptBefore, transformed.resolve("popup.js").readBytes())
        assertEquals(setOf(ADAPTER_ID), prepared.appliedAdapterIds)
        assertContentEquals(
            adapterAssetBytes(),
            transformed.resolve(ASSET_PATH).readBytes(),
        )
    }

    @Test
    fun `마커는 popup span과 asset hash만 보존한다`() {
        val secret = "private-field-value"
        val input = fixtureInput { root ->
            root.resolve("popup.html").writeText(
                root.resolve("popup.html").readText().replace(
                    "<input type=\"email\">",
                    "<input id=\"account\" name=\"email\" value=\"$secret\" type=\"email\">",
                ),
            )
        }

        val prepared = prepare(input, setOf(ADAPTER_ID))
        val transformed = extract(prepared.transformedSourceZip)
        val markerText = transformed.resolve(MARKER_PATH).readText()
        val marker = Json.parseToJsonElement(markerText) as JsonObject
        val applied = prepared.compatibilityAfter.adapterDecisions.single {
            it.id == ADAPTER_ID
        }

        assertEquals(
            setOf("adapter", "version", "popupPath", "scriptTagSha256", "assetSha256"),
            marker.keys,
        )
        assertEquals(ADAPTER_ID, (marker["adapter"] as JsonPrimitive).content)
        assertEquals("1", (marker["version"] as JsonPrimitive).content)
        assertEquals("popup.html", (marker["popupPath"] as JsonPrimitive).content)
        assertEquals(sha256(SCRIPT_TAG.toByteArray(UTF_8)), marker.string("scriptTagSha256"))
        assertEquals(sha256(adapterAssetBytes()), marker.string("assetSha256"))
        assertFalse(secret in markerText)
        assertFalse("account" in markerText)
        assertFalse("name" in markerText)
        assertEquals(
            listOf(
                "path=popup.html; span-before-sha256=${sha256(ByteArray(0))}; " +
                    "span-after-sha256=${sha256(SCRIPT_TAG.toByteArray(UTF_8))}",
            ),
            applied.changes,
        )
    }

    @Test
    fun `문자열 marker version은 applied로 재구성하지 않는다`() {
        val prepared = prepare(fixtureInput(), setOf(ADAPTER_ID))
        val transformed = extract(prepared.transformedSourceZip)
        val canonical = MobilePatternAnalyzer().analyze(transformed, profile)
            .adapterDecisions.single { it.id == ADAPTER_ID }
        val marker = transformed.resolve(MARKER_PATH)
        val markerText = marker.readText()

        assertTrue(canonical.applied)
        assertTrue("\"version\":1" in markerText)
        marker.writeText(markerText.replace("\"version\":1", "\"version\":\"1\""))

        val tampered = MobilePatternAnalyzer().analyze(transformed, profile)
            .adapterDecisions.single { it.id == ADAPTER_ID }
        assertFalse(tampered.applied)
    }

    @Test
    fun `원본 유지 선재은 source bytes를 변경하지 않는다`() {
        val input = fixtureInput()

        val prepared = prepare(input, emptySet())

        assertContentEquals(input.canonicalOriginalZip, prepared.transformedSourceZip)
        assertEquals(CompatibilityStatus.PARTIAL, prepared.compatibilityAfter.status)
        assertEquals(emptySet(), prepared.appliedAdapterIds)
    }

    @Test
    fun `기존 SIX Bridge asset의 다른 bytes나 손상 marker는 변환 전체를 rollback한다`() {
        listOf<(Path) -> Unit>(
            { root ->
                Files.createDirectories(root.resolve("sixbridge"))
                root.resolve(ASSET_PATH).writeText("foreign")
            },
            { root ->
                Files.createDirectories(root.resolve("sixbridge"))
                root.resolve(MARKER_PATH).writeText("{}")
            },
        ).forEachIndexed { index, collide ->
            val source = fixtureRoot(collide)
            ConversionWorkspace().use { workspace ->
                val snapshot = workspace.snapshot(source)
                val before = ExtensionZip.createBytes(
                    snapshot.workingRoot,
                    includeProjectMarker = true,
                )
                val report = MobilePatternAnalyzer().analyze(snapshot.originalRoot, profile)
                val plan = ConversionPlanner().plan(report, setOf(ADAPTER_ID))

                assertFailsWith<ConversionExecutionException>("collision $index") {
                    workspace.transform(snapshot, profile, plan)
                }
                assertContentEquals(
                    before,
                    ExtensionZip.createBytes(
                        snapshot.workingRoot,
                        includeProjectMarker = true,
                    ),
                    "collision $index",
                )
            }
        }
    }

    @Test
    fun `두 번 재 적용해도 byte identical하고 script tag는 하나다`() {
        val first = prepare(fixtureInput(), setOf(ADAPTER_ID))
        val reappliedInput = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            first.transformedSourceZip,
        )

        val second = prepare(reappliedInput, setOf(ADAPTER_ID))
        val popup = extract(second.transformedSourceZip).resolve("popup.html").readText()

        assertContentEquals(first.transformedSourceZip, second.transformedSourceZip)
        assertEquals(1, popup.windowed(SCRIPT_TAG.length).count { it == SCRIPT_TAG })
        assertEquals(setOf(ADAPTER_ID), second.appliedAdapterIds)
    }

    private fun prepare(
        input: PortableExtensionInput,
        approvals: Set<String>,
    ): PreparedConversion = ConversionPreparationEngine().use {
        it.prepare(input, profile, approvals)
    }

    private fun fixtureInput(
        mutate: (Path) -> Unit = {},
    ): PortableExtensionInput = PortableExtensionInput.fromZip(
        ExtensionInputKind.ZIP,
        ExtensionZip.createBytes(fixtureRoot(mutate), includeProjectMarker = true),
    )

    private fun fixtureRoot(mutate: (Path) -> Unit = {}): Path {
        val fixture = Path.of("../fixtures/popup-native-autofill-guard")
        val root = Files.createTempDirectory("six-popup-native-guard-")
        listOf("manifest.json", "popup.html", "popup.js", "options.html").forEach {
            Files.copy(fixture.resolve(it), root.resolve(it))
        }
        mutate(root)
        return root
    }

    private fun extract(zip: ByteArray): Path {
        val parent = Files.createTempDirectory("six-popup-native-guard-extract-")
        return ExtensionZip.extract(zip, parent.resolve("tree"))
    }

    private fun adapterAssetBytes(): ByteArray = requireNotNull(
        javaClass.classLoader.getResourceAsStream(ASSET_RESOURCE),
    ).use { it.readBytes() }

    private fun JsonObject.string(name: String): String =
        (get(name) as JsonPrimitive).content

    private fun sha256(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ADAPTER_ID = "popup-native-autofill-guard"
        const val SCRIPT_TAG =
            "<script src=\"/sixbridge/popup-native-autofill-guard.v1.js\"></script>"
        const val ASSET_PATH = "sixbridge/popup-native-autofill-guard.v1.js"
        const val MARKER_PATH = "sixbridge/popup-native-autofill-guard.v1.json"
        const val ASSET_RESOURCE =
            "dev/sixbridge/adapters/popup-native-autofill-guard.v1.js"
    }

    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = BrowserProfile.BASELINE_VERSION,
        versionCode = 1_300_261_502,
        majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )
}
