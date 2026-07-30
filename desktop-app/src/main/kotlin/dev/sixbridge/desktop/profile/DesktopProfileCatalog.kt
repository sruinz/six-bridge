package dev.sixbridge.desktop.profile

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileExtractor
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.ProfileSupport
import dev.sixbridge.core.profile.SignedBrowserProfile
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.name

enum class DesktopProfileTrust {
    BUILT_IN,
    SIGNED,
    APK_EXTRACTED_SUPPORTED,
    UNTRUSTED,
}

data class DesktopProfileOption(
    val id: String,
    val profile: BrowserProfile?,
    val trust: DesktopProfileTrust,
    val selectable: Boolean,
    val explanation: String,
)

class DesktopProfileCatalog(
    private val dataRoot: Path,
    private val trustedKeys: Map<String, PublicKey> = bundledTrustedKeys(),
    private val extractProfile: (Path) -> BrowserProfile = BrowserProfileExtractor()::extract,
    private val resources: ClassLoader = DesktopProfileCatalog::class.java.classLoader,
) {
    fun builtIns(): List<DesktopProfileOption> = builtInProfiles().map(::builtInOption)

    fun default(): DesktopProfileOption = builtIns().maxWith(
        compareBy<DesktopProfileOption> { versionKey(it.profile!!.versionName) }
            .thenBy { it.profile!!.versionCode },
    )

    fun importJson(bytes: ByteArray): DesktopProfileOption {
        val text = runCatching { bytes.decodeToString() }.getOrNull() ?: return untrusted()
        val signed = runCatching { SignedBrowserProfile.verify(text, trustedKeys) }.getOrNull()
        if (signed != null) {
            val option = signedOption(signed.profile, DesktopProfileTrust.SIGNED)
            persistSigned(option.profile!!, text)
            return option
        }
        val profile = runCatching { BrowserProfileJson.decode(text) }.getOrNull() ?: return untrusted()
        return builtInProfiles().firstOrNull { it.canonicalSha256() == profile.canonicalSha256() }
            ?.let(::builtInOption)
            ?: untrusted(profile)
    }

    fun importApk(path: Path): DesktopProfileOption {
        val profile = runCatching { extractProfile(path) }.getOrNull() ?: return untrusted()
        if (profile.support != ProfileSupport.SUPPORTED) return untrusted(profile)
        val option = signedOption(profile, DesktopProfileTrust.APK_EXTRACTED_SUPPORTED)
        persistExtracted(profile)
        return option
    }

    fun loadPersisted(): List<DesktopProfileOption> {
        val directory = profilesDirectory(create = false) ?: return emptyList()
        return Files.newDirectoryStream(directory).use { entries ->
            entries.mapNotNull { path -> loadPersisted(path) }.toList()
        }
    }

    private fun loadPersisted(path: Path): DesktopProfileOption? = runCatching {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "profile 저장 파일은 일반 파일이어야 합니다." }
        val fileName = path.name
        val profileSha256 = fileName.removeSuffix(".json")
        require(fileName == "$profileSha256.json" && SHA256.matches(profileSha256)) {
            "profile 저장 파일명이 올바르지 않습니다."
        }
        val record = decodeRecord(readBounded(path))
        require(record.profileSha256 == profileSha256) { "profile 저장 파일명 hash가 일치하지 않습니다." }
        when (record.trust) {
            DesktopProfileTrust.SIGNED -> {
                val verified = SignedBrowserProfile.verify(
                    record.envelopeBase64!!.decodeCanonicalBase64(),
                    trustedKeys,
                )
                require(verified.profile.canonicalSha256() == profileSha256) {
                    "서명 profile canonical hash가 일치하지 않습니다."
                }
                signedOption(verified.profile, DesktopProfileTrust.SIGNED)
            }
            DesktopProfileTrust.APK_EXTRACTED_SUPPORTED -> {
                val profile = BrowserProfileJson.decode(
                    record.profileBase64!!.decodeCanonicalBase64(),
                )
                require(profile.canonicalSha256() == profileSha256) {
                    "APK profile canonical hash가 일치하지 않습니다."
                }
                require(profile.support == ProfileSupport.SUPPORTED) {
                    "지원하지 않는 APK profile은 저장할 수 없습니다."
                }
                signedOption(profile, DesktopProfileTrust.APK_EXTRACTED_SUPPORTED)
            }
            else -> throw IllegalArgumentException("지원하지 않는 profile trust입니다.")
        }
    }.getOrNull()

    private fun persistSigned(profile: BrowserProfile, envelope: String) {
        persist(
            profile,
            PersistedProfile(
                trust = DesktopProfileTrust.SIGNED,
                profileSha256 = profile.canonicalSha256(),
                envelopeBase64 = Base64.getEncoder().encodeToString(envelope.encodeToByteArray()),
            ),
        )
    }

    private fun persistExtracted(profile: BrowserProfile) {
        persist(
            profile,
            PersistedProfile(
                trust = DesktopProfileTrust.APK_EXTRACTED_SUPPORTED,
                profileSha256 = profile.canonicalSha256(),
                profileBase64 = Base64.getEncoder().encodeToString(
                    BrowserProfileJson.encode(profile).encodeToByteArray(),
                ),
            ),
        )
    }

    private fun persist(profile: BrowserProfile, record: PersistedProfile) {
        val profileSha256 = profile.canonicalSha256()
        require(record.profileSha256 == profileSha256)
        val directory = profilesDirectory(create = true) ?: return
        val destination = directory.resolve("$profileSha256.json")
        require(destination.parent == directory && !Files.exists(destination, NOFOLLOW_LINKS) ||
            Files.isRegularFile(destination, NOFOLLOW_LINKS)) {
            "profile 저장 경로가 올바르지 않습니다."
        }
        if (Files.exists(destination, NOFOLLOW_LINKS)) return
        val temporary = Files.createTempFile(directory, "$profileSha256.", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                channel.write(java.nio.ByteBuffer.wrap(encodeRecord(record)))
                channel.force(true)
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("profile 저장소는 atomic move를 지원해야 합니다.", error)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun profilesDirectory(create: Boolean): Path? {
        val root = dataRoot.toAbsolutePath().normalize()
        if (create) Files.createDirectories(root)
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return null
        val directory = root.resolve("profiles")
        if (create) createDirectoryIfMissing(directory)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) return null
        return directory
    }

    private fun createDirectoryIfMissing(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
    }

    private fun readBounded(path: Path): ByteArray {
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() in 1..MAX_PERSISTED_BYTES) { "profile 저장 파일 크기가 올바르지 않습니다." }
        }
        return Files.readAllBytes(path)
    }

    private fun builtInProfiles(): List<BrowserProfile> = BUILT_IN_RESOURCES.map { resource ->
        val bytes = resources.getResourceAsStream(resource)?.use { input -> input.readBytes() }
            ?: throw IllegalStateException("내장 browser profile resource가 없습니다: $resource")
        BrowserProfileJson.decode(bytes.decodeToString()).also(BrowserProfile::canonicalSha256)
    }

    private fun builtInOption(profile: BrowserProfile): DesktopProfileOption = DesktopProfileOption(
        id = profile.canonicalSha256(),
        profile = profile,
        trust = DesktopProfileTrust.BUILT_IN,
        selectable = true,
        explanation = "내장 검증 Samsung Internet profile",
    )

    private fun signedOption(
        profile: BrowserProfile,
        trust: DesktopProfileTrust,
    ): DesktopProfileOption = DesktopProfileOption(
        id = profile.canonicalSha256(),
        profile = profile,
        trust = trust,
        selectable = true,
        explanation = when (trust) {
            DesktopProfileTrust.SIGNED -> "배포 공개 키로 서명 검증한 browser profile"
            DesktopProfileTrust.APK_EXTRACTED_SUPPORTED -> "지원 Samsung Internet APK에서 로컬 추출한 profile"
            else -> error("선택 가능한 trust가 아닙니다.")
        },
    )

    private fun untrusted(profile: BrowserProfile? = null): DesktopProfileOption = DesktopProfileOption(
        id = profile?.canonicalSha256() ?: "untrusted",
        profile = profile,
        trust = DesktopProfileTrust.UNTRUSTED,
        selectable = false,
        explanation = "검증되지 않은 browser profile은 선택할 수 없습니다.",
    )

    private data class PersistedProfile(
        val trust: DesktopProfileTrust,
        val profileSha256: String,
        val envelopeBase64: String? = null,
        val profileBase64: String? = null,
    )

    private fun encodeRecord(record: PersistedProfile): ByteArray {
        val fields = linkedMapOf<String, JsonPrimitive>(
            "profileSha256" to JsonPrimitive(record.profileSha256),
            "schemaVersion" to JsonPrimitive(1),
            "trust" to JsonPrimitive(record.trust.name),
        )
        record.envelopeBase64?.let { fields["envelopeBase64"] = JsonPrimitive(it) }
        record.profileBase64?.let { fields["profileBase64"] = JsonPrimitive(it) }
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(fields.toSortedMap()),
        ).encodeToByteArray()
    }

    private fun decodeRecord(bytes: ByteArray): PersistedProfile {
        val text = bytes.decodeToString()
        val value = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("profile 저장 값은 JSON object여야 합니다.")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("profile 저장 JSON을 읽을 수 없습니다.", error)
        }
        require(bytes.contentEquals(json.encodeToString(JsonObject.serializer(), JsonObject(value.toSortedMap())).encodeToByteArray())) {
            "profile 저장 JSON은 중복 키가 없고 canonical 형식이어야 합니다."
        }
        val trust = value.string("trust").let { enumValueOf<DesktopProfileTrust>(it) }
        val expected = when (trust) {
            DesktopProfileTrust.SIGNED -> SIGNED_RECORD_FIELDS
            DesktopProfileTrust.APK_EXTRACTED_SUPPORTED -> APK_RECORD_FIELDS
            else -> throw IllegalArgumentException("지원하지 않는 profile trust입니다.")
        }
        require(value.keys == expected) { "profile 저장 schema 항목이 정확하지 않습니다." }
        require(value.int("schemaVersion") == 1) { "지원하지 않는 profile 저장 schemaVersion입니다." }
        val hash = value.string("profileSha256")
        require(SHA256.matches(hash)) { "profile 저장 hash가 올바르지 않습니다." }
        return PersistedProfile(
            trust = trust,
            profileSha256 = hash,
            envelopeBase64 = value["envelopeBase64"]?.let { value.string("envelopeBase64") },
            profileBase64 = value["profileBase64"]?.let { value.string("profileBase64") },
        )
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.also { require(it.isString) { "$name 값은 문자열이어야 합니다." } }
            .content.also { require(it.isNotBlank()) { "$name 값은 비어 있을 수 없습니다." } }

    private fun JsonObject.int(name: String): Int =
        getValue(name).jsonPrimitive.also {
            require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]*"))) {
                "$name 값은 canonical JSON 정수여야 합니다."
            }
        }.content.toInt()

    private fun String.decodeCanonicalBase64(): String {
        val decoded = Base64.getDecoder().decode(this)
        require(Base64.getEncoder().encodeToString(decoded) == this) { "profile 저장 Base64가 canonical이 아닙니다." }
        return decoded.decodeToString()
    }

    private fun versionKey(value: String): String =
        value.split('.').joinToString(".") { "%08d".format(it.toIntOrNull() ?: -1) }

    companion object {
        private const val MAX_PERSISTED_BYTES = 1_048_576
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val BUILT_IN_RESOURCES = listOf(
            "profiles/builtin/sbrowser-30.0.0.67.json",
            "profiles/builtin/sbrowser-30.0.2.61.json",
        )
        private val SIGNED_RECORD_FIELDS = setOf(
            "schemaVersion", "trust", "profileSha256", "envelopeBase64",
        )
        private val APK_RECORD_FIELDS = setOf(
            "schemaVersion", "trust", "profileSha256", "profileBase64",
        )

        private val json = Json

        private fun bundledTrustedKeys(): Map<String, PublicKey> {
            val key = DesktopProfileCatalog::class.java.classLoader
                .getResourceAsStream("profiles/trusted/profile-signing-2026-01.der")
                ?.use { input ->
                    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(input.readBytes()))
                }
                ?: throw IllegalStateException("내장 profile signing 공개 키가 없습니다.")
            return mapOf("profile-signing-2026-01" to key)
        }
    }
}
