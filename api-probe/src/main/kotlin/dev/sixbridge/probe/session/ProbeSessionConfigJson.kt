package dev.sixbridge.probe.session

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProbeSessionConfigJson {
    private val json = Json { prettyPrint = true }
    private val rootFields = setOf(
        "schemaVersion", "sessionId", "catalogSha256", "browserPackage",
        "browserVersionName", "browserVersionCode", "browserApkSetSha256",
        "browserSignerSha256", "bindings",
    )
    private val bindingFields = setOf("groupId", "extensionId", "wrapperSignerSha256")
    private val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    fun encode(config: ProbeSessionConfig): String {
        config.validate()
        val value = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(config.schemaVersion),
            "sessionId" to JsonPrimitive(config.sessionId),
            "catalogSha256" to JsonPrimitive(config.catalogSha256),
            "browserPackage" to JsonPrimitive(config.browserPackage),
            "browserVersionName" to JsonPrimitive(config.browserVersionName),
            "browserVersionCode" to JsonPrimitive(config.browserVersionCode),
            "browserApkSetSha256" to JsonPrimitive(config.browserApkSetSha256),
            "browserSignerSha256" to JsonPrimitive(config.browserSignerSha256),
            "bindings" to JsonArray(config.bindings.sortedBy { it.groupId }.map { binding ->
                JsonObject(linkedMapOf(
                    "groupId" to JsonPrimitive(binding.groupId),
                    "extensionId" to JsonPrimitive(binding.extensionId),
                    "wrapperSignerSha256" to JsonPrimitive(binding.wrapperSignerSha256),
                ))
            }),
        ))
        return json.encodeToString(JsonObject.serializer(), value) + "\n"
    }

    fun decodeStrict(value: String): ProbeSessionConfig {
        require(value.toByteArray(UTF_8).size <= MAX_BYTES) { "probe session config가 너무 큽니다." }
        rejectDuplicateObjectKeys(value)
        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("probe session config JSON을 읽을 수 없습니다.", error)
        }
        require(root.keys == rootFields) { "probe session config field가 정확하지 않습니다." }
        val config = ProbeSessionConfig(
            schemaVersion = root.int("schemaVersion"),
            sessionId = root.string("sessionId"),
            catalogSha256 = root.string("catalogSha256"),
            browserPackage = root.string("browserPackage"),
            browserVersionName = root.string("browserVersionName"),
            browserVersionCode = root.long("browserVersionCode"),
            browserApkSetSha256 = root.string("browserApkSetSha256"),
            browserSignerSha256 = root.string("browserSignerSha256"),
            bindings = root.required("bindings").jsonArray.map { element ->
                val binding = element.jsonObject
                require(binding.keys == bindingFields) { "probe session binding field가 정확하지 않습니다." }
                ProbeSessionBinding(
                    groupId = binding.string("groupId"),
                    extensionId = binding.string("extensionId"),
                    wrapperSignerSha256 = binding.string("wrapperSignerSha256"),
                )
            },
        ).also(ProbeSessionConfig::validate)
        require(encode(config) == value) { "probe session config는 canonical JSON이어야 합니다." }
        return config
    }

    fun readStrict(path: Path): ProbeSessionConfig {
        requirePosix(path)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "probe session config는 symlink가 아닌 regular file이어야 합니다."
        }
        require(Files.getPosixFilePermissions(path) == ownerOnly) {
            "probe session config permission은 0600이어야 합니다."
        }
        val size = Files.size(path)
        require(size in 1..MAX_BYTES.toLong()) { "probe session config 크기가 올바르지 않습니다." }
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size.toLong() == size) { "probe session config가 읽는 중 변경되었습니다." }
        return decodeStrict(bytes.toString(UTF_8))
    }

    fun writeNew(path: Path, config: ProbeSessionConfig) {
        val target = path.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "probe session output에 parent가 필요합니다." }
        Files.createDirectories(parent)
        requirePosix(parent)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "probe session output parent는 symlink가 아닌 directory여야 합니다."
        }
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "probe session output은 존재하지 않아야 합니다." }
        val bytes = encode(config).toByteArray(UTF_8)
        val temporary = Files.createTempFile(parent, ".${target.fileName}.tmp-", ".json")
        var moved = false
        try {
            Files.setPosixFilePermissions(temporary, ownerOnly)
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            require(readStrict(temporary) == config) { "probe session temp self-verification에 실패했습니다." }
            try {
                Files.move(temporary, target)
            } catch (error: FileAlreadyExistsException) {
                throw IllegalArgumentException("probe session output이 publication 중 생성되었습니다.", error)
            }
            moved = true
            try {
                FileChannel.open(parent, READ, NOFOLLOW_LINKS).use { it.force(true) }
            } catch (error: Exception) {
                Files.deleteIfExists(target)
                runCatching { FileChannel.open(parent, READ, NOFOLLOW_LINKS).use { it.force(true) } }
                throw error
            }
            require(readStrict(target) == config) { "probe session publication self-verification에 실패했습니다." }
        } finally {
            Files.deleteIfExists(temporary)
            if (!moved) require(!Files.exists(target, NOFOLLOW_LINKS) || Files.isRegularFile(target, NOFOLLOW_LINKS))
        }
    }

    private fun requirePosix(path: Path) {
        require(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            "probe session config는 POSIX 0600 permission을 검증할 수 있는 filesystem에서만 지원합니다."
        }
    }

    private fun JsonObject.required(name: String) = this[name]
        ?: throw IllegalArgumentException("$name field가 없습니다.")

    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.also {
        require(it.isString) { "$name field는 string이어야 합니다." }
    }.content

    private fun JsonObject.int(name: String): Int = required(name).jsonPrimitive.also {
        require(!it.isString) { "$name field는 integer여야 합니다." }
    }.content.toIntOrNull() ?: throw IllegalArgumentException("$name field는 integer여야 합니다.")

    private fun JsonObject.long(name: String): Long = required(name).jsonPrimitive.also {
        require(!it.isString) { "$name field는 integer여야 합니다." }
    }.content.toLongOrNull() ?: throw IllegalArgumentException("$name field는 integer여야 합니다.")

    private fun rejectDuplicateObjectKeys(value: String) {
        val stack = ArrayDeque<MutableSet<String>?>()
        var stringStart = -1
        var escaped = false
        var depth = 0
        value.forEachIndexed { index, char ->
            if (stringStart >= 0) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') {
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next += 1
                    val keys = stack.lastOrNull()
                    if (next < value.length && value[next] == ':' && keys != null) {
                        val key = json.decodeFromString<String>(value.substring(stringStart, index + 1))
                        require(keys.add(key)) { "probe session config에 duplicate JSON field가 있습니다." }
                    }
                    stringStart = -1
                }
            } else when (char) {
                '"' -> stringStart = index
                '{' -> { depth += 1; require(depth <= 32); stack.addLast(linkedSetOf()) }
                '[' -> { depth += 1; require(depth <= 32); stack.addLast(null) }
                '}', ']' -> { require(depth > 0 && stack.isNotEmpty()); depth -= 1; stack.removeLast() }
            }
        }
        require(stringStart < 0 && depth == 0 && stack.isEmpty()) { "probe session config JSON structure가 올바르지 않습니다." }
    }

    private const val MAX_BYTES = 64 * 1024
}
