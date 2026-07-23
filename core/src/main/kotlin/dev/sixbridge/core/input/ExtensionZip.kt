package dev.sixbridge.core.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

object ExtensionZip {
    fun create(sourceRoot: Path, output: Path, publicKey: ByteArray? = null) {
        Files.write(output, createBytes(sourceRoot, publicKey))
    }

    fun createBytes(sourceRoot: Path, publicKey: ByteArray? = null): ByteArray {
        require(sourceRoot.isDirectory()) { "확장 소스 디렉터리가 아닙니다: $sourceRoot" }
        require(sourceRoot.resolve("manifest.json").isRegularFile()) {
            "manifest.json 파일이 없습니다: $sourceRoot"
        }
        val paths = Files.walk(sourceRoot).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { path -> shouldInclude(sourceRoot.relativize(path)) }
                .sorted(compareBy { sourceRoot.relativize(it).invariantSeparatorsPathString })
                .toList()
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
        Files.createDirectories(destination)
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = destination.resolve(entry.name).normalize()
                if (!output.startsWith(destination)) {
                    throw IllegalArgumentException("ZIP 경로 탈출 항목을 거부했습니다: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    Files.copy(zip, output)
                }
            }
        }
        return locateManifestRoot(destination)
    }

    fun extract(zipPath: Path, destination: Path): Path {
        Files.createDirectories(destination)
        ZipFile(zipPath.toFile()).use { zip ->
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
                        Files.copy(input, output)
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

    private fun shouldInclude(relative: Path): Boolean {
        val components = relative.map { it.toString() }
        if (components.any { it in EXCLUDED_DIRECTORIES }) return false
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

    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
    private val EXCLUDED_FILES = setOf(".DS_Store")
}
