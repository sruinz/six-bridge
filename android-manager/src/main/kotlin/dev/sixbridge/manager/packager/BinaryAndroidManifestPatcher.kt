package dev.sixbridge.manager.packager

import dev.sixbridge.manager.wrapper.ArchiveActivity
import dev.sixbridge.manager.wrapper.ArchiveIntentFilter
import dev.sixbridge.manager.wrapper.ArchiveProvider
import dev.sixbridge.manager.wrapper.BinaryAndroidManifestReader
import dev.sixbridge.manager.wrapper.BinaryManifestFacts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.TreeMap

data class WrapperManifestRequest(
    val extensionId: String,
    val versionCode: Int,
    val extensionName: String,
)

class BinaryAndroidManifestPatcher(
    private val reader: BinaryAndroidManifestReader = BinaryAndroidManifestReader(),
) {
    fun patch(template: ByteArray, request: WrapperManifestRequest): ByteArray {
        require(EXTENSION_ID.matches(request.extensionId)) { "확장 ID 형식이 올바르지 않습니다." }
        require(request.versionCode > 0) { "versionCode는 0보다 커야 합니다." }
        require(request.extensionName.isNotBlank() && '\u0000' !in request.extensionName) {
            "확장 이름이 올바르지 않습니다."
        }

        val immutableTemplate = template.copyOf()
        val templateFacts = reader.read(immutableTemplate)
        verifyTemplateFacts(templateFacts)
        val layout = BinaryManifestLayout.parse(immutableTemplate)

        val packageName = "$PACKAGE_PREFIX${request.extensionId}"
        val authority = "$packageName$PROVIDER_SUFFIX"
        val packageAttribute = layout.singleAttribute(
            elementName = "manifest",
            parentName = null,
            namespace = null,
            name = "package",
            resourceId = 0,
        )
        val versionCodeAttribute = layout.singleAttribute(
            elementName = "manifest",
            parentName = null,
            namespace = ANDROID_NAMESPACE,
            name = "versionCode",
            resourceId = VERSION_CODE_RESOURCE_ID,
        )
        val metadataValueAttribute = layout.singleMetadataValueAttribute()
        val providerAuthorityAttribute = layout.singleAttribute(
            elementName = "provider",
            parentName = "application",
            namespace = ANDROID_NAMESPACE,
            name = "authorities",
            resourceId = AUTHORITIES_RESOURCE_ID,
        )
        val applicationLabelAttribute = layout.singleAttribute(
            elementName = "application",
            parentName = "manifest",
            namespace = ANDROID_NAMESPACE,
            name = "label",
            resourceId = LABEL_RESOURCE_ID,
        )

        val packageSlot = layout.singlePlaceholder(PACKAGE_PREFIX + PLACEHOLDER_ID)
        val extensionIdSlot = layout.singlePlaceholder(PLACEHOLDER_ID)
        val authoritySlot = layout.singlePlaceholder(
            "$PACKAGE_PREFIX$PLACEHOLDER_ID$PROVIDER_SUFFIX",
        )
        val labelSlot = layout.singleStringAttribute(applicationLabelAttribute)
        require(
            labelSlot.value.startsWith(LABEL_PLACEHOLDER_PREFIX) &&
                labelSlot.payloadLength >= MIN_LABEL_PAYLOAD_BYTES,
        ) {
            "템플릿 application label placeholder가 올바르지 않습니다."
        }
        requireStringAttribute(packageAttribute, packageSlot)
        requireStringAttribute(metadataValueAttribute, extensionIdSlot)
        requireStringAttribute(providerAuthorityAttribute, authoritySlot)
        require(
            versionCodeAttribute.valueType == TYPE_INT_DEC &&
                versionCodeAttribute.rawValueIndex == NO_INDEX &&
                versionCodeAttribute.valueData == TEMPLATE_VERSION_CODE
        ) {
            "템플릿 versionCode attribute가 올바르지 않습니다."
        }

        val patched = immutableTemplate.copyOf()
        packageSlot.replace(patched, packageName)
        extensionIdSlot.replace(patched, request.extensionId)
        authoritySlot.replace(patched, authority)
        labelSlot.replaceWithinCapacity(patched, request.extensionName)
        patched.writeInt(versionCodeAttribute.offset + ATTRIBUTE_RAW_VALUE_OFFSET, NO_INDEX)
        patched.writeInt(versionCodeAttribute.offset + ATTRIBUTE_TYPED_DATA_OFFSET, request.versionCode)

        val facts = reader.read(patched)
        verifyPatchedFacts(facts, request, packageName, authority)
        val patchedLayout = BinaryManifestLayout.parse(patched)
        val patchedLabelAttribute = patchedLayout.singleAttribute(
            elementName = "application",
            parentName = "manifest",
            namespace = ANDROID_NAMESPACE,
            name = "label",
            resourceId = LABEL_RESOURCE_ID,
        )
        require(
            patchedLayout.singleStringAttribute(patchedLabelAttribute).value ==
                request.extensionName,
        ) {
            "패치된 manifest application label이 요청과 일치하지 않습니다."
        }
        return patched
    }

    private fun verifyTemplateFacts(facts: BinaryManifestFacts) {
        require(facts.packageName == "$PACKAGE_PREFIX$PLACEHOLDER_ID") {
            "템플릿 package가 올바르지 않습니다."
        }
        require(facts.versionCode == TEMPLATE_VERSION_CODE) {
            "템플릿 versionCode가 올바르지 않습니다."
        }
        verifyFixedFacts(facts, PLACEHOLDER_ID, "$PACKAGE_PREFIX$PLACEHOLDER_ID$PROVIDER_SUFFIX")
    }

    private fun verifyPatchedFacts(
        facts: BinaryManifestFacts,
        request: WrapperManifestRequest,
        packageName: String,
        authority: String,
    ) {
        require(facts.packageName == packageName && facts.versionCode == request.versionCode) {
            "패치된 manifest package 또는 versionCode가 요청과 일치하지 않습니다."
        }
        verifyFixedFacts(facts, request.extensionId, authority)
    }

    private fun verifyFixedFacts(
        facts: BinaryManifestFacts,
        extensionId: String,
        authority: String,
    ) {
        require(facts.minSdkVersion == MIN_SDK_VERSION && facts.targetSdkVersion == TARGET_SDK_VERSION) {
            "manifest SDK 계약이 올바르지 않습니다."
        }
        require(facts.metadata == mapOf(EXTENSION_ID_METADATA to extensionId)) {
            "manifest metadata 계약이 올바르지 않습니다."
        }
        require(
            facts.activities == listOf(
                ArchiveActivity(
                    DISCOVERY_ACTIVITY,
                    exported = true,
                    intentFilters = listOf(
                        ArchiveIntentFilter(
                            actions = setOf(VIEW_ACTION),
                            categories = setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
                            schemes = setOf(SIX_SCHEME),
                            hosts = setOf(EX_HOST),
                        ),
                    ),
                ),
            ),
        ) {
            "manifest Activity 계약이 올바르지 않습니다."
        }
        require(
            facts.providers == listOf(
                ArchiveProvider(EXTENSION_PROVIDER, authority, exported = true),
            ),
        ) {
            "manifest Provider 계약이 올바르지 않습니다."
        }
    }

    private fun requireStringAttribute(
        attribute: BinaryManifestLayout.Attribute,
        slot: BinaryManifestLayout.StringSlot,
    ) {
        require(
            attribute.valueType == TYPE_STRING &&
                attribute.valueData == slot.index &&
                (attribute.rawValueIndex == NO_INDEX || attribute.rawValueIndex == slot.index)
        ) {
            "템플릿 string attribute가 올바르지 않습니다."
        }
    }

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
        const val PLACEHOLDER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PACKAGE_PREFIX = "dev.sixbridge.ext."
        const val PROVIDER_SUFFIX = ".contentprovider"
        const val TEMPLATE_VERSION_CODE = 1
        const val MIN_SDK_VERSION = 30
        const val TARGET_SDK_VERSION = 36
        const val EXTENSION_ID_METADATA = "extension-id"
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val EXTENSION_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
        const val VIEW_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
        const val SIX_SCHEME = "six"
        const val EX_HOST = "ex"
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val VERSION_CODE_RESOURCE_ID = 0x0101021b
        const val AUTHORITIES_RESOURCE_ID = 0x01010018
        const val LABEL_RESOURCE_ID = 0x01010001
        const val LABEL_PLACEHOLDER_PREFIX = "SIXBRIDGE_EXTENSION_NAME_PLACEHOLDER_"
        const val MIN_LABEL_PAYLOAD_BYTES = 128
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val NO_INDEX = -1
        const val ATTRIBUTE_RAW_VALUE_OFFSET = 8
        const val ATTRIBUTE_TYPED_DATA_OFFSET = 16
    }
}

