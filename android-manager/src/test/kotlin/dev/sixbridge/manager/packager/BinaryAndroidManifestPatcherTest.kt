package dev.sixbridge.manager.packager

import dev.sixbridge.manager.wrapper.BinaryAndroidManifestReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryAndroidManifestPatcherTest {
    private val templateManifest = ZipFile(File("src/main/assets/wrapper-template.apk")).use { apk ->
        apk.getInputStream(apk.getEntry("AndroidManifest.xml")).readBytes()
    }

    @Test
    fun `템플릿을 확장 ID와 versionCode로 구조 패치한다`() {
        val extensionId = "bcdefghijklmnopabcdefghijklmnopa"
        val original = templateManifest.copyOf()

        val patched = BinaryAndroidManifestPatcher().patch(
            templateManifest,
            WrapperManifestRequest(extensionId, 42),
        )

        val facts = BinaryAndroidManifestReader().read(patched)
        assertEquals("dev.sixbridge.ext.$extensionId", facts.packageName)
        assertEquals(42, facts.versionCode)
        assertEquals(30, facts.minSdkVersion)
        assertEquals(36, facts.targetSdkVersion)
        assertEquals(extensionId, facts.metadata.getValue("extension-id"))
        assertEquals("dev.sixbridge.ext.$extensionId.contentprovider", facts.providers.single().authority)
        assertContentEquals(original, templateManifest)
    }

    @Test
    fun `32자 a p 형식이 아닌 확장 ID를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(
                templateManifest,
                WrapperManifestRequest("z".repeat(32), 1),
            )
        }
    }

    @Test
    fun `0 이하 versionCode를 거부한다`() {
        listOf(0, -1).forEach { versionCode ->
            assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestPatcher().patch(
                    templateManifest,
                    WrapperManifestRequest("bcdefghijklmnopabcdefghijklmnopa", versionCode),
                )
            }
        }
    }

    @Test
    fun `extension ID placeholder 누락을 거부한다`() {
        val mutated = templateManifest.copyOf()
        replaceString(mutated, PLACEHOLDER_ID, "b".repeat(32))

        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(mutated, request())
        }
    }

    @Test
    fun `Provider placeholder 중복을 거부한다`() {
        val mutated = templateManifest.copyOf()
        duplicateStringValue(
            mutated,
            from = "platformBuildVersionCode",
            to = "dev.sixbridge.ext.$PLACEHOLDER_ID.contentprovider",
        )

        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(mutated, request())
        }
    }

    @Test
    fun `UTF 8 문자열 길이 변조를 거부한다`() {
        val utf8Template = convertStringPoolToUtf8(templateManifest)
        BinaryAndroidManifestPatcher().patch(utf8Template, request())
        val mutated = utf8Template.copyOf()
        val slot = stringSlots(mutated).single { it.value == PLACEHOLDER_ID }
        require(slot.utf8)
        mutated[slot.payloadOffset - 1] = (slot.payloadLength - 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(mutated, request())
        }
    }

    @Test
    fun `manifest package raw typed 불일치를 거부한다`() {
        val mutated = templateManifest.copyOf()
        val extensionIdIndex = stringSlots(mutated).single { it.value == PLACEHOLDER_ID }.index
        val packageAttribute = attributeOffset(mutated, elementName = "manifest", attributeName = "package")
        mutated.writeInt(packageAttribute + ATTRIBUTE_RAW_VALUE_OFFSET, extensionIdIndex)

        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(mutated, request())
        }
    }

    @Test
    fun `metadata와 Provider hierarchy 변조를 거부한다`() {
        listOf(
            moveElementInside(mutated = templateManifest, element = "meta-data", parent = "provider"),
            moveElementInside(mutated = templateManifest, element = "provider", parent = "activity"),
        ).forEach { mutated ->
            assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestPatcher().patch(mutated, request())
            }
        }
    }

    @Test
    fun `min target SDK 변조를 거부한다`() {
        val minSdk = templateManifest.copyOf()
        minSdk.writeInt(
            attributeOffset(minSdk, elementName = "uses-sdk", attributeName = "minSdkVersion") +
                ATTRIBUTE_TYPED_DATA_OFFSET,
            29,
        )
        val targetSdk = templateManifest.copyOf()
        targetSdk.writeInt(
            attributeOffset(targetSdk, elementName = "uses-sdk", attributeName = "targetSdkVersion") +
                ATTRIBUTE_TYPED_DATA_OFFSET,
            35,
        )

        listOf(minSdk, targetSdk).forEach { mutated ->
            assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestPatcher().patch(mutated, request())
            }
        }
    }

    @Test
    fun `service receiver와 값 없는 metadata 추가를 거부한다`() {
        val service = insertUnknownComponent(templateManifest, "service", "platformBuildVersionCode")
        val receiver = insertUnknownComponent(templateManifest, "receiver", "platformBuildVersionName")
        val metadataWithoutValue = insertMetadataWithoutAttributes(templateManifest)

        listOf(service, receiver, metadataWithoutValue).forEach { mutated ->
            assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestPatcher().patch(mutated, request())
            }
        }
    }

    @Test
    fun `namespace 종료 누락 순서 URI 불일치와 Android URI 위조를 거부한다`() {
        val missingEnd = removeNamespaceEnd(templateManifest)
        val endPrefixMismatch = mutateNamespacePrefix(templateManifest, END_NAMESPACE_TYPE, PLACEHOLDER_ID)
        val endUriMismatch = mutateNamespaceUri(templateManifest, END_NAMESPACE_TYPE, PLACEHOLDER_ID)
        val androidUriForgery = mutateNamespaceUri(templateManifest, START_NAMESPACE_TYPE, PLACEHOLDER_ID)

        listOf(missingEnd, endPrefixMismatch, endUriMismatch, androidUriForgery).forEach { mutated ->
            assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestPatcher().patch(mutated, request())
            }
        }
    }

    @Test
    fun `string pool offset 중복을 거부한다`() {
        val mutated = templateManifest.copyOf()
        duplicateStringValue(mutated, from = "1.0", to = "16")

        assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestPatcher().patch(mutated, request())
        }
    }

    private fun request() = WrapperManifestRequest(
        extensionId = "bcdefghijklmnopabcdefghijklmnopa",
        versionCode = 42,
    )

    private fun replaceString(bytes: ByteArray, from: String, to: String) {
        val slot = stringSlots(bytes).single { it.value == from }
        val replacement = to.toByteArray(if (slot.utf8) StandardCharsets.UTF_8 else StandardCharsets.UTF_16LE)
        require(replacement.size == slot.payloadLength)
        replacement.copyInto(bytes, slot.payloadOffset)
    }

    private fun duplicateStringValue(bytes: ByteArray, from: String, to: String) {
        val slots = stringSlots(bytes)
        val source = slots.single { it.value == to }
        val target = slots.single { it.value == from }
        bytes.writeInt(target.offsetTableEntry, source.relativeOffset)
    }

    private fun convertStringPoolToUtf8(bytes: ByteArray): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val stringPoolIndex = chunks.indexOfFirst { it.type == STRING_POOL_TYPE }
        require(stringPoolIndex >= 0)
        val original = chunks[stringPoolIndex].bytes
        val headerSize = original.u16(2)
        val count = original.u32(8)
        val styleCount = original.u32(12)
        val stringsStart = original.u32(20)
        require(styleCount == 0 && original.u32(16) and UTF8_FLAG == 0)
        val slots = stringSlots(bytes)
        val encodedStrings = slots.map { slot ->
            val encoded = slot.value.encodeToByteArray()
            require(slot.value.length < 0x80 && encoded.size < 0x80)
            byteArrayOf(slot.value.length.toByte(), encoded.size.toByte()) + encoded + byteArrayOf(0)
        }
        val rebuilt = ByteArray(stringsStart + encodedStrings.sumOf { it.size })
        original.copyInto(rebuilt, endIndex = stringsStart)
        rebuilt.writeInt(4, rebuilt.size)
        rebuilt.writeInt(16, UTF8_FLAG)
        var relativeOffset = 0
        encodedStrings.forEachIndexed { index, encoded ->
            rebuilt.writeInt(headerSize + index * 4, relativeOffset)
            encoded.copyInto(rebuilt, stringsStart + relativeOffset)
            relativeOffset += encoded.size
        }
        chunks[stringPoolIndex] = chunks[stringPoolIndex].copy(bytes = rebuilt)
        return rebuildXml(bytes, chunks)
    }

    private fun insertUnknownComponent(bytes: ByteArray, component: String, sourceName: String): ByteArray {
        val mutated = bytes.copyOf()
        val slots = stringSlots(mutated)
        val nameIndex = slots.single { it.value == sourceName }.index
        replaceShortString(mutated, sourceName, component)
        val chunks = xmlChunks(mutated).toMutableList()
        val providerStart = chunks.indexOfFirst { it.type == START_ELEMENT_TYPE && it.name == "provider" }
        val providerEnd = matchingEnd(chunks, providerStart)
        val componentChunks = chunks.subList(providerStart, providerEnd + 1).map { chunk ->
            if (chunk.type == START_ELEMENT_TYPE || chunk.type == END_ELEMENT_TYPE) {
                chunk.copy(bytes = chunk.bytes.copyOf().also { element ->
                    element.writeInt(element.u16(2) + 4, nameIndex)
                })
            } else {
                chunk
            }
        }
        val applicationStart = chunks.indexOfFirst { it.type == START_ELEMENT_TYPE && it.name == "application" }
        chunks.addAll(applicationStart + 1, componentChunks)
        return rebuildXml(mutated, chunks)
    }

    private fun insertMetadataWithoutAttributes(bytes: ByteArray): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val metadataStart = chunks.first { it.type == START_ELEMENT_TYPE && it.name == "meta-data" }
        val metadataEnd = chunks.first { it.type == END_ELEMENT_TYPE && it.name == "meta-data" }
        val bareStart = metadataStart.bytes.copyOfRange(0, metadataStart.bytes.u16(2) + 20).also { chunk ->
            chunk.writeInt(4, chunk.size)
            ByteBuffer.wrap(chunk, chunk.u16(2) + 12, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0)
        }
        val applicationStart = chunks.indexOfFirst { it.type == START_ELEMENT_TYPE && it.name == "application" }
        chunks.addAll(
            applicationStart + 1,
            listOf(
                XmlChunk(START_ELEMENT_TYPE, "meta-data", bareStart),
                metadataEnd.copy(bytes = metadataEnd.bytes.copyOf()),
            ),
        )
        return rebuildXml(bytes, chunks)
    }

    private fun removeNamespaceEnd(bytes: ByteArray): ByteArray = rebuildXml(
        bytes,
        xmlChunks(bytes).filterNot { it.type == END_NAMESPACE_TYPE },
    )

    private fun mutateNamespaceUri(bytes: ByteArray, type: Int, uri: String): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val index = chunks.indexOfFirst { it.type == type }
        require(index >= 0)
        val uriIndex = stringSlots(bytes).single { it.value == uri }.index
        val chunk = chunks[index].bytes.copyOf()
        chunk.writeInt(chunk.u16(2) + 4, uriIndex)
        chunks[index] = chunks[index].copy(bytes = chunk)
        return rebuildXml(bytes, chunks)
    }

    private fun mutateNamespacePrefix(bytes: ByteArray, type: Int, prefix: String): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val index = chunks.indexOfFirst { it.type == type }
        require(index >= 0)
        val prefixIndex = stringSlots(bytes).single { it.value == prefix }.index
        val chunk = chunks[index].bytes.copyOf()
        chunk.writeInt(chunk.u16(2), prefixIndex)
        chunks[index] = chunks[index].copy(bytes = chunk)
        return rebuildXml(bytes, chunks)
    }

    private fun replaceShortString(bytes: ByteArray, from: String, to: String) {
        val slot = stringSlots(bytes).single { it.value == from }
        val replacement = to.toByteArray(if (slot.utf8) StandardCharsets.UTF_8 else StandardCharsets.UTF_16LE)
        require(replacement.size < slot.payloadLength)
        if (slot.utf8) {
            bytes[slot.payloadOffset - 1] = replacement.size.toByte()
            replacement.copyInto(bytes, slot.payloadOffset)
            bytes[slot.payloadOffset + replacement.size] = 0
        } else {
            ByteBuffer.wrap(bytes, slot.payloadOffset - 2, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(to.length.toShort())
            replacement.copyInto(bytes, slot.payloadOffset)
            bytes[slot.payloadOffset + replacement.size] = 0
            bytes[slot.payloadOffset + replacement.size + 1] = 0
        }
    }

    private fun moveElementInside(mutated: ByteArray, element: String, parent: String): ByteArray {
        val chunks = xmlChunks(mutated).toMutableList()
        val elementStart = chunks.indexOfFirst { it.type == START_ELEMENT_TYPE && it.name == element }
        val elementEnd = matchingEnd(chunks, elementStart)
        val moved = chunks.subList(elementStart, elementEnd + 1).toList()
        chunks.subList(elementStart, elementEnd + 1).clear()
        val parentStart = chunks.indexOfFirst { it.type == START_ELEMENT_TYPE && it.name == parent }
        require(parentStart >= 0)
        chunks.addAll(parentStart + 1, moved)
        return rebuildXml(mutated, chunks)
    }

    private fun matchingEnd(chunks: List<XmlChunk>, start: Int): Int {
        require(start >= 0)
        var depth = 0
        for (index in start until chunks.size) {
            when (chunks[index].type) {
                START_ELEMENT_TYPE -> depth += 1
                END_ELEMENT_TYPE -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        error("element end가 없습니다.")
    }

    private fun xmlChunks(bytes: ByteArray): List<XmlChunk> {
        val strings = stringSlots(bytes)
        val chunks = mutableListOf<XmlChunk>()
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            val name = if (type == START_ELEMENT_TYPE || type == END_ELEMENT_TYPE) {
                strings[bytes.u32(offset + headerSize + 4)].value
            } else {
                null
            }
            chunks += XmlChunk(type, name, bytes.copyOfRange(offset, offset + size))
            offset += size
        }
        return chunks
    }

    private fun rebuildXml(original: ByteArray, chunks: List<XmlChunk>): ByteArray {
        val rootHeaderSize = original.u16(2)
        return ByteArrayOutputStream().use { output ->
            output.write(original, 0, rootHeaderSize)
            chunks.forEach { output.write(it.bytes) }
            output.toByteArray().also { rebuilt -> rebuilt.writeInt(4, rebuilt.size) }
        }
    }

    private fun attributeOffset(bytes: ByteArray, elementName: String, attributeName: String): Int {
        val strings = stringSlots(bytes)
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            if (type == START_ELEMENT_TYPE && strings[bytes.u32(offset + headerSize + 4)].value == elementName) {
                val extension = offset + headerSize
                val attributeStart = bytes.u16(extension + 8)
                val attributeSize = bytes.u16(extension + 10)
                val attributeCount = bytes.u16(extension + 12)
                repeat(attributeCount) { index ->
                    val attribute = extension + attributeStart + index * attributeSize
                    if (strings[bytes.u32(attribute + 4)].value == attributeName) return attribute
                }
            }
            offset += size
        }
        error("attribute가 없습니다.")
    }

    private fun stringSlots(bytes: ByteArray): List<StringSlot> {
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            if (type == STRING_POOL_TYPE) {
                val count = bytes.u32(offset + 8)
                val flags = bytes.u32(offset + 16)
                val stringsStart = offset + bytes.u32(offset + 20)
                val utf8 = flags and UTF8_FLAG != 0
                return List(count) { index ->
                    val offsetTableEntry = offset + headerSize + index * 4
                    val relativeOffset = bytes.u32(offsetTableEntry)
                    var cursor = stringsStart + relativeOffset
                    val value: String
                    val payloadOffset: Int
                    val payloadLength: Int
                    if (utf8) {
                        cursor = bytes.readUtf8Length(cursor).second
                        val (length, afterLength) = bytes.readUtf8Length(cursor)
                        payloadOffset = afterLength
                        payloadLength = length
                        value = bytes.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                            .toString(StandardCharsets.UTF_8)
                    } else {
                        val (length, afterLength) = bytes.readUtf16Length(cursor)
                        payloadOffset = afterLength
                        payloadLength = length * 2
                        value = bytes.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                            .toString(StandardCharsets.UTF_16LE)
                    }
                    StringSlot(index, value, payloadOffset, payloadLength, offsetTableEntry, relativeOffset, utf8)
                }
            }
            offset += size
        }
        error("string pool이 없습니다.")
    }

    private fun ByteArray.readUtf8Length(offset: Int): Pair<Int, Int> {
        val first = this[offset].toInt() and 0xff
        return if (first and 0x80 == 0) first to offset + 1 else {
            (((first and 0x7f) shl 8) or (this[offset + 1].toInt() and 0xff)) to offset + 2
        }
    }

    private fun ByteArray.readUtf16Length(offset: Int): Pair<Int, Int> {
        val first = u16(offset)
        return if (first and 0x8000 == 0) first to offset + 2 else {
            (((first and 0x7fff) shl 16) or u16(offset + 2)) to offset + 4
        }
    }

    private fun ByteArray.u16(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun ByteArray.u32(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private data class StringSlot(
        val index: Int,
        val value: String,
        val payloadOffset: Int,
        val payloadLength: Int,
        val offsetTableEntry: Int,
        val relativeOffset: Int,
        val utf8: Boolean,
    )

    private data class XmlChunk(val type: Int, val name: String?, val bytes: ByteArray)

    private companion object {
        const val PLACEHOLDER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val STRING_POOL_TYPE = 0x0001
        const val START_NAMESPACE_TYPE = 0x0100
        const val END_NAMESPACE_TYPE = 0x0101
        const val START_ELEMENT_TYPE = 0x0102
        const val END_ELEMENT_TYPE = 0x0103
        const val UTF8_FLAG = 0x00000100
        const val ATTRIBUTE_RAW_VALUE_OFFSET = 8
        const val ATTRIBUTE_TYPED_DATA_OFFSET = 16
    }
}
