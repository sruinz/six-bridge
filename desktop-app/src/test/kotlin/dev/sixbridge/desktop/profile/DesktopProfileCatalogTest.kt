package dev.sixbridge.desktop.profile

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.SignedBrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DesktopProfileCatalogTest {
    private val json = Json
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        detectedApis = BrowserProfile.BASELINE_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `profile JSON parse의 CancellationException과 Error는 전파한다`() {
        val cancelled = DesktopProfileCatalog.testing(
            dataRoot = Files.createTempDirectory("cancel-json-catalog"),
            parseProfileJson = { throw CancellationException("취소") },
        )
        val failed = DesktopProfileCatalog.testing(
            dataRoot = Files.createTempDirectory("error-json-catalog"),
            parseProfileJson = { throw AssertionError("fatal") },
        )

        assertFailsWith<CancellationException> {
            cancelled.prepareJson("{}".encodeToByteArray())
        }
        assertFailsWith<AssertionError> {
            failed.prepareJson("{}".encodeToByteArray())
        }
    }

    @Test
    fun `APK prepare는 extractor에 cancellation을 전달하고 취소를 untrusted로 바꾸지 않는다`() {
        val apk = Files.createTempFile("cancel-sbrowser", ".apk")
        val catalog = DesktopProfileCatalog.testingCancelable(
            dataRoot = Files.createTempDirectory("cancel-catalog"),
            extractProfile = { _, checkCancelled ->
                checkCancelled()
                profile
            },
        )

        assertFailsWith<CancellationException> {
            catalog.prepareApk(apk) { throw CancellationException("취소") }
        }
    }

    @Test
    fun `기본 profile은 최신 내장 30점0점2점61이다`() {
        val catalog = catalog()

        assertEquals("30.0.2.61", catalog.default().profile?.versionName)
        assertEquals(DesktopProfileTrust.BUILT_IN, catalog.default().trust)
        assertTrue(catalog.default().selectable)
    }

    @Test
    fun `미서명 임의 JSON과 unknown APK profile은 선택할 수 없다`() {
        val catalog = catalog(
            extract = { profile.copy(profileDifferences = listOf("unknown_apk_evidence")) },
        )

        val raw = catalog.importJson(
            BrowserProfileJson.encode(profile.copy(versionName = "31.0.0.1")).encodeToByteArray(),
        )
        val apk = catalog.importApk(Files.createTempFile("unknown-sbrowser", ".apk"))

        assertFalse(raw.selectable)
        assertEquals(DesktopProfileTrust.UNTRUSTED, raw.trust)
        assertFalse(apk.selectable)
        assertEquals(DesktopProfileTrust.UNTRUSTED, apk.trust)
    }

    @Test
    fun `내장 profile 의미값에 unknown 또는 duplicate field가 있으면 신뢰하지 않는다`() {
        val resource = checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "profiles/builtin/sbrowser-30.0.2.61.json",
            ),
        ).use { it.readBytes().decodeToString() }
        val unknown = resource.dropLastWhile(Char::isWhitespace).dropLast(1) +
            ",\n    \"unexpected\": true\n}"
        val duplicate = resource.replaceFirst(
            "{",
            "{\n    \"versionName\": \"spoofed\",",
        )
        val catalog = catalog()

        assertEquals(DesktopProfileTrust.UNTRUSTED, catalog.importJson(unknown.encodeToByteArray()).trust)
        assertEquals(DesktopProfileTrust.UNTRUSTED, catalog.importJson(duplicate.encodeToByteArray()).trust)
    }

    @Test
    fun `검증된 envelope와 지원 APK profile만 원자 저장 후 다시 불러온다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            extract = { profile.copy(supportedFeatures = setOf("storage.local")) },
        )
        val envelope = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "test-key",
            pair.private,
        )

        val signed = catalog.importJson(envelope.encodeToByteArray())
        val extracted = catalog.importApk(Files.createTempFile("supported-sbrowser", ".apk"))
        val reloaded = catalog.loadPersisted()

        assertEquals(DesktopProfileTrust.SIGNED, signed.trust)
        assertTrue(signed.selectable)
        assertEquals(DesktopProfileTrust.APK_EXTRACTED_SUPPORTED, extracted.trust)
        assertTrue(extracted.selectable)
        assertEquals(
            setOf(DesktopProfileTrust.SIGNED, DesktopProfileTrust.APK_EXTRACTED_SUPPORTED),
            reloaded.map(DesktopProfileOption::trust).toSet(),
        )
        assertEquals(2, Files.list(dataRoot.resolve("profiles")).use { it.count() })
    }

    @Test
    fun `profile prepare는 영속화하지 않고 current commit만 저장한다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
        )
        val envelope = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "test-key",
            pair.private,
        )

        val prepared = catalog.prepareJson(envelope.encodeToByteArray())

        assertFalse(Files.exists(dataRoot.resolve("profiles")))
        val committed = catalog.commit(prepared)
        assertEquals(DesktopProfileTrust.SIGNED, committed.trust)
        assertEquals(1, Files.list(dataRoot.resolve("profiles")).use { it.count() })
    }

    @Test
    fun `변조 파일명과 symlink 영속 profile은 다시 불러오지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val profiles = Files.createDirectories(dataRoot.resolve("profiles"))
        val outside = Files.createTempFile("outside-profile", ".json")
        Files.writeString(outside, "{}")
        Files.createSymbolicLink(profiles.resolve("${"0".repeat(64)}.json"), outside)
        Files.writeString(profiles.resolve("not-a-sha.json"), "{}")

        assertTrue(catalog(dataRoot = dataRoot).loadPersisted().isEmpty())
    }

    @Test
    fun `정적 profiles symlink에는 저장하지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val outsideDirectory = Files.createTempDirectory("six-bridge-outside")
        Files.createSymbolicLink(dataRoot.resolve("profiles"), outsideDirectory)
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
        )

        val imported = catalog.importJson(signedEnvelope(pair).encodeToByteArray())

        assertEquals(DesktopProfileTrust.SIGNED, imported.trust)
        Files.list(outsideDirectory).use { entries ->
            assertEquals(0, entries.count())
        }
        assertTrue(catalog.loadPersisted().isEmpty())
    }

    @Test
    fun `temp open 전 parent symlink replacement는 외부에 파일을 만들지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val outsideDirectory = Files.createTempDirectory("six-bridge-outside")
        val displacedDirectory = dataRoot.resolve("profiles-displaced")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        var replaced = false
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            persistenceCheckpoint = { checkpoint, directory ->
                if (
                    checkpoint == DesktopProfilePersistenceCheckpoint.PERSIST_BEFORE_TEMP_OPEN &&
                    !replaced
                ) {
                    Files.move(directory, displacedDirectory)
                    Files.createSymbolicLink(directory, outsideDirectory)
                    replaced = true
                }
            },
        )

        assertFails {
            catalog.importJson(signedEnvelope(pair).encodeToByteArray())
        }

        Files.list(outsideDirectory).use { entries ->
            assertEquals(0, entries.count())
        }
        Files.list(displacedDirectory).use { entries ->
            assertEquals(0, entries.count())
        }
    }

    @Test
    fun `force 후 실제 directory replacement는 실패 temp를 남긴다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val displacedDirectory = dataRoot.resolve("profiles-displaced")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        var replaced = false
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            persistenceCheckpoint = { checkpoint, directory ->
                if (
                    checkpoint == DesktopProfilePersistenceCheckpoint.PERSIST_AFTER_FORCE &&
                    !replaced
                ) {
                    Files.move(directory, displacedDirectory)
                    Files.createDirectory(directory)
                    replaced = true
                }
            },
        )

        assertFails {
            catalog.importJson(signedEnvelope(pair).encodeToByteArray())
        }

        Files.list(dataRoot.resolve("profiles")).use { entries ->
            assertEquals(0, entries.count())
        }
        Files.list(displacedDirectory).use { entries ->
            val names = entries.map { it.fileName.toString() }.toList()
            assertEquals(1, names.size)
            assertTrue(names.single().endsWith(".tmp"))
        }
        assertTrue(catalog(dataRoot = dataRoot).loadPersisted().isEmpty())
    }

    @Test
    fun `load 중 실제 directory replacement는 persisted profile을 신뢰하지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val displacedDirectory = dataRoot.resolve("profiles-displaced")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trustedKeys = mapOf("test-key" to pair.public)
        catalog(
            dataRoot = dataRoot,
            trustedKeys = trustedKeys,
        ).importJson(signedEnvelope(pair).encodeToByteArray())
        var replaced = false
        val replacingCatalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = trustedKeys,
            persistenceCheckpoint = { checkpoint, directory ->
                if (
                    checkpoint == DesktopProfilePersistenceCheckpoint.LOAD_DIRECTORY_OPENED &&
                    !replaced
                ) {
                    Files.move(directory, displacedDirectory)
                    Files.createDirectory(directory)
                    replaced = true
                }
            },
        )

        assertTrue(replacingCatalog.loadPersisted().isEmpty())

        Files.list(dataRoot.resolve("profiles")).use { entries ->
            assertEquals(0, entries.count())
        }
        Files.list(displacedDirectory).use { entries ->
            assertEquals(1, entries.count())
        }
    }

    @Test
    fun `existing signed destination 변조는 재검증하고 덮어쓰지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val envelope = signedEnvelope(pair)
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
        )
        val imported = catalog.importJson(envelope.encodeToByteArray())
        val destination = dataRoot.resolve("profiles").resolve("${imported.id}.json")
        val original = Files.readAllBytes(destination)

        catalog.importJson(envelope.encodeToByteArray())
        assertContentEquals(original, Files.readAllBytes(destination))

        val tampered = replaceRecordField(
            original,
            "profileSha256",
            JsonPrimitive("0".repeat(64)),
        )
        Files.write(destination, tampered)

        assertFails {
            catalog.importJson(envelope.encodeToByteArray())
        }
        assertContentEquals(tampered, Files.readAllBytes(destination))
        assertTrue(catalog.loadPersisted().isEmpty())
    }

    @Test
    fun `move 직전 나타난 final은 덮어쓰지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val destinationName = "${profile.canonicalSha256()}.json"
        val occupied = "occupied".encodeToByteArray()
        var occupiedDestination = false
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            persistenceCheckpoint = { checkpoint, directory ->
                if (
                    checkpoint == DesktopProfilePersistenceCheckpoint.PERSIST_BEFORE_MOVE &&
                    !occupiedDestination
                ) {
                    Files.write(directory.resolve(destinationName), occupied, CREATE_NEW, WRITE)
                    occupiedDestination = true
                }
            },
        )

        assertFails {
            catalog.importJson(signedEnvelope(pair).encodeToByteArray())
        }

        val profilesDirectory = dataRoot.resolve("profiles")
        assertContentEquals(occupied, Files.readAllBytes(profilesDirectory.resolve(destinationName)))
        Files.list(profilesDirectory).use { entries ->
            assertEquals(1, entries.filter { it.fileName.toString().endsWith(".tmp") }.count())
        }
        assertTrue(catalog.loadPersisted().isEmpty())
    }

    @Test
    fun `persisted signed metadata와 envelope 변조는 거부하고 파일을 수정하지 않는다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
        )
        val envelope = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "test-key",
            pair.private,
        )
        catalog.importJson(envelope.encodeToByteArray())
        val path = onlyPersistedPath(dataRoot)
        val original = Files.readAllBytes(path)
        val metadataTampered = replaceRecordField(
            original,
            "profileSha256",
            JsonPrimitive("0".repeat(64)),
        )

        Files.write(path, metadataTampered)
        assertTrue(catalog.loadPersisted().isEmpty())
        assertContentEquals(metadataTampered, Files.readAllBytes(path))

        val envelopeTampered = replaceRecordBase64(original, "envelopeBase64") { encoded ->
            val decoded = Base64.getDecoder().decode(encoded).decodeToString()
            val signature = decoded.substringAfter("\"signatureBase64\":\"").substringBefore('"')
            val changed = "${if (signature.first() == 'A') 'B' else 'A'}${signature.drop(1)}"
            Base64.getEncoder().encodeToString(
                decoded.replace(signature, changed).encodeToByteArray(),
            )
        }
        Files.write(path, envelopeTampered)
        assertTrue(catalog.loadPersisted().isEmpty())
        assertContentEquals(envelopeTampered, Files.readAllBytes(path))
    }

    @Test
    fun `persisted APK profile과 partial record는 다시 불러오지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val catalog = catalog(
            dataRoot = dataRoot,
            extract = { profile.copy(supportedFeatures = setOf("storage.local")) },
        )
        catalog.importApk(Files.createTempFile("supported-sbrowser", ".apk"))
        val path = onlyPersistedPath(dataRoot)
        val original = Files.readAllBytes(path)
        val profileTampered = replaceRecordBase64(original, "profileBase64") { encoded ->
            val decoded = Base64.getDecoder().decode(encoded).decodeToString()
            Base64.getEncoder().encodeToString(
                decoded.replace(
                    "\"versionName\": \"30.0.2.61\"",
                    "\"versionName\": \"30.0.2.62\"",
                ).encodeToByteArray(),
            )
        }

        Files.write(path, profileTampered)
        assertTrue(catalog.loadPersisted().isEmpty())
        assertContentEquals(profileTampered, Files.readAllBytes(path))

        val partial = dataRoot.resolve("profiles").resolve("${"0".repeat(64)}.json")
        Files.writeString(partial, "{\"profileSha256\":")
        assertTrue(catalog.loadPersisted().isEmpty())
        assertEquals("{\"profileSha256\":", Files.readString(partial))
    }

    @Test
    fun `persisted IO는 growth bound와 short write를 끝까지 검증한다`() {
        assertFailsWith<IllegalArgumentException> {
            DesktopProfileFileIo.readBounded(
                Channels.newChannel(ByteArrayInputStream(ByteArray(33))),
                32,
            )
        }

        val payload = ByteArray(31) { it.toByte() }
        val channel = ShortWritingChannel(maxWrite = 3)
        DesktopProfileFileIo.writeFully(channel, payload)
        assertContentEquals(payload, channel.bytes())
    }

    @Test
    fun `public catalog constructor는 data root만 받는다`() {
        val publicArities = DesktopProfileCatalog::class.java.constructors
            .filterNot { it.isSynthetic }
            .map { it.parameterCount }

        assertEquals(listOf(1), publicArities)
    }

    @Test
    fun `bundled DER 공개 키는 고정 key id에 매핑한다`() {
        val keys = loadBundledTrustedProfileKeys(javaClass.classLoader)
        val resource = checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "profiles/trusted/profile-signing-2026-01.der",
            ),
        ).use { it.readBytes() }

        assertEquals(setOf("profile-signing-2026-01"), keys.keys)
        assertTrue(keys.getValue("profile-signing-2026-01").algorithm in setOf("Ed25519", "EdDSA"))
        assertContentEquals(resource, keys.getValue("profile-signing-2026-01").encoded)
    }

    private fun catalog(
        dataRoot: Path = Files.createTempDirectory("six-bridge-catalog"),
        trustedKeys: Map<String, java.security.PublicKey> = emptyMap(),
        extract: (Path) -> BrowserProfile = { profile.copy(versionName = "31.0.0.1") },
        persistenceCheckpoint: (DesktopProfilePersistenceCheckpoint, Path) -> Unit = { _, _ -> },
    ) = DesktopProfileCatalog.testing(
        dataRoot,
        trustedKeys,
        extract,
        persistenceCheckpoint = persistenceCheckpoint,
    )

    private fun signedEnvelope(pair: KeyPair): String = SignedBrowserProfile.sign(
        BrowserProfileJson.encode(profile).encodeToByteArray(),
        "test-key",
        pair.private,
    )

    private fun onlyPersistedPath(dataRoot: Path): Path =
        Files.list(dataRoot.resolve("profiles")).use { entries -> entries.toList().single() }

    private fun replaceRecordField(
        encoded: ByteArray,
        name: String,
        replacement: JsonPrimitive,
    ): ByteArray {
        val original = json.parseToJsonElement(encoded.decodeToString()).jsonObject
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject((original + (name to replacement)).toSortedMap()),
        ).encodeToByteArray()
    }

    private fun replaceRecordBase64(
        encoded: ByteArray,
        name: String,
        transform: (String) -> String,
    ): ByteArray {
        val original = json.parseToJsonElement(encoded.decodeToString()).jsonObject
        return replaceRecordField(
            encoded,
            name,
            JsonPrimitive(transform(original.getValue(name).jsonPrimitive.content)),
        )
    }

    private class ShortWritingChannel(
        private val maxWrite: Int,
    ) : WritableByteChannel {
        private val output = ByteArrayOutputStream()
        private var open = true

        override fun write(source: ByteBuffer): Int {
            val count = minOf(maxWrite, source.remaining())
            val bytes = ByteArray(count)
            source.get(bytes)
            output.write(bytes)
            return count
        }

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
