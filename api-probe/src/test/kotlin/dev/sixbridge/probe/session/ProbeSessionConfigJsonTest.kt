package dev.sixbridge.probe.session

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeSessionConfigJsonTest {
    @Test
    fun `session config가 exact canonical JSON으로 round trip한다`() {
        val encoded = ProbeSessionConfigJson.encode(config())
        val decoded = ProbeSessionConfigJson.decodeStrict(encoded)

        assertEquals(config(), decoded)
        assertEquals(encoded, ProbeSessionConfigJson.encode(decoded))
        assertTrue(encoded.endsWith("\n"))
        assertEquals(ApiProbeCatalog.PROBE_GROUP_IDS.sorted(), decoded.bindings.map { it.groupId })
    }

    @Test
    fun `unknown missing duplicate field를 거부한다`() {
        val encoded = ProbeSessionConfigJson.encode(config())
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst("{", "{\"unknown\":true,"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replace(
                "    \"sessionId\": \"20260802-143000\",\n",
                "",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"schemaVersion\": 1",
                "\"schemaVersion\": 1, \"schemaVersion\": 1",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"groupId\": \"advanced-runtime\"",
                "\"groupId\": \"advanced-runtime\", \"groupId\": \"advanced-runtime\"",
            ))
        }
    }

    @Test
    fun `binding은 exact group과 unique extension ID 및 동일 signer를 요구한다`() {
        val base = config()
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.dropLast(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.mapIndexed { index, value ->
                if (index == 1) value.copy(extensionId = base.bindings.first().extensionId) else value
            }).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.mapIndexed { index, value ->
                if (index == 1) value.copy(wrapperSignerSha256 = "f".repeat(64)) else value
            }).validate()
        }
    }

    @Test
    fun `origin은 extension ID에서만 계산한다`() {
        val binding = config().bindings.first()
        assertEquals("chrome-extension://${binding.extensionId}", binding.origin)
    }

    @Test
    fun `config는 owner only regular file로 durable no replace publish한다`() = withDirectory { root ->
        val output = root.resolve("session.json")

        ProbeSessionConfigJson.writeNew(output, config())

        assertEquals(config(), ProbeSessionConfigJson.readStrict(output))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(output),
        )
        val original = output.readText()
        assertFailsWith<IllegalArgumentException> { ProbeSessionConfigJson.writeNew(output, config()) }
        assertEquals(original, output.readText())
        assertFalse(Files.list(root).use { entries -> entries.anyMatch { it.fileName.toString().contains(".tmp-") } })
    }

    @Test
    fun `symlink과 0644 config를 거부한다`() = withDirectory { root ->
        val target = root.resolve("target.json")
        Files.writeString(target, ProbeSessionConfigJson.encode(config()), UTF_8)
        Files.setPosixFilePermissions(target, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
        ))
        assertFailsWith<IllegalArgumentException> { ProbeSessionConfigJson.readStrict(target) }

        Files.setPosixFilePermissions(target, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        ))
        val link = root.resolve("link.json")
        link.createSymbolicLinkPointingTo(target)
        assertFailsWith<IllegalArgumentException> { ProbeSessionConfigJson.readStrict(link) }
    }

    private fun config(): ProbeSessionConfig {
        val signer = "b".repeat(64)
        return ProbeSessionConfig(
            schemaVersion = 1,
            sessionId = "20260802-143000",
            catalogSha256 = "a".repeat(64),
            browserPackage = "com.sec.android.app.sbrowser",
            browserVersionName = "30.0.2.61",
            browserVersionCode = 300020061,
            browserApkSetSha256 = "c".repeat(64),
            browserSignerSha256 = "d".repeat(64),
            bindings = ApiProbeCatalog.PROBE_GROUP_IDS.sorted().mapIndexed { index, groupId ->
                ProbeSessionBinding(
                    groupId = groupId,
                    extensionId = ('a'.code + index).toChar().toString().repeat(32),
                    wrapperSignerSha256 = signer,
                )
            },
        ).also(ProbeSessionConfig::validate)
    }

    private fun withDirectory(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("probe-session-test-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
