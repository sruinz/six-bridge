package dev.sixbridge.core.profile

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BrowserArchiveEvidenceScannerTest {
    private val scanner = BrowserArchiveEvidenceScanner()

    @Test
    fun `설치 APK 집합 내용이 바뀌면 profile digest가 바뀐다`() {
        val first = scanner.scan(
            evidence(splitApks = listOf(archive("split_feature.apk", padding = "a"))),
        )
        val second = scanner.scan(
            evidence(splitApks = listOf(archive("split_feature.apk", padding = "b"))),
        )

        assertNotEquals(first.installationApkSetSha256, second.installationApkSetSha256)
        assertNotEquals(first.canonicalSha256(), second.canonicalSha256())
    }

    @Test
    fun `설치 APK 집합 이름이 바뀌면 profile digest가 바뀐다`() {
        val bytes = apkBytes(padding = "same")
        val first = scanner.scan(
            evidence(splitApks = listOf(archive("split_a.apk", bytes))),
        )
        val second = scanner.scan(
            evidence(splitApks = listOf(archive("split_b.apk", bytes))),
        )

        assertNotEquals(first.installationApkSetSha256, second.installationApkSetSha256)
        assertNotEquals(first.canonicalSha256(), second.canonicalSha256())
    }

    @Test
    fun `split APK 입력 순서는 installation digest에 영향을 주지 않는다`() {
        val firstSplit = archive("split_a.apk", padding = "a")
        val secondSplit = archive("split_b.apk", padding = "b")

        val first = scanner.scan(evidence(splitApks = listOf(firstSplit, secondSplit)))
        val second = scanner.scan(evidence(splitApks = listOf(secondSplit, firstSplit)))

        assertEquals(first.installationApkSetSha256, second.installationApkSetSha256)
        assertEquals(first.canonicalSha256(), second.canonicalSha256())
    }

    @Test
    fun `각 APK의 실제 hash가 선언 hash와 다르면 명시적으로 거부한다`() {
        val input = archive("base.apk").copyWithSha256("0".repeat(64))

        val failure = assertFailsWith<BrowserArchiveEvidenceException> {
            scanner.scan(evidence(baseApk = input))
        }

        assertEquals(BrowserArchiveEvidenceFailure.DECLARED_SHA256_MISMATCH, failure.failure)
    }

    @Test
    fun `같은 APK를 두 번 열었을 때 내용이 바뀌면 명시적으로 거부한다`() {
        val first = apkBytes(padding = "first")
        val second = apkBytes(padding = "second")
        var opens = 0
        val changing = object : BrowserArchiveInput {
            override val name = "base.apk"
            override val sha256 = sha256(first)

            override fun openStream(): InputStream {
                opens += 1
                return ByteArrayInputStream(if (opens == 1) first else second)
            }
        }

        val failure = assertFailsWith<BrowserArchiveEvidenceException> {
            scanner.scan(evidence(baseApk = changing))
        }

        assertEquals(BrowserArchiveEvidenceFailure.CONTENT_CHANGED, failure.failure)
        assertEquals(2, opens)
    }

    @Test
    fun `APK stream read 실패를 명시적으로 거부한다`() {
        val failing = object : BrowserArchiveInput {
            override val name = "base.apk"
            override val sha256 = "0".repeat(64)

            override fun openStream(): InputStream = object : InputStream() {
                override fun read(): Int = throw IOException("read failed")
            }
        }

        val failure = assertFailsWith<BrowserArchiveEvidenceException> {
            scanner.scan(evidence(baseApk = failing))
        }

        assertEquals(BrowserArchiveEvidenceFailure.READ_FAILED, failure.failure)
    }

    @Test
    fun `base와 split에 분산된 삼성 계약 marker와 API를 함께 검사한다`() {
        val base = archive(
            name = "base.apk",
            dex = """
                six://ex extension-id .contentprovider dev_ext
                getExtensionOptionPage six_add_on_settings updateGoAddonSettings
            """.trimIndent(),
            terrace = "",
        )
        val split = archive(
            name = "split_config.arm64_v8a.apk",
            dex = "",
            terrace = buildString {
                BrowserProfile.BASELINE_APIS.sorted().forEach {
                    append("""{"namespace":"$it"}""")
                }
                append(" options_ui options_page")
            },
        )

        val profile = scanner.scan(evidence(baseApk = base, splitApks = listOf(split)))

        assertEquals(WrapperContract.complete(), profile.wrapperContract)
        assertEquals(BrowserProfile.BASELINE_APIS, profile.detectedApis)
        assertTrue("extension.options-page.user-accessible" in profile.supportedFeatures)
        assertEquals(ProfileSupport.SUPPORTED, profile.support)
    }

    @Test
    fun `installation digest도 filename과 canonical collision을 먼저 검증한다`() {
        listOf("", ".", "..", "dir/base.apk", "dir\\base.apk", "base\u0000.apk")
            .forEach { invalidName ->
                val failure = assertFailsWith<BrowserArchiveEvidenceException> {
                    evidence(baseApk = archive(invalidName)).installationApkSetSha256()
                }
                assertEquals(
                    BrowserArchiveEvidenceFailure.INVALID_EVIDENCE,
                    failure.failure,
                )
            }

        listOf(
            archive("Base.apk") to archive("base.apk"),
            archive("É.apk") to archive("e\u0301.apk"),
            archive("straße.apk") to archive("STRASSE.apk"),
        ).forEach { (base, split) ->
            val failure = assertFailsWith<BrowserArchiveEvidenceException> {
                evidence(baseApk = base, splitApks = listOf(split))
                    .installationApkSetSha256()
            }
            assertEquals(
                BrowserArchiveEvidenceFailure.INVALID_EVIDENCE,
                failure.failure,
            )
        }
    }

    @Test
    fun `exact baseline base에 split 추가 또는 변경은 Unknown이고 제거는 복구된다`() {
        val baseOnly = scanner.scan(
            evidence(versionName = BrowserProfile.BASELINE_VERSION),
        ).copy(sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256)
        val added = scanner.scan(
            evidence(
                versionName = BrowserProfile.BASELINE_VERSION,
                splitApks = listOf(archive("feature.apk", padding = "first")),
            ),
        ).copy(sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256)
        val changed = scanner.scan(
            evidence(
                versionName = BrowserProfile.BASELINE_VERSION,
                splitApks = listOf(archive("feature.apk", padding = "second")),
            ),
        ).copy(sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256)

        assertEquals(ProfileSupport.SUPPORTED, baseOnly.support)
        assertEquals(ProfileSupport.UNKNOWN, added.support)
        assertEquals(ProfileSupport.UNKNOWN, changed.support)
        assertTrue(added.profileDifferences.any { it.startsWith("unexpected_split:") })
        assertNotEquals(
            added.installationApkSetSha256,
            changed.installationApkSetSha256,
        )
        assertNotEquals(
            added.installationApkSetSha256,
            baseOnly.installationApkSetSha256,
        )
    }

    @Test
    fun `raw entry total inflate와 ratio budget 초과를 구분해 거부한다`() {
        val bytes = apkBytes()
        val cases = listOf(
            BrowserArchiveScanLimits(maxRawBytesPerArchive = bytes.size.toLong() - 1) to
                evidence(baseApk = archive("base.apk", bytes)),
            BrowserArchiveScanLimits(maxTotalRawBytes = bytes.size.toLong() * 2 - 1) to
                evidence(
                    baseApk = archive("base.apk", bytes),
                    splitApks = listOf(archive("feature.apk", bytes)),
                ),
            BrowserArchiveScanLimits(maxArchiveCount = 1) to
                evidence(
                    baseApk = archive("base.apk", bytes),
                    splitApks = listOf(archive("feature.apk", bytes)),
                ),
            BrowserArchiveScanLimits(maxEntryCountPerArchive = 1) to
                evidence(baseApk = archive("base.apk", bytes)),
            BrowserArchiveScanLimits(maxInflatedBytesPerEntry = 8) to
                evidence(baseApk = archive("base.apk", bytes)),
            BrowserArchiveScanLimits(maxTotalInflatedBytesPerArchive = 8) to
                evidence(baseApk = archive("base.apk", bytes)),
            BrowserArchiveScanLimits(maxInflateRatio = 2) to
                evidence(
                    baseApk = archive(
                        "base.apk",
                        apkBytes(padding = "0".repeat(64 * 1024)),
                    ),
                ),
        )
        cases.forEach { (limits, evidence) ->
            val failure = assertFailsWith<BrowserArchiveEvidenceException> {
                BrowserArchiveEvidenceScanner(limits = limits)
                    .scan(evidence)
            }
            assertEquals(
                BrowserArchiveEvidenceFailure.RESOURCE_LIMIT_EXCEEDED,
                failure.failure,
            )
        }
    }

    @Test
    fun `scanner cancellation은 evidence 실패로 감싸지 않고 전파한다`() {
        var checks = 0
        val scanner = BrowserArchiveEvidenceScanner(
            checkCancelled = {
                checks += 1
                if (checks == 3) throw CancellationException("cancelled")
            },
        )

        assertFailsWith<CancellationException> {
            scanner.scan(evidence())
        }
    }

    @Test
    fun `archive close 실패도 read 실패로 닫는다`() {
        val bytes = apkBytes()
        val input = object : BrowserArchiveInput {
            override val name = "base.apk"
            override val sha256 = sha256(bytes)
            override fun openStream(): InputStream = object : ByteArrayInputStream(bytes) {
                override fun close() {
                    super.close()
                    throw IOException("close failed")
                }
            }
        }

        val failure = assertFailsWith<BrowserArchiveEvidenceException> {
            scanner.scan(evidence(baseApk = input))
        }

        assertEquals(BrowserArchiveEvidenceFailure.READ_FAILED, failure.failure)
    }

    private fun evidence(
        baseApk: BrowserArchiveInput = archive("base.apk"),
        splitApks: List<BrowserArchiveInput> = emptyList(),
        versionName: String = "30.1.0.1",
        signerSha256: String = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    ) = BrowserPackageEvidence(
        packageName = "com.sec.android.app.sbrowser",
        versionName = versionName,
        versionCode = 1_300_300_001,
        signerSha256 = signerSha256,
        baseApk = baseApk,
        splitApks = splitApks,
    )

    private fun archive(
        name: String,
        padding: String = "",
        dex: String = "six://ex extension-id .contentprovider dev_ext",
        terrace: String = baselineNamespaces(),
    ): ByteArrayArchiveInput = archive(name, apkBytes(dex, terrace, padding))

    private fun archive(name: String, bytes: ByteArray) =
        ByteArrayArchiveInput(name, sha256(bytes), bytes)

    private fun apkBytes(
        dex: String = "six://ex extension-id .contentprovider dev_ext",
        terrace: String = baselineNamespaces(),
        padding: String = "",
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            if (dex.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(dex.toByteArray())
                zip.closeEntry()
            }
            if (terrace.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
                zip.write(terrace.toByteArray())
                zip.closeEntry()
            }
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class ByteArrayArchiveInput(
        override val name: String,
        override val sha256: String,
        private val bytes: ByteArray,
    ) : BrowserArchiveInput {
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)

        fun copyWithSha256(value: String): ByteArrayArchiveInput = copy(sha256 = value)
    }
}
