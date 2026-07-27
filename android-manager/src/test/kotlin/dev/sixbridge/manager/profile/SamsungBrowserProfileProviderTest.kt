package dev.sixbridge.manager.profile

import dev.sixbridge.core.profile.BrowserArchiveInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.ProfileSupport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SamsungBrowserProfileProviderTest {
    @Test
    fun `삼성 인터넷이 설치되지 않았을 때만 Missing이다`(): Unit = runBlocking {
        val provider = provider(SamsungBrowserPackageLookupResult.Missing)

        assertIs<SamsungBrowserProfileResult.Missing>(provider.load())
    }

    @Test
    fun `package 조회 실패는 Unknown이다`(): Unit = runBlocking {
        val provider = provider(
            SamsungBrowserPackageLookupResult.Failure("package_read_failed"),
        )

        val result = assertIs<SamsungBrowserProfileResult.Unknown>(provider.load())

        assertEquals(listOf("package_read_failed"), result.reasons)
    }

    @Test
    fun `signer가 정확히 하나가 아니면 Unknown이다`(): Unit = runBlocking {
        listOf(
            emptyList(),
            listOf(BrowserProfile.SAMSUNG_SIGNER_SHA256, "2".repeat(64)),
        ).forEach { signers ->
            val result = provider(found(signers = signers)).load()

            assertIs<SamsungBrowserProfileResult.Unknown>(result)
        }
    }

    @Test
    fun `Samsung signer가 아니면 archive를 읽지 않고 Unknown이다`(): Unit = runBlocking {
        val archive = CountingArchive("base.apk", completeApk())
        val result = provider(
            found(
                signers = listOf("2".repeat(64)),
                baseApk = archive,
            ),
        ).load()

        assertIs<SamsungBrowserProfileResult.Unknown>(result)
        assertEquals(0, archive.openCount)
    }

    @Test
    fun `major 31은 archive를 읽지 않고 Unknown이다`(): Unit = runBlocking {
        val archive = CountingArchive("base.apk", completeApk())
        val result = provider(
            found(versionName = "31.0.0.1", baseApk = archive),
        ).load()

        assertIs<SamsungBrowserProfileResult.Unknown>(result)
        assertEquals(0, archive.openCount)
    }

    @Test
    fun `wrapper marker가 누락되면 profile과 함께 Unknown이다`(): Unit = runBlocking {
        val result = provider(
            found(baseApk = archive("base.apk", apk(dex = "extension-id"))),
        ).load()

        val unknown = assertIs<SamsungBrowserProfileResult.Unknown>(result)

        assertEquals(ProfileSupport.UNKNOWN, unknown.profile?.support)
        assertTrue(unknown.reasons.any { it.startsWith("wrapper_contract:") })
    }

    @Test
    fun `baseline API와 다르면 profile difference와 함께 Unknown이다`(): Unit = runBlocking {
        val result = provider(
            found(
                baseApk = archive(
                    "base.apk",
                    apk(
                        dex = COMPLETE_MARKERS,
                        terrace = """{"namespace":"storage"}""",
                    ),
                ),
            ),
        ).load()

        val unknown = assertIs<SamsungBrowserProfileResult.Unknown>(result)

        assertTrue(unknown.reasons.any { it.startsWith("missing_api:") })
    }

    @Test
    fun `정상 Samsung 30 x 설치 profile은 Supported이다`(): Unit = runBlocking {
        val result = provider(found()).load()

        val supported = assertIs<SamsungBrowserProfileResult.Supported>(result)

        assertEquals("com.sec.android.app.sbrowser", supported.profile.browserPackage)
        assertEquals(30, supported.profile.majorVersion)
        assertEquals(ProfileSupport.SUPPORTED, supported.profile.support)
        assertTrue(supported.profile.installationApkSetSha256.matches(SHA256))
    }

    @Test
    fun `cache key가 다르면 stale Supported를 반환하지 않고 재검사한다`(): Unit =
        runBlocking {
            val cache = MemoryCache()
            val initial = found()
            assertIs<SamsungBrowserProfileResult.Supported>(provider(initial, cache).load())
            val changedArchive = CountingArchive(
                "base.apk",
                apk(dex = "extension-id", padding = "changed"),
            )

            val result = provider(found(baseApk = changedArchive), cache).load()

            assertIs<SamsungBrowserProfileResult.Unknown>(result)
            assertEquals(2, changedArchive.openCount)
        }

    @Test
    fun `현재 설치 facts와 일치하는 cache는 encoded profile을 반환한다`(): Unit =
        runBlocking {
            val cache = MemoryCache()
            val bytes = completeApk()
            assertIs<SamsungBrowserProfileResult.Supported>(
                provider(found(baseApk = archive("base.apk", bytes)), cache).load(),
            )
            val cachedArchive = object : BrowserArchiveInput {
                override val name = "base.apk"
                override val sha256 = sha256(bytes)
                override fun openStream(): InputStream =
                    error("일치하는 cache에서 archive scan을 반복하면 안 됩니다.")
            }

            val result = provider(found(baseApk = cachedArchive), cache).load()

            assertIs<SamsungBrowserProfileResult.Supported>(result)
        }

    @Test
    fun `decode할 수 없는 cache는 재검사하고 교체한다`(): Unit = runBlocking {
        val cache = MemoryCache()
        assertIs<SamsungBrowserProfileResult.Supported>(provider(found(), cache).load())
        cache.value = cache.value?.copy(encodedProfile = "{")
        val archive = CountingArchive("base.apk", completeApk())

        val result = provider(found(baseApk = archive), cache).load()

        assertIs<SamsungBrowserProfileResult.Supported>(result)
        assertEquals(2, archive.openCount)
        assertTrue(cache.value?.encodedProfile?.startsWith("{") == true)
        assertEquals(
            ProfileSupport.SUPPORTED,
            BrowserProfileJson.decode(cache.value!!.encodedProfile).support,
        )
    }

    @Test
    fun `cache profile metadata가 현재 설치와 다르면 재검사한다`(): Unit = runBlocking {
        val cache = MemoryCache()
        assertIs<SamsungBrowserProfileResult.Supported>(provider(found(), cache).load())
        val stale = BrowserProfileJson.decode(cache.value!!.encodedProfile)
            .copy(versionCode = 7)
        cache.value = cache.value?.copy(encodedProfile = BrowserProfileJson.encode(stale))
        val archive = CountingArchive("base.apk", completeApk())

        val result = provider(found(baseApk = archive), cache).load()

        assertIs<SamsungBrowserProfileResult.Supported>(result)
        assertEquals(2, archive.openCount)
    }

    @Test
    fun `archive read 실패와 두 번 open 내용 변경은 Unknown이다`(): Unit = runBlocking {
        val failing = object : BrowserArchiveInput {
            override val name = "base.apk"
            override val sha256 = "0".repeat(64)
            override fun openStream(): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("failed")
            }
        }
        val first = completeApk()
        val second = apk(padding = "changed")
        var opens = 0
        val changing = object : BrowserArchiveInput {
            override val name = "base.apk"
            override val sha256 = sha256(first)
            override fun openStream(): InputStream =
                ByteArrayInputStream(if (++opens == 1) first else second)
        }

        assertIs<SamsungBrowserProfileResult.Unknown>(
            provider(found(baseApk = failing)).load(),
        )
        assertIs<SamsungBrowserProfileResult.Unknown>(
            provider(found(baseApk = changing)).load(),
        )
    }

    private fun provider(
        lookup: SamsungBrowserPackageLookupResult,
        cache: SamsungBrowserProfileCache = MemoryCache(),
    ) = AndroidSamsungBrowserProfileProvider(
        packageLookup = SamsungBrowserPackageLookup { lookup },
        cache = cache,
    )

    private fun found(
        versionName: String = "30.1.0.1",
        versionCode: Long = 1_300_300_001,
        signers: List<String> = listOf(BrowserProfile.SAMSUNG_SIGNER_SHA256),
        baseApk: BrowserArchiveInput = archive("base.apk", completeApk()),
        splitApks: List<BrowserArchiveInput> = emptyList(),
    ) = SamsungBrowserPackageLookupResult.Found(
        packageName = "com.sec.android.app.sbrowser",
        versionName = versionName,
        versionCode = versionCode,
        signerSha256 = signers,
        baseApk = baseApk,
        splitApks = splitApks,
    )

    private fun completeApk() = apk()

    private fun apk(
        dex: String = COMPLETE_MARKERS,
        terrace: String = baselineNamespaces(),
        padding: String = "",
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(dex.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write(terrace.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/padding.txt"))
            zip.write(padding.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun baselineNamespaces(): String = buildString {
        BrowserProfile.BASELINE_APIS.sorted().forEach {
            append("""{"namespace":"$it"}""")
        }
    }

    private fun archive(name: String, bytes: ByteArray) = CountingArchive(name, bytes)

    private class CountingArchive(
        override val name: String,
        private val bytes: ByteArray,
    ) : BrowserArchiveInput {
        override val sha256 = sha256(bytes)
        var openCount = 0
            private set

        override fun openStream(): InputStream {
            openCount += 1
            return ByteArrayInputStream(bytes)
        }
    }

    private class MemoryCache : SamsungBrowserProfileCache {
        var value: CachedSamsungBrowserProfile? = null

        override fun load(): CachedSamsungBrowserProfile? = value

        override fun save(value: CachedSamsungBrowserProfile) {
            this.value = value
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        const val COMPLETE_MARKERS =
            "six://ex extension-id .contentprovider dev_ext " +
                "getExtensionOptionPage six_add_on_settings updateGoAddonSettings"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
