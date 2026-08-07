package dev.sixbridge.manager.profile

import dev.sixbridge.core.profile.BrowserArchiveInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.ProfileSupport
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        val cache = MemoryCache()
        val result = provider(found(), cache).load()

        val supported = assertIs<SamsungBrowserProfileResult.Supported>(result)

        assertEquals("com.sec.android.app.sbrowser", supported.profile.browserPackage)
        assertEquals(30, supported.profile.majorVersion)
        assertEquals(ProfileSupport.SUPPORTED, supported.profile.support)
        assertTrue(supported.profile.installationApkSetSha256.matches(SHA256))
        assertEquals(3, cache.value?.schemaVersion)
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
    fun `구문상 유효한 cache facts 변조도 profile digest 불일치로 재검사한다`(): Unit =
        runBlocking {
            val cache = MemoryCache()
            val incomplete = CountingArchive(
                "base.apk",
                apk(dex = "extension-id"),
            )
            assertIs<SamsungBrowserProfileResult.Unknown>(
                provider(found(baseApk = incomplete), cache).load(),
            )
            val forged = BrowserProfileJson.decode(cache.value!!.encodedProfile)
                .copy(
                    wrapperContract = WrapperContract.complete(),
                    profileDifferences = emptyList(),
                )
            cache.value = cache.value?.copy(
                encodedProfile = BrowserProfileJson.encode(forged),
            )

            val result = provider(found(baseApk = incomplete), cache).load()

            assertIs<SamsungBrowserProfileResult.Unknown>(result)
            assertEquals(4, incomplete.openCount)

            val invalidFacts = Json.parseToJsonElement(cache.value!!.encodedProfile)
                .jsonObject
                .toMutableMap()
                .apply {
                    val feature = JsonArray(listOf(JsonPrimitive("same-feature")))
                    this["supportedFeatures"] = feature
                    this["unavailableFeatures"] = feature
                }
            cache.value = cache.value?.copy(
                encodedProfile = JsonObject(invalidFacts).toString(),
            )

            assertIs<SamsungBrowserProfileResult.Unknown>(
                provider(found(baseApk = incomplete), cache).load(),
            )
            assertEquals(6, incomplete.openCount)
        }

    @Test
    fun `legacy domain schema 또는 profile digest가 다른 cache는 재검사한다`(): Unit =
        runBlocking {
            listOf<(CachedSamsungBrowserProfile) -> CachedSamsungBrowserProfile>(
                { it.copy(domain = "legacy") },
                { it.copy(schemaVersion = 2) },
                { it.copy(profileDigest = "0".repeat(64)) },
            ).forEach { corrupt ->
                val cache = MemoryCache()
                assertIs<SamsungBrowserProfileResult.Supported>(
                    provider(found(), cache).load(),
                )
                cache.value = corrupt(cache.value!!)
                val archive = CountingArchive("base.apk", completeApk())

                assertIs<SamsungBrowserProfileResult.Supported>(
                    provider(found(baseApk = archive), cache).load(),
                )
                assertEquals(2, archive.openCount)
            }
        }

    @Test
    fun `cache hit 직전과 직후 설치 snapshot이 바뀌면 stale 결과를 거부한다`(): Unit =
        runBlocking {
            val cache = MemoryCache()
            val stable = found()
            assertIs<SamsungBrowserProfileResult.Supported>(
                provider(stable, cache).load(),
            )
            val changed = found(
                baseApk = archive("base.apk", apk(padding = "changed")),
            )

            val result = provider(listOf(stable, changed), cache).load()

            val unknown = assertIs<SamsungBrowserProfileResult.Unknown>(result)
            assertTrue("package_changed" in unknown.reasons)
        }

    @Test
    fun `full scan 직전과 직후 설치 snapshot이 바뀌면 cache하지 않는다`(): Unit =
        runBlocking {
            val cache = MemoryCache()
            val initial = found()
            val changed = found(versionCode = initial.versionCode + 1)

            val result = provider(listOf(initial, changed), cache).load()

            val unknown = assertIs<SamsungBrowserProfileResult.Unknown>(result)
            assertTrue("package_changed" in unknown.reasons)
            assertNull(cache.value)
        }

    @Test
    fun `cache 저장 commit 실패는 안전한 현재 결과를 반환하고 다음 load에서 재검사한다`(): Unit =
        runBlocking {
            val cache = MemoryCache(saveSucceeds = false)
            val archive = CountingArchive("base.apk", completeApk())

            assertIs<SamsungBrowserProfileResult.Supported>(
                provider(found(baseApk = archive), cache).load(),
            )
            assertIs<SamsungBrowserProfileResult.Supported>(
                provider(found(baseApk = archive), cache).load(),
            )

            assertNull(cache.value)
            assertEquals(4, archive.openCount)
        }

    @Test
    fun `coroutine cancellation은 Unknown으로 감싸지 않고 전파한다`() {
        val provider = AndroidSamsungBrowserProfileProvider(
            packageLookup = SamsungBrowserPackageLookup { checkCancelled ->
                checkCancelled()
                throw CancellationException("cancelled")
            },
            cache = MemoryCache(),
        )

        assertFailsWith<CancellationException> {
            runBlocking { provider.load() }
        }
    }

    @Test
    fun `Android splitNames와 splitSourceDirs를 1대1 logical archive로 전달한다`() {
        val base = File.createTempFile("sixbridge-base", ".apk")
        val split = File.createTempFile("sixbridge-split", ".apk")
        try {
            base.writeBytes(byteArrayOf(1, 2, 3))
            split.writeBytes(byteArrayOf(4, 5, 6))

            val archives = androidArchiveInputs(
                sourceDir = base.absolutePath,
                splitNames = listOf("config.arm64_v8a"),
                splitSourceDirs = listOf(split.absolutePath),
                checkCancelled = {},
            )

            assertEquals("base.apk", archives.baseApk.name)
            assertEquals(listOf("split_config.arm64_v8a.apk"), archives.splitApks.map { it.name })
            assertEquals(sha256(base.readBytes()), archives.baseApk.sha256)
            assertEquals(sha256(split.readBytes()), archives.splitApks.single().sha256)
            assertEquals(
                listOf(4, 5, 6),
                archives.splitApks.single().openStream().use { it.readBytes() }
                    .map(Byte::toInt),
            )
            assertFailsWith<IllegalArgumentException> {
                androidArchiveInputs(
                    sourceDir = base.absolutePath,
                    splitNames = emptyList(),
                    splitSourceDirs = listOf(split.absolutePath),
                    checkCancelled = {},
                )
            }
        } finally {
            base.delete()
            split.delete()
        }
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
        packageLookup = SamsungBrowserPackageLookup { _ -> lookup },
        cache = cache,
    )

    private fun provider(
        lookups: List<SamsungBrowserPackageLookupResult>,
        cache: SamsungBrowserProfileCache = MemoryCache(),
    ): AndroidSamsungBrowserProfileProvider {
        val remaining = ArrayDeque(lookups)
        return AndroidSamsungBrowserProfileProvider(
            packageLookup = SamsungBrowserPackageLookup {
                remaining.removeFirstOrNull() ?: lookups.last()
            },
            cache = cache,
        )
    }

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

    private class MemoryCache(
        private val saveSucceeds: Boolean = true,
    ) : SamsungBrowserProfileCache {
        var value: CachedSamsungBrowserProfile? = null

        override fun load(): CachedSamsungBrowserProfile? = value

        override fun save(value: CachedSamsungBrowserProfile): Boolean {
            if (saveSucceeds) this.value = value
            return saveSucceeds
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