private class BinaryManifestLayout private constructor(
    private val strings: List<StringSlot>,
    private val attributes: List<Attribute>,
) {
    fun singlePlaceholder(value: String): StringSlot {
        val slots = strings.filter { it.value == value }
        require(slots.size == 1) { "템플릿 placeholder가 누락되었거나 중복되었습니다." }
        return slots.single()
    }

    fun singleAttribute(
        elementName: String,
        parentName: String?,
        namespace: String?,
        name: String,
        resourceId: Int,
    ): Attribute = attributes.singleOrNull {
        it.elementName == elementName &&
            it.parentName == parentName &&
            it.namespace == namespace &&
            it.name == name &&
            it.resourceId == resourceId
    } ?: throw IllegalArgumentException("템플릿 manifest attribute가 누락되었거나 중복되었습니다.")

    fun singleStringAttribute(attribute: Attribute): StringSlot {
        require(attribute.valueType == TYPE_STRING) {
            "템플릿 string attribute가 올바르지 않습니다."
        }
        val slot = string(attribute.valueData)
        require(
            attribute.rawValueIndex == NO_INDEX ||
                attribute.rawValueIndex == slot.index,
        ) {
            "템플릿 string attribute의 raw value가 올바르지 않습니다."
        }
        return slot
    }

    fun singleMetadataValueAttribute(): Attribute {
        val candidates = attributes.filter {
            it.elementName == "meta-data" &&
                it.parentName == "application" &&
                it.namespace == ANDROID_NAMESPACE &&
                it.name == "value" &&
                it.resourceId == VALUE_RESOURCE_ID
        }
        require(candidates.size == 1) { "템플릿 metadata value가 누락되었거나 중복되었습니다." }
        val name = singleAttribute(
            elementName = "meta-data",
            parentName = "application",
            namespace = ANDROID_NAMESPACE,
            name = "name",
            resourceId = NAME_RESOURCE_ID,
        )
        require(name.valueType == TYPE_STRING && string(name.valueData).value == EXTENSION_ID_METADATA) {
            "템플릿 metadata 이름이 올바르지 않습니다."
        }
        return candidates.single()
    }

    private fun string(index: Int): StringSlot {
        require(index in strings.indices) { "binary Android manifest string index가 올바르지 않습니다." }
        return strings[index]
    }

    data class Attribute(
        val elementName: String,
        val parentName: String?,
        val namespace: String?,
        val name: String,
        val resourceId: Int,
        val rawValueIndex: Int,
        val valueType: Int,
        val valueData: Int,
        val offset: Int,
    )

    data class StringSlot(
        val index: Int,
        val value: String,
        val payloadOffset: Int,
        val payloadLength: Int,
        val charset: java.nio.charset.Charset,
        val utf16LengthOffset: Int,
        val utf16LengthWidth: Int,
        val encodedLengthOffset: Int?,
        val encodedLengthWidth: Int?,
        val terminatorSize: Int,
    ) {
        fun replace(bytes: ByteArray, replacement: String) {
            val encoded = replacement.toByteArray(charset)
            require(encoded.size == payloadLength) {
                "템플릿 문자열 길이가 요청값과 일치하지 않습니다."
            }
            encoded.copyInto(bytes, payloadOffset)
        }

        fun replaceWithinCapacity(bytes: ByteArray, replacement: String) {
            val encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(replacement))
            val encoded = ByteArray(buffer.remaining()).also(buffer::get)
            require(encoded.size <= payloadLength) {
                "확장 이름이 manifest label placeholder 용량을 초과합니다."
            }
            writeLength(
                bytes,
                utf16LengthOffset,
                utf16LengthWidth,
                replacement.length,
                utf16 = charset == StandardCharsets.UTF_16LE,
            )
            encodedLengthOffset?.let { offset ->
                writeLength(
                    bytes,
                    offset,
                    requireNotNull(encodedLengthWidth),
                    encoded.size,
                    utf16 = false,
                )
            }
            bytes.fill(
                0,
                payloadOffset,
                payloadOffset + payloadLength + terminatorSize,
            )
            encoded.copyInto(bytes, payloadOffset)
        }

        private fun writeLength(
            bytes: ByteArray,
            offset: Int,
            width: Int,
            length: Int,
            utf16: Boolean,
        ) {
            if (utf16) {
                when (width) {
                    2 -> {
                        require(length <= 0x7fff)
                        ByteBuffer.wrap(bytes, offset, 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(length.toShort())
                    }
                    4 -> {
                        require(length >= 0)
                        ByteBuffer.wrap(bytes, offset, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(((length shr 16) or 0x8000).toShort())
                            .putShort(length.toShort())
                    }
                    else -> error("UTF-16 string length 폭이 올바르지 않습니다.")
                }
            } else {
                when (width) {
                    1 -> {
                        require(length < 0x80)
                        bytes[offset] = length.toByte()
                    }
                    2 -> {
                        require(length <= 0x7fff)
                        bytes[offset] = ((length shr 8) or 0x80).toByte()
                        bytes[offset + 1] = length.toByte()
                    }
                    else -> error("UTF-8 string length 폭이 올바르지 않습니다.")
                }
            }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): BinaryManifestLayout {
            require(bytes.size >= CHUNK_HEADER_SIZE && bytes.u16(0) == XML_TYPE) {
                "binary Android manifest root가 없습니다."
            }
            val rootHeaderSize = bytes.u16(2)
            val rootSize = bytes.u32(4)
            require(rootHeaderSize >= CHUNK_HEADER_SIZE && rootSize == bytes.size) {
                "binary Android manifest root 범위가 올바르지 않습니다."
            }

            var strings: List<StringSlot>? = null
            var resourceIds: List<Int>? = null
            val attributes = mutableListOf<Attribute>()
            val elements = mutableListOf<String>()
            val namespaces = mutableListOf<Namespace>()
            val namespaceDeclarations = mutableListOf<Namespace>()
            val structure = TemplateStructure()
            var offset = rootHeaderSize
            while (offset < rootSize) {
                require(offset <= rootSize - CHUNK_HEADER_SIZE) {
                    "binary Android manifest chunk가 잘렸습니다."
                }
                val type = bytes.u16(offset)
                val headerSize = bytes.u16(offset + 2)
                val size = bytes.u32(offset + 4)
                require(headerSize >= CHUNK_HEADER_SIZE && size >= headerSize && size <= rootSize - offset) {
                    "binary Android manifest chunk 범위가 올바르지 않습니다."
                }
                when (type) {
                    STRING_POOL_TYPE -> {
                        require(strings == null) { "binary Android manifest string pool이 중복되었습니다." }
                        strings = readStringPool(bytes, offset, headerSize, size)
                    }
                    RESOURCE_MAP_TYPE -> {
                        require(resourceIds == null) { "binary Android manifest resource map이 중복되었습니다." }
                        resourceIds = readResourceMap(
                            bytes,
                            offset,
                            headerSize,
                            size,
                            requireNotNull(strings) { "resource map 전에 string pool이 필요합니다." },
                        )
                    }
                    START_NAMESPACE_TYPE -> {
                        require(elements.isEmpty()) {
                            "binary Android manifest namespace hierarchy가 올바르지 않습니다."
                        }
                        val namespace = readNamespace(
                            bytes,
                            offset,
                            headerSize,
                            size,
                            requireNotNull(strings) { "namespace 전에 string pool이 필요합니다." },
                        )
                        require(namespaces.none { it.prefix == namespace.prefix }) {
                            "binary Android manifest namespace prefix가 중복되었습니다."
                        }
                        namespaces += namespace
                        namespaceDeclarations += namespace
                    }
                    END_NAMESPACE_TYPE -> {
                        require(elements.isEmpty()) {
                            "binary Android manifest namespace hierarchy가 올바르지 않습니다."
                        }
                        val namespace = readNamespace(
                            bytes,
                            offset,
                            headerSize,
                            size,
                            requireNotNull(strings) { "namespace 전에 string pool이 필요합니다." },
                        )
                        require(namespaces.removeLastOrNull() == namespace) {
                            "binary Android manifest namespace 종료 순서가 올바르지 않습니다."
                        }
                    }
                    START_ELEMENT_TYPE -> {
                        val pool = requireNotNull(strings) { "element 전에 string pool이 필요합니다." }
                        val map = requireNotNull(resourceIds) { "element 전에 resource map이 필요합니다." }
                        val element = readStartElement(bytes, offset, headerSize, size, pool, map)
                        val parent = elements.lastOrNull()
                        structure.start(element.name, parent)
                        attributes += element.attributes.map {
                            it.copy(elementName = element.name, parentName = parent)
                        }
                        elements += element.name
                    }
                    END_ELEMENT_TYPE -> {
                        val pool = requireNotNull(strings) { "element 전에 string pool이 필요합니다." }
                        val name = readEndElement(bytes, offset, headerSize, size, pool)
                        require(elements.removeLastOrNull() == name) {
                            "binary Android manifest element hierarchy가 올바르지 않습니다."
                        }
                    }
                    else -> throw IllegalArgumentException("지원하지 않는 binary Android manifest chunk입니다.")
                }
                offset += size
            }
            require(
                offset == rootSize &&
                    elements.isEmpty() &&
                    namespaces.isEmpty() &&
                    namespaceDeclarations == listOf(Namespace("android", ANDROID_NAMESPACE)) &&
                    strings != null &&
                    resourceIds != null,
            ) {
                "binary Android manifest가 완전하지 않습니다."
            }
            structure.finish()
            return BinaryManifestLayout(requireNotNull(strings), attributes)
        }

        private fun readStringPool(
            bytes: ByteArray,
            offset: Int,
            headerSize: Int,
            size: Int,
        ): List<StringSlot> {
            require(headerSize >= STRING_POOL_HEADER_SIZE) { "binary Android manifest string pool header가 잘렸습니다." }
            val stringCount = bytes.u32(offset + 8)
            val styleCount = bytes.u32(offset + 12)
            val flags = bytes.u32(offset + 16)
            val stringsStart = bytes.u32(offset + 20)
            require(stringCount <= MAX_STRING_COUNT && styleCount <= MAX_STRING_COUNT) {
                "binary Android manifest string pool이 너무 큽니다."
            }
            val tableEnd = offset.toLong() + headerSize + (stringCount.toLong() + styleCount) * INT_SIZE
            require(stringsStart >= headerSize && tableEnd <= offset.toLong() + stringsStart) {
                "binary Android manifest string offset table이 올바르지 않습니다."
            }
            val dataStart = offset + stringsStart
            val chunkEnd = offset + size
            require(dataStart in offset..chunkEnd) { "binary Android manifest string data 범위가 올바르지 않습니다." }
            val utf8 = flags and UTF8_FLAG != 0
            val offsets = hashSetOf<Int>()
            val payloadRanges = TreeMap<Int, Int>()
            var decodedBytes = 0L
            return List(stringCount) { index ->
                val relativeOffset = bytes.u32(offset + headerSize + index * INT_SIZE)
                require(offsets.add(relativeOffset)) {
                    "binary Android manifest string offset이 중복되었습니다."
                }
                val stringOffset = dataStart.toLong() + relativeOffset
                require(stringOffset in dataStart.toLong() until chunkEnd.toLong()) {
                    "binary Android manifest string 범위가 올바르지 않습니다."
                }
                val slot = if (utf8) {
                    val (utf16Length, afterUtf16Length) = bytes.readUtf8Length(stringOffset.toInt(), chunkEnd)
                    val (byteLength, payloadOffset) = bytes.readUtf8Length(afterUtf16Length, chunkEnd)
                    require(payloadOffset <= chunkEnd - byteLength - 1 && bytes.u8(payloadOffset + byteLength) == 0) {
                        "binary Android manifest UTF-8 string이 잘렸습니다."
                    }
                    val value = bytes.decode(payloadOffset, byteLength, StandardCharsets.UTF_8)
                    require(value.length == utf16Length) { "binary Android manifest UTF-8 길이가 올바르지 않습니다." }
                    StringSlot(
                        index = index,
                        value = value,
                        payloadOffset = payloadOffset,
                        payloadLength = byteLength,
                        charset = StandardCharsets.UTF_8,
                        utf16LengthOffset = stringOffset.toInt(),
                        utf16LengthWidth = afterUtf16Length - stringOffset.toInt(),
                        encodedLengthOffset = afterUtf16Length,
                        encodedLengthWidth = payloadOffset - afterUtf16Length,
                        terminatorSize = 1,
                    )
                } else {
                    val (length, payloadOffset) = bytes.readUtf16Length(stringOffset.toInt(), chunkEnd)
                    val byteLength = length.toLong() * 2
                    require(
                        byteLength <= Int.MAX_VALUE &&
                            payloadOffset.toLong() + byteLength + 2 <= chunkEnd &&
                            bytes.u16(payloadOffset + byteLength.toInt()) == 0,
                    ) { "binary Android manifest UTF-16 string이 잘렸습니다." }
                    val value = bytes.decode(payloadOffset, byteLength.toInt(), StandardCharsets.UTF_16LE)
                    require(value.length == length) { "binary Android manifest UTF-16 길이가 올바르지 않습니다." }
                    StringSlot(
                        index = index,
                        value = value,
                        payloadOffset = payloadOffset,
                        payloadLength = byteLength.toInt(),
                        charset = StandardCharsets.UTF_16LE,
                        utf16LengthOffset = stringOffset.toInt(),
                        utf16LengthWidth = payloadOffset - stringOffset.toInt(),
                        encodedLengthOffset = null,
                        encodedLengthWidth = null,
                        terminatorSize = 2,
                    )
                }
                val range = if (utf8) {
                    bytes.utf8PayloadRange(stringOffset.toInt(), chunkEnd)
                } else {
                    bytes.utf16PayloadRange(stringOffset.toInt(), chunkEnd)
                }
                require(!payloadRanges.overlaps(range)) {
                    "binary Android manifest string payload가 중복되었습니다."
                }
                payloadRanges[range.start] = range.endExclusive
                decodedBytes += range.payloadLength
                require(decodedBytes <= MAX_DECODED_STRING_BYTES) {
                    "binary Android manifest string data가 너무 큽니다."
                }
                slot
            }
        }

        private fun readResourceMap(
            bytes: ByteArray,
            offset: Int,
            headerSize: Int,
            size: Int,
            strings: List<StringSlot>,
        ): List<Int> {
            require(headerSize == CHUNK_HEADER_SIZE && (size - headerSize) % INT_SIZE == 0) {
                "binary Android manifest resource map 범위가 올바르지 않습니다."
            }
            val count = (size - headerSize) / INT_SIZE
            require(count <= strings.size) { "binary Android manifest resource map이 string pool보다 큽니다." }
            return List(count) { index -> bytes.u32(offset + headerSize + index * INT_SIZE) }
        }

        private data class Element(
            val name: String,
            val attributes: List<Attribute>,
        )

        private fun readStartElement(
            bytes: ByteArray,
            offset: Int,
            headerSize: Int,
            size: Int,
            strings: List<StringSlot>,
            resourceIds: List<Int>,
        ): Element {
            require(headerSize >= XML_NODE_HEADER_SIZE && size >= headerSize + ATTRIBUTE_EXTENSION_SIZE) {
                "binary Android manifest start element가 잘렸습니다."
            }
            val extension = offset + headerSize
            val name = strings.required(bytes.u32(extension + 4)).value
            val attributeStart = bytes.u16(extension + 8)
            val attributeSize = bytes.u16(extension + 10)
            val attributeCount = bytes.u16(extension + 12)
            require(attributeStart >= ATTRIBUTE_EXTENSION_SIZE && attributeSize >= ATTRIBUTE_SIZE) {
                "binary Android manifest attribute header가 올바르지 않습니다."
            }
            val firstAttribute = extension + attributeStart
            val attributeBytes = attributeCount.toLong() * attributeSize
            require(firstAttribute >= extension && firstAttribute.toLong() + attributeBytes <= offset.toLong() + size) {
                "binary Android manifest attribute 범위가 올바르지 않습니다."
            }
            return Element(
                name,
                List(attributeCount) { index ->
                    val attributeOffset = firstAttribute + index * attributeSize
                    val namespaceIndex = bytes.u32(attributeOffset)
                    val nameIndex = bytes.u32(attributeOffset + 4)
                    val rawValueIndex = bytes.u32(attributeOffset + ATTRIBUTE_RAW_VALUE_OFFSET)
                    val valueSize = bytes.u16(attributeOffset + 12)
                    val valueReserved = bytes.u8(attributeOffset + 14)
                    require(valueSize == TYPED_VALUE_SIZE && valueReserved == 0) {
                        "binary Android manifest typed attribute header가 올바르지 않습니다."
                    }
                    Attribute(
                        elementName = "",
                        parentName = null,
                        namespace = strings.optional(namespaceIndex)?.value,
                        name = strings.required(nameIndex).value,
                        resourceId = resourceIds.getOrElse(nameIndex) { 0 },
                        rawValueIndex = rawValueIndex,
                        valueType = bytes.u8(attributeOffset + 15),
                        valueData = bytes.u32(attributeOffset + ATTRIBUTE_TYPED_DATA_OFFSET),
                        offset = attributeOffset,
                    )
                },
            )
        }

        private fun readEndElement(
            bytes: ByteArray,
            offset: Int,
            headerSize: Int,
            size: Int,
            strings: List<StringSlot>,
        ): String {
            require(headerSize >= XML_NODE_HEADER_SIZE && size >= headerSize + END_ELEMENT_EXTENSION_SIZE) {
                "binary Android manifest end element가 잘렸습니다."
            }
            return strings.required(bytes.u32(offset + headerSize + 4)).value
        }

        private fun readNamespace(
            bytes: ByteArray,
            offset: Int,
            headerSize: Int,
            size: Int,
            strings: List<StringSlot>,
        ): Namespace {
            require(headerSize >= XML_NODE_HEADER_SIZE && size >= headerSize + NAMESPACE_EXTENSION_SIZE) {
                "binary Android manifest namespace가 잘렸습니다."
            }
            return Namespace(
                prefix = strings.required(bytes.u32(offset + headerSize)).value,
                uri = strings.required(bytes.u32(offset + headerSize + INT_SIZE)).value,
            )
        }

        private fun List<StringSlot>.required(index: Int): StringSlot {
            require(index != NO_INDEX && index in indices) { "binary Android manifest string index가 올바르지 않습니다." }
            return this[index]
        }

        private fun List<StringSlot>.optional(index: Int): StringSlot? =
            if (index == NO_INDEX) null else required(index)

        private fun ByteArray.readUtf8Length(offset: Int, end: Int): Pair<Int, Int> {
            require(offset < end) { "binary Android manifest UTF-8 length가 잘렸습니다." }
            val first = u8(offset)
            return if (first and 0x80 == 0) {
                first to offset + 1
            } else {
                require(offset + 1 < end) { "binary Android manifest UTF-8 length가 잘렸습니다." }
                (((first and 0x7f) shl 8) or u8(offset + 1)) to offset + 2
            }
        }

        private fun ByteArray.utf8PayloadRange(offset: Int, end: Int): PayloadRange {
            val (_, afterUtf16Length) = readUtf8Length(offset, end)
            val (byteLength, contentOffset) = readUtf8Length(afterUtf16Length, end)
            require(contentOffset <= end - byteLength - 1 && u8(contentOffset + byteLength) == 0) {
                "binary Android manifest UTF-8 string이 잘렸습니다."
            }
            return PayloadRange(offset, contentOffset + byteLength + 1, byteLength)
        }

        private fun ByteArray.readUtf16Length(offset: Int, end: Int): Pair<Int, Int> {
            require(offset <= end - 2) { "binary Android manifest UTF-16 length가 잘렸습니다." }
            val first = u16(offset)
            return if (first and 0x8000 == 0) {
                first to offset + 2
            } else {
                require(offset <= end - 4) { "binary Android manifest UTF-16 length가 잘렸습니다." }
                (((first and 0x7fff) shl 16) or u16(offset + 2)) to offset + 4
            }
        }

        private fun ByteArray.utf16PayloadRange(offset: Int, end: Int): PayloadRange {
            val (length, contentOffset) = readUtf16Length(offset, end)
            val byteLength = length.toLong() * 2
            require(
                byteLength <= Int.MAX_VALUE &&
                    contentOffset.toLong() + byteLength + 2 <= end &&
                    u16(contentOffset + byteLength.toInt()) == 0,
            ) { "binary Android manifest UTF-16 string이 잘렸습니다." }
            return PayloadRange(offset, contentOffset + byteLength.toInt() + 2, byteLength.toInt())
        }

        private fun ByteArray.decode(offset: Int, length: Int, charset: java.nio.charset.Charset): String =
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(this, offset, length))
                .toString()

        private fun ByteArray.u8(offset: Int): Int {
            require(offset in indices) { "binary Android manifest byte 범위가 올바르지 않습니다." }
            return this[offset].toInt() and 0xff
        }

        private fun ByteArray.u16(offset: Int): Int {
            require(offset >= 0 && offset <= size - 2) { "binary Android manifest uint16 범위가 올바르지 않습니다." }
            return ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        }

        private fun ByteArray.u32(offset: Int): Int {
            require(offset >= 0 && offset <= size - 4) { "binary Android manifest uint32 범위가 올바르지 않습니다." }
            val value = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(value >= 0 || value == NO_INDEX) { "binary Android manifest uint32 값이 너무 큽니다." }
            return value
        }

        private const val XML_TYPE = 0x0003
        private const val STRING_POOL_TYPE = 0x0001
        private const val RESOURCE_MAP_TYPE = 0x0180
        private const val START_NAMESPACE_TYPE = 0x0100
        private const val END_NAMESPACE_TYPE = 0x0101
        private const val START_ELEMENT_TYPE = 0x0102
        private const val END_ELEMENT_TYPE = 0x0103
        private const val CHUNK_HEADER_SIZE = 8
        private const val XML_NODE_HEADER_SIZE = 16
        private const val STRING_POOL_HEADER_SIZE = 28
        private const val ATTRIBUTE_EXTENSION_SIZE = 20
        private const val END_ELEMENT_EXTENSION_SIZE = 8
        private const val NAMESPACE_EXTENSION_SIZE = 8
        private const val ATTRIBUTE_SIZE = 20
        private const val TYPED_VALUE_SIZE = 8
        private const val INT_SIZE = 4
        private const val MAX_STRING_COUNT = 100_000
        private const val MAX_DECODED_STRING_BYTES = 4 * 1024 * 1024L
        private const val NO_INDEX = -1
        private const val UTF8_FLAG = 0x00000100
        private const val TYPE_STRING = 0x03
        private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        private const val VALUE_RESOURCE_ID = 0x01010024
        private const val NAME_RESOURCE_ID = 0x01010003
        private const val EXTENSION_ID_METADATA = "extension-id"
        private const val ATTRIBUTE_RAW_VALUE_OFFSET = 8
        private const val ATTRIBUTE_TYPED_DATA_OFFSET = 16

        private data class Namespace(val prefix: String, val uri: String)

        private data class PayloadRange(
            val start: Int,
            val endExclusive: Int,
            val payloadLength: Int,
        )

        private fun TreeMap<Int, Int>.overlaps(range: PayloadRange): Boolean =
            floorEntry(range.start)?.value?.let { it > range.start } == true ||
                ceilingEntry(range.start)?.key?.let { it < range.endExclusive } == true

        private class TemplateStructure {
            private val elements = linkedMapOf<String, Int>()

            fun start(name: String, parent: String?) {
                when (name) {
                    "manifest" -> require(parent == null)
                    "uses-sdk", "application" -> require(parent == "manifest")
                    "meta-data", "activity", "provider" -> require(parent == "application")
                    "intent-filter" -> require(parent == "activity")
                    "action", "category", "data" -> require(parent == "intent-filter")
                    else -> throw IllegalArgumentException("템플릿 manifest element가 허용 목록에 없습니다.")
                }
                elements[name] = (elements[name] ?: 0) + 1
                when (name) {
                    "manifest", "uses-sdk", "application", "meta-data", "activity", "provider", "intent-filter", "action", "data" ->
                        require(elements.getValue(name) == 1) {
                            "템플릿 manifest element가 중복되었습니다."
                        }
                    "category" -> require(elements.getValue(name) <= 2) {
                        "템플릿 manifest category가 중복되었습니다."
                    }
                }
            }

            fun finish() {
                require(
                    elements == mapOf(
                        "manifest" to 1,
                        "uses-sdk" to 1,
                        "application" to 1,
                        "meta-data" to 1,
                        "activity" to 1,
                        "intent-filter" to 1,
                        "action" to 1,
                        "category" to 2,
                        "data" to 1,
                        "provider" to 1,
                    ),
                ) { "템플릿 manifest 구조가 올바르지 않습니다." }
            }
        }
    }
}

private fun ByteArray.writeInt(offset: Int, value: Int) {
    require(offset >= 0 && offset <= size - 4) { "binary Android manifest write 범위가 올바르지 않습니다." }
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
}
