package dev.sixbridge.desktop.profile

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileExtractor
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.ProfileSupport
import dev.sixbridge.core.profile.SignedBrowserProfile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
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

class DesktopProfileCatalog private constructor(
    private val dataRoot: Path,
    private val trustedKeys: Map<String, PublicKey>,
    private val extractProfile: (Path) -> BrowserProfile,
    private val resources: ClassLoader,
) {
    constructor(dataRoot: Path) : this(
        dataRoot = dataRoot,
        trustedKeys = loadBundledTrustedProfileKeys(DesktopProfileCatalog::class.java.classLoader),
        extractProfile = BrowserProfileExtractor()::extract,
        resources = DesktopProfileCatalog::class.java.classLoader,
    )

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
        val record = decodeRecord(
            DesktopProfileFileIo.readBounded(path, MAX_PERSISTED_BYTES),
        )
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
        val directoryFileKey = fileKey(directory)
        val destination = directory.resolve("$profileSha256.json")
        require(
            destination.parent == directory &&
                (
                    !Files.exists(destination, NOFOLLOW_LINKS) ||
                        Files.isRegularFile(destination, NOFOLLOW_LINKS)
                    ),
        ) {
            "profile 저장 경로가 올바르지 않습니다."
        }
        if (Files.exists(destination, NOFOLLOW_LINKS)) return
        val temporary = directory.resolve(".$profileSha256.${UUID.randomUUID()}.tmp")
        var temporaryFileKey: Any? = null
        try {
            val encoded = encodeRecord(record)
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                temporaryFileKey = fileKey(temporary)
                DesktopProfileFileIo.writeFully(channel, encoded)
                require(channel.size() == encoded.size.toLong()) {
                    "profile 임시 파일이 완전히 기록되지 않았습니다."
                }
                channel.force(true)
            }
            val temporaryAttributes = fileAttributes(temporary)
            require(
                temporaryAttributes.isRegularFile &&
                    temporaryAttributes.fileKey() == temporaryFileKey &&
                    temporaryAttributes.size() == encoded.size.toLong(),
            ) {
                "profile 임시 파일이 게시 전에 변경되었습니다."
            }
            require(fileKey(directory) == directoryFileKey) {
                "profile 저장 디렉터리가 기록 중 변경되었습니다."
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IllegalStateException("profile 저장소는 atomic move를 지원해야 합니다.", error)
            }
            require(fileKey(directory) == directoryFileKey) {
                "profile 저장 디렉터리가 게시 중 변경되었습니다."
            }
            val destinationAttributes = fileAttributes(destination)
            require(
                destinationAttributes.isRegularFile &&
                    destinationAttributes.fileKey() == temporaryFileKey &&
                    destinationAttributes.size() == encoded.size.toLong(),
            ) {
                "profile 최종 파일이 게시 중 변경되었습니다."
            }
        } finally {
            deleteOwnedTemporary(
                temporary,
                directory,
                directoryFileKey,
                temporaryFileKey,
            )
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

        internal fun testing(
            dataRoot: Path,
            trustedKeys: Map<String, PublicKey> = emptyMap(),
            extractProfile: (Path) -> BrowserProfile = BrowserProfileExtractor()::extract,
            resources: ClassLoader = DesktopProfileCatalog::class.java.classLoader,
        ): DesktopProfileCatalog = DesktopProfileCatalog(
            dataRoot,
            trustedKeys,
            extractProfile,
            resources,
        )
    }
}

internal fun loadBundledTrustedProfileKeys(resources: ClassLoader): Map<String, PublicKey> {
    val key = resources
        .getResourceAsStream("profiles/trusted/profile-signing-2026-01.der")
        ?.use { input ->
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(input.readBytes()))
        }
        ?: throw IllegalStateException("내장 profile signing 공개 키가 없습니다.")
    return mapOf("profile-signing-2026-01" to key)
}

internal object DesktopProfileFileIo {
    fun readBounded(path: Path, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && before.fileKey() != null) {
            "profile 저장 파일은 identity가 있는 일반 파일이어야 합니다."
        }
        require(before.size() in 1..maxBytes.toLong()) {
            "profile 저장 파일 크기가 올바르지 않습니다."
        }
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() in 1..maxBytes.toLong()) {
                "profile 저장 파일 크기가 올바르지 않습니다."
            }
            val bytes = readBounded(channel, maxBytes)
            require(channel.size() == bytes.size.toLong()) {
                "profile 저장 파일 크기가 읽는 중 변경되었습니다."
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(
                after.isRegularFile &&
                    after.fileKey() == before.fileKey() &&
                    before.size() == bytes.size.toLong() &&
                    after.size() == bytes.size.toLong(),
            ) {
                "profile 저장 파일이 읽는 중 변경되었습니다."
            }
            bytes
        }
    }

    fun readBounded(channel: ReadableByteChannel, maxBytes: Int): ByteArray {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteBuffer.allocate(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) break
            require(read > 0) { "profile 저장 파일을 끝까지 읽을 수 없습니다." }
            require(output.size().toLong() + read <= maxBytes.toLong()) {
                "profile 저장 파일 크기가 제한을 초과합니다."
            }
            output.write(buffer.array(), 0, read)
        }
        require(output.size() > 0) { "profile 저장 파일이 비어 있습니다." }
        return output.toByteArray()
    }

    fun writeFully(channel: WritableByteChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            require(channel.write(buffer) > 0) {
                "profile 임시 파일을 끝까지 기록할 수 없습니다."
            }
        }
    }
}

private fun fileKey(path: Path): Any =
    requireNotNull(
        fileAttributes(path).fileKey(),
    ) {
        "profile 저장 경로 identity를 확인할 수 없습니다."
    }

private fun fileAttributes(path: Path): BasicFileAttributes =
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

private fun deleteOwnedTemporary(
    temporary: Path,
    directory: Path,
    expectedDirectoryFileKey: Any,
    expectedTemporaryFileKey: Any?,
) {
    if (expectedTemporaryFileKey == null || !Files.exists(temporary, NOFOLLOW_LINKS)) return
    val directoryAttributes = runCatching { fileAttributes(directory) }.getOrNull() ?: return
    val temporaryAttributes = runCatching { fileAttributes(temporary) }.getOrNull() ?: return
    if (
        directoryAttributes.isDirectory &&
        directoryAttributes.fileKey() == expectedDirectoryFileKey &&
        temporaryAttributes.isRegularFile &&
        temporaryAttributes.fileKey() == expectedTemporaryFileKey
    ) {
        Files.deleteIfExists(temporary)
    }
}
