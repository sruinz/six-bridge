package dev.sixbridge.core.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ExtensionZip {
    fun create(sourceRoot: Path, output: Path, publicKey: ByteArray? = null) {
        Files.write(output, createBytes(sourceRoot, publicKey, includeProjectMarker = true))
    }

    fun createBytes(
        sourceRoot: Path,
        publicKey: ByteArray? = null,
        includeProjectMarker: Boolean = false,
    ): ByteArray {
        require(sourceRoot.isDirectory()) { "확장 소스 디렉터리가 아닙니다: $sourceRoot" }
        require(sourceRoot.resolve("manifest.json").isRegularFile()) {
            "manifest.json 파일이 없습니다: $sourceRoot"
        }
        val paths = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { path ->
                    shouldInclude(sourceRoot.relativize(path), includeProjectMarker)
                }
                .sorted(compareBy { sourceRoot.relativize(it).invariantSeparatorsPathString })
                .toList()
        }
        require(paths.size <= MAX_ENTRIES) { "ZIP 항목 수가 제한을 초과합니다." }
        var totalSize = 0L
        paths.forEach { path ->
            val size = Files.size(path)
            require(size <= MAX_ENTRY_SIZE) { "ZIP 개별 파일 크기가 제한을 초과합니다: $path" }
            totalSize += size
            require(totalSize <= MAX_TOTAL_SIZE) { "ZIP 전체 크기가 제한을 초과합니다." }
        }
        return ByteArrayOutputStream().run {
            ZipOutputStream(this).use { zip ->
                paths.forEach { path ->
                    val relative = sourceRoot.relativize(path).invariantSeparatorsPathString
                    val entry = ZipEntry(relative).apply { time = 0L }
                    zip.putNextEntry(entry)
                    if (relative == "manifest.json" && publicKey != null) {
                        zip.write(manifestWithKey(path, publicKey))
                    } else {
                        Files.copy(path, zip)
                    }
                    zip.closeEntry()
                }
            }
            toByteArray()
        }
    }

    fun extract(bytes: ByteArray, destination: Path): Path {
        val temporary = Files.createTempFile("sixbridge-extension-bytes-", ".zip")
        return try {
            Files.write(temporary, bytes)
            extract(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun extract(zipPath: Path, destination: Path): Path {
        Files.createDirectories(destination)
        ZipFile(zipPath.toFile()).use { zip ->
            require(zip.size() <= MAX_ENTRIES) { "ZIP 항목 수가 제한을 초과합니다." }
            var declaredTotal = 0L
            val names = mutableSetOf<String>()
            zip.entries().asSequence().forEach { entry ->
                require(names.add(entry.name)) {
                    "ZIP 중복 항목을 거부했습니다: ${entry.name}"
                }
                validateEntry(entry)
                if (!entry.isDirectory) {
                    declaredTotal += entry.size
                    require(declaredTotal <= MAX_TOTAL_SIZE) {
                        "ZIP 전체 압축 해제 크기가 제한을 초과합니다."
                    }
                }
            }
            var totalSize = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val output = destination.resolve(entry.name).normalize()
                if (!output.startsWith(destination)) {
                    throw IllegalArgumentException("ZIP 경로 탈출 항목을 거부했습니다: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    zip.getInputStream(entry).use { input ->
                        totalSize += copyLimited(input, output)
                        require(totalSize <= MAX_TOTAL_SIZE) {
                            "ZIP 전체 압축 해제 크기가 제한을 초과합니다."
                        }
                    }
                }
            }
        }
        return locateManifestRoot(destination)
    }

    private fun manifestWithKey(manifestPath: Path, publicKey: ByteArray): ByteArray {
        val json = Json { prettyPrint = true }
        val manifest = json.parseToJsonElement(manifestPath.readText()) as JsonObject
        val withKey = JsonObject(
            manifest + ("key" to JsonPrimitive(Base64.getEncoder().encodeToString(publicKey))),
        )
        return json.encodeToString(JsonObject.serializer(), withKey).toByteArray()
    }

    private fun shouldInclude(relative: Path, includeProjectMarker: Boolean): Boolean {
        val components = relative.map { it.toString() }
        if (components.any { it in EXCLUDED_DIRECTORIES }) return false
        if (!includeProjectMarker &&
            relative.fileName.toString() == SourceProjectIdentityStore.MARKER_FILE
        ) {
            return false
        }
        return relative.fileName.toString() !in EXCLUDED_FILES
    }

    private fun locateManifestRoot(extracted: Path): Path {
        if (extracted.resolve("manifest.json").isRegularFile()) return extracted
        val candidates = Files.walk(extracted, 2).use { stream ->
            stream.filter { it.fileName.toString() == "manifest.json" && it.isRegularFile() }
                .map { it.parent }
                .toList()
        }
        require(candidates.size == 1) {
            "ZIP에서 단일 manifest.json 루트를 찾지 못했습니다."
        }
        return candidates.single()
    }

    private fun validateEntry(entry: ZipEntry) {
        if (!entry.isDirectory && entry.size >= 0) {
            require(entry.size <= MAX_ENTRY_SIZE) {
                "ZIP 개별 항목 크기가 제한을 초과합니다: ${entry.name}"
            }
        }
        if (entry.size > 0 && entry.compressedSize > 0) {
            require(entry.size.toDouble() / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                "ZIP 압축 비율이 제한을 초과합니다: ${entry.name}"
            }
        }
    }

    private fun copyLimited(input: InputStream, output: Path): Long {
        var size = 0L
        Files.newOutputStream(output).use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                require(size <= MAX_ENTRY_SIZE) {
                    "ZIP 개별 항목 크기가 제한을 초과합니다: $output"
                }
                target.write(buffer, 0, read)
            }
        }
        return size
    }

    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
    private val EXCLUDED_FILES = setOf(".DS_Store")
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_SIZE = 128L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 512L * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100.0
}
