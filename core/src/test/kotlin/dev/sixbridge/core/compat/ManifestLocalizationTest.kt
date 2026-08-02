package dev.sixbridge.core.compat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManifestLocalizationTest {
    private val root = Files.createTempDirectory("six-manifest-localization")
    private val outside = Files.createTempDirectory("six-manifest-localization-outside")
    private val manifest get() = Json.parseToJsonElement(
        root.resolve("manifest.json").toFile().readText(),
    ).jsonObject

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
        outside.toFile().deleteRecursively()
    }

    @Test
    fun `default_locale messages에서 MSG 이름을 해석한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")
        writeMessages("en", """{"extName":{"message":"Tampermonkey"}}""")

        assertEquals("Tampermonkey", ManifestLocalization.resolveName(root, manifest)?.value)
    }

    @Test
    fun `locale key가 없으면 token과 nonblocking issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")

        val result = ManifestLocalization.resolveName(root, manifest)

        assertEquals("__MSG_extName__", result?.value)
        assertEquals("manifest_name_locale_missing", result?.issue?.code)
        assertFalse(result?.issue?.blocking ?: true)
    }

    @Test
    fun `허용하지 않는 locale 경로는 token과 issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "../en")

        val result = ManifestLocalization.resolveName(root, manifest)

        assertEquals("__MSG_extName__", result?.value)
        assertEquals("manifest_name_locale_missing", result?.issue?.code)
        assertFalse(result?.issue?.blocking ?: true)
    }

    @Test
    fun `중복 key가 있는 messages는 해석하지 않고 issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")
        writeMessages("en", """{"extName":{"message":"Tampermonkey"},"extName":{"message":"Other"}}""")

        val result = ManifestLocalization.resolveName(root, manifest)

        assertEquals("__MSG_extName__", result?.value)
        assertEquals("manifest_name_locale_missing", result?.issue?.code)
    }

    @Test
    fun `_locales symlink는 token과 issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")
        outside.resolve("en").createDirectories().resolve("messages.json")
            .writeText("""{"extName":{"message":"Outside"}}""")
        Files.createSymbolicLink(root.resolve("_locales"), outside)

        assertMissingName()
    }

    @Test
    fun `locale directory symlink는 token과 issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")
        root.resolve("_locales").createDirectories()
        outside.resolve("messages.json").writeText("""{"extName":{"message":"Outside"}}""")
        Files.createSymbolicLink(root.resolve("_locales/en"), outside)

        assertMissingName()
    }

    @Test
    fun `messages symlink는 token과 issue를 보존한다`() {
        writeManifest("__MSG_extName__", defaultLocale = "en")
        root.resolve("_locales/en").createDirectories()
        val outsideMessages = outside.resolve("messages.json")
        outsideMessages.writeText("""{"extName":{"message":"Outside"}}""")
        Files.createSymbolicLink(root.resolve("_locales/en/messages.json"), outsideMessages)

        assertMissingName()
    }

    private fun writeManifest(name: String, defaultLocale: String) {
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"$name","version":"1","default_locale":"$defaultLocale"}""",
        )
    }

    private fun writeMessages(locale: String, contents: String) {
        root.resolve("_locales").resolve(locale).createDirectories()
            .resolve("messages.json")
            .writeText(contents)
    }

    private fun assertMissingName() {
        val result = ManifestLocalization.resolveName(root, manifest)
        assertEquals("__MSG_extName__", result?.value)
        assertEquals("manifest_name_locale_missing", result?.issue?.code)
    }
}
