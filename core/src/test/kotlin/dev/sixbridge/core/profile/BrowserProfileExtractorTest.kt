package dev.sixbridge.core.profile

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserProfileExtractorTest {
    @Test
    fun `records option page accessibility only with the complete Samsung contract`() {
        val complete = browserApk(
            dex = """
                six://ex extension-id .contentprovider dev_ext
                getExtensionOptionPage six_add_on_settings updateGoAddonSettings
            """.trimIndent(),
            terrace = """{"namespace":"action"} options_ui options_page""",
        )
        val partial = browserApk(
            dex = "getExtensionOptionPage six_add_on_settings",
            terrace = "options_ui",
        )
        val absent = browserApk(
            dex = "six://ex extension-id .contentprovider dev_ext",
            terrace = """{"namespace":"action"}""",
        )
        val swapped = browserApk(
            dex = "options_ui options_page",
            terrace = "getExtensionOptionPage six_add_on_settings updateGoAddonSettings",
        )

        assertTrue(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(complete).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(partial).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(absent).supportedFeatures,
        )
        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(swapped).supportedFeatures,
        )
    }

    @Test
    fun `requires one ABI runtime library to contain every option page marker`() {
        val dex = "getExtensionOptionPage six_add_on_settings updateGoAddonSettings"
        val splitAcrossAbis = browserApk(
            dex = dex,
            terraceByAbi = mapOf(
                "arm64-v8a" to "options_ui",
                "armeabi-v7a" to "options_page",
            ),
        )
        val completeInOneAbi = browserApk(
            dex = dex,
            terraceByAbi = mapOf(
                "arm64-v8a" to "options_ui options_page",
                "armeabi-v7a" to "options_ui",
            ),
        )

        assertFalse(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(splitAcrossAbis).supportedFeatures,
        )
        assertTrue(
            "extension.options-page.user-accessible" in
                BrowserProfileExtractor().extract(completeInOneAbi).supportedFeatures,
        )
    }

    @Test
    fun `extracts the Samsung extension contract and supported namespaces`() {
        val directory = Files.createTempDirectory("browser-profile")
        val apk = directory.resolve(
            "sbrowser_30.0.2.61-1300261502_minAPI30.apk",
        )
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                """
                six://ex extension-id .contentprovider dev_ext
                unrelated 10.200004.10.1 versionCode 999999999
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write(
                """
                {"namespace":"storage"}{"namespace":"tabs"}{"namespace":"offscreen"}
                {"namespace":"debugger"}{"namespace":"downloads"}
                {"namespace":"nativeMessaging"}{"namespace":"webstorePrivate"}
                """.trimIndent()
                    .toByteArray(),
            )
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(30, profile.majorVersion)
        assertEquals(setOf("offscreen", "storage", "tabs"), profile.supportedApis)
        assertTrue(profile.wrapperContract.discoveryUri)
        assertTrue(profile.wrapperContract.extensionMetadata)
        assertTrue(profile.wrapperContract.contentProvider)
        assertTrue(profile.wrapperContract.devExtensionPath)
        assertFalse(profile.isSupportedBaseline)
        assertEquals(ProfileSupport.UNKNOWN, profile.support)
        val encoded = BrowserProfileJson.encode(profile)
        assertTrue("\"detectedApis\"" in encoded)
        assertTrue("\"debugger\"" in encoded)
        assertTrue("\"webstorePrivate\"" in encoded)
    }

    @Test
    fun `marks an incomplete or new contract unknown`() {
        val apk = Files.createTempFile("browser", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("versionName 31.0.0.1 extension-id".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertFalse(profile.isSupportedBaseline)
        assertEquals(ProfileSupport.UNKNOWN, profile.support)
    }

    @Test
    fun `marks a changed 30 x API profile unknown until reviewed`() {
        val apk = Files.createTempFile("browser-30.1.0.1-", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                "six://ex extension-id .contentprovider dev_ext versionName 30.1.0.1"
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write("""{"namespace":"storage"}""".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(ProfileSupport.UNKNOWN, profile.support)
        assertTrue(profile.profileDifferences.any { it.startsWith("missing_api:") })
    }

    private fun browserApk(dex: String, terrace: String): Path {
        return browserApk(dex, mapOf("arm64-v8a" to terrace))
    }

    private fun browserApk(dex: String, terraceByAbi: Map<String, String>): Path {
        val apk = Files.createTempFile("six-browser-profile-", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex.toByteArray())
            zip.closeEntry()
            terraceByAbi.forEach { (abi, terrace) ->
                zip.putNextEntry(ZipEntry("lib/$abi/libterrace.so"))
                zip.write(terrace.toByteArray())
                zip.closeEntry()
            }
        }
        return apk
    }
}
