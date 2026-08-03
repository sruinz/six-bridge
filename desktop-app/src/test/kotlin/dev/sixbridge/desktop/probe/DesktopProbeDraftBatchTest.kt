package dev.sixbridge.desktop.probe

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopProbeDraftBatchTest {
    @Test
    fun `여섯 canonical group에 결정론 project identity를 적용한다`() {
        val root = Files.createTempDirectory("desktop-probe-batch-")
        val sources = root.resolve("source")
        val output = root.resolve("drafts")
        val profileFile = root.resolve("profile.json")
        Files.createDirectory(sources)
        groups.forEach { group ->
            Files.createDirectory(sources.resolve(group))
            sources.resolve(group).resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"$group","version":"1"}""",
            )
        }
        profileFile.writeText(BrowserProfileJson.encode(profile))
        val identities = linkedMapOf<String, String>()

        DesktopProbeDraftBatch.runBatch(
            sourceRoot = sources,
            profilePath = profileFile,
            outputRoot = output,
            draftBuilder = ProbeDraftBuilder { group, _, _, projectIdentity, destination ->
                identities[group] = projectIdentity
                destination.writeText("draft-$group")
            },
        )

        assertEquals(groups, identities.keys.toList())
        groups.forEach { group ->
            assertEquals(projectIdentity(group), identities.getValue(group))
            assertTrue(Files.isRegularFile(output.resolve("$group.sixdraft")))
        }
    }

    @Test
    fun `누락 group과 source symlink를 draft 생성 전에 거부한다`() {
        val root = Files.createTempDirectory("desktop-probe-batch-invalid-")
        val sources = root.resolve("source")
        val profileFile = root.resolve("profile.json")
        Files.createDirectory(sources)
        groups.dropLast(1).forEach { Files.createDirectory(sources.resolve(it)) }
        profileFile.writeText(BrowserProfileJson.encode(profile))

        assertFailsWith<IllegalArgumentException> {
            DesktopProbeDraftBatch.runBatch(
                sources,
                profileFile,
                root.resolve("missing-output"),
                ProbeDraftBuilder { _, _, _, _, _ -> error("호출되면 안 됩니다.") },
            )
        }

        Files.createDirectory(sources.resolve(groups.last()))
        Files.createSymbolicLink(
            sources.resolve(groups.first()).resolve("linked-manifest.json"),
            profileFile,
        )
        assertFailsWith<IllegalArgumentException> {
            DesktopProbeDraftBatch.runBatch(
                sources,
                profileFile,
                root.resolve("symlink-output"),
                ProbeDraftBuilder { _, _, _, _, _ -> error("호출되면 안 됩니다.") },
            )
        }
    }

    private fun projectIdentity(group: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("sixbridge-api-probe:v1:$group".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private companion object {
        val groups = listOf(
            "advanced-runtime",
            "base-runtime",
            "identity-privacy",
            "network",
            "tab-page",
            "user-interface",
        )
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.0.67",
            versionCode = 1,
            majorVersion = 30,
            supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = "1".repeat(64),
            installationApkSetSha256 = "2".repeat(64),
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
