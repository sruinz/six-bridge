package dev.sixbridge.manager.wrapper

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class BinaryAndroidManifestReader {
    fun read(bytes: ByteArray): BinaryManifestFacts {
        require(bytes.size >= CHUNK_HEADER_SIZE) { "binary Android manifest가 잘렸습니다." }
        require(bytes.u16(0) == XML_TYPE) { "binary Android manifest root가 없습니다." }
        val rootHeaderSize = bytes.u16(2)
        val rootSize = bytes.u32(4)
        require(rootHeaderSize >= CHUNK_HEADER_SIZE && rootSize == bytes.size) {
            "binary Android manifest root 범위가 올바르지 않습니다."
        }

        var strings: StringPool? = null
        var resourceMap: ResourceMap? = null
        var offset = rootHeaderSize
        val elements = mutableListOf<String>()
        val metadata = linkedMapOf<String, String>()
        val activities = mutableListOf<ArchiveActivity>()
        val providers = mutableListOf<ArchiveProvider>()
        var activity: ActivityBuilder? = null
        var intentFilter: IntentFilterBuilder? = null
        var manifestSeen = false
        var applicationCount = 0
        var packageName: String? = null
        var versionCode: Int? = null
        var minSdkVersion: Int? = null
        var targetSdkVersion: Int? = null
        val requiredAttributeCounts = linkedMapOf(
            VERSION_CODE_RESOURCE_ID to 0,
            MIN_SDK_VERSION_RESOURCE_ID to 0,
            TARGET_SDK_VERSION_RESOURCE_ID to 0,
        )

        while (offset < rootSize) {
            require(offset <= rootSize - CHUNK_HEADER_SIZE) {
                "binary Android manifest chunk가 잘렸습니다."
            }
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            require(headerSize >= CHUNK_HEADER_SIZE && size >= headerSize && offset <= rootSize - size) {
                "binary Android manifest chunk 범위가 올바르지 않습니다."
            }
            when (type) {
                STRING_POOL_TYPE -> {
                    require(strings == null) { "binary Android manifest string pool이 중복되었습니다." }
                    strings = readStringPool(bytes, offset, headerSize, size)
                }
                RESOURCE_MAP_TYPE -> {
                    require(resourceMap == null) {
                        "binary Android manifest resource map이 중복되었습니다."
                    }
                    resourceMap = readResourceMap(
                        bytes,
                        offset,
                        headerSize,
                        size,
                        requireNotNull(strings) {
                            "binary Android manifest resource map 전에 string pool이 필요합니다."
                        },
                    )
                }
                START_NAMESPACE_TYPE, END_NAMESPACE_TYPE -> Unit
                START_ELEMENT_TYPE -> {
                    val pool = requireNotNull(strings) {
                        "binary Android manifest element 전에 string pool이 필요합니다."
                    }
                    val element = readElement(
                        bytes,
                        offset,
                        headerSize,
                        size,
                        pool,
                        requireNotNull(resourceMap) {
                            "binary Android manifest element 전에 resource map이 필요합니다."
                        },
                    )
                    element.attributes.forEach { attribute ->
                        requiredAttributeCounts[attribute.resourceId]?.let { count ->
                            requiredAttributeCounts[attribute.resourceId] = count + 1
                        }
                    }
                    val parent = elements.lastOrNull()
                    if (elements.isEmpty()) {
                        require(!manifestSeen && element.name == "manifest") {
                            "binary Android manifest root hierarchy가 올바르지 않습니다."
                        }
                        manifestSeen = true
                    } else {
                        require(manifestSeen) {
                            "binary Android manifest root hierarchy가 올바르지 않습니다."
                        }
                    }
                    when (element.name) {
                        "manifest" -> require(elements.isEmpty()) {
                            "binary Android manifest root hierarchy가 올바르지 않습니다."
                        }
                        "application" -> {
                            require(elements == listOf("manifest")) {
                                "binary Android manifest application hierarchy가 올바르지 않습니다."
                            }
                            applicationCount += 1
                            require(applicationCount == 1) {
                                "binary Android manifest application hierarchy가 올바르지 않습니다."
                            }
                        }
                        "uses-sdk" -> require(elements == listOf("manifest")) {
                            "binary Android manifest uses-sdk hierarchy가 올바르지 않습니다."
                        }
                        "meta-data", "activity", "provider" ->
                            require(elements == listOf("manifest", "application")) {
                                "binary Android manifest component hierarchy가 올바르지 않습니다."
                            }
                        "intent-filter" ->
                            require(
                                elements == listOf("manifest", "application", "activity"),
                            ) {
                                "binary Android manifest intent-filter hierarchy가 올바르지 않습니다."
                            }
                        "action", "category", "data" ->
                            require(
                                elements == listOf(
                                    "manifest",
                                    "application",
                                    "activity",
                                    "intent-filter",
                                ),
                            ) {
                                "binary Android manifest intent-filter child hierarchy가 올바르지 않습니다."
                            }
                    }
                    when {
                        element.name == "manifest" && parent == null -> {
                            require(packageName == null && versionCode == null) {
                                "binary Android manifest package 또는 versionCode가 중복되었습니다."
                            }
                            packageName = element.requiredStringAttribute(
                                namespace = null,
                                name = "package",
                                resourceId = 0,
                            )
                            versionCode = element.requiredIntegerAttribute(
                                name = "versionCode",
                                resourceId = VERSION_CODE_RESOURCE_ID,
                            )
                        }
                        element.name == "uses-sdk" && parent == "manifest" -> {
                            require(minSdkVersion == null && targetSdkVersion == null) {
                                "binary Android manifest SDK attribute가 중복되었습니다."
                            }
                            minSdkVersion = element.requiredIntegerAttribute(
                                name = "minSdkVersion",
                                resourceId = MIN_SDK_VERSION_RESOURCE_ID,
                            )
                            targetSdkVersion = element.requiredIntegerAttribute(
                                name = "targetSdkVersion",
                                resourceId = TARGET_SDK_VERSION_RESOURCE_ID,
                            )
                        }
                        element.name == "meta-data" && parent == "application" -> {
                            val name = element.androidValue("name")
                            val value = element.androidValue("value")
                            if (name != null && value != null) {
                                require(metadata.put(name, value) == null) {
                                    "binary Android manifest metadata가 중복되었습니다."
                                }
                            }
                        }
                        element.name == "activity" && parent == "application" -> {
                            require(activity == null) {
                                "binary Android manifest activity hierarchy가 올바르지 않습니다."
                            }
                            activity = ActivityBuilder(
                                name = element.androidValue("name").orEmpty(),
                                exported = element.androidBoolean("exported"),
                            )
                        }
                        element.name == "provider" && parent == "application" -> {
                            providers += ArchiveProvider(
                                name = element.androidValue("name").orEmpty(),
                                authority = element.androidValue("authorities").orEmpty(),
                                exported = element.androidBoolean("exported"),
                            )
                        }
                        element.name == "intent-filter" && parent == "activity" -> {
                            require(activity != null && intentFilter == null) {
                                "binary Android manifest intent-filter hierarchy가 올바르지 않습니다."
                            }
                            intentFilter = IntentFilterBuilder()
                        }
                        element.name == "action" && parent == "intent-filter" -> {
                            val filter = requireNotNull(intentFilter)
                            filter.actionCount += 1
                            element.androidValue("name")?.let(filter.actions::add)
                        }
                        element.name == "category" && parent == "intent-filter" -> {
                            val filter = requireNotNull(intentFilter)
                            filter.categoryCount += 1
                            element.androidValue("name")?.let(filter.categories::add)
                        }
                        element.name == "data" && parent == "intent-filter" -> {
                            val filter = requireNotNull(intentFilter)
                            filter.dataCount += 1
                            element.androidValue("scheme")?.let(filter.schemes::add)
                            element.androidValue("host")?.let(filter.hosts::add)
                        }
                    }
                    elements += element.name
                }
                END_ELEMENT_TYPE -> {
                    val pool = requireNotNull(strings) {
                        "binary Android manifest element 전에 string pool이 필요합니다."
                    }
                    val name = readEndElement(bytes, offset, headerSize, size, pool)
                    require(elements.removeLastOrNull() == name) {
                        "binary Android manifest element hierarchy가 올바르지 않습니다."
                    }
                    if (name == "intent-filter") {
                        val completed = requireNotNull(intentFilter) {
                            "binary Android manifest intent-filter가 올바르지 않습니다."
                        }
                        requireNotNull(activity).intentFilters += completed.build()
                        intentFilter = null
                    } else if (name == "activity") {
                        require(intentFilter == null) {
                            "binary Android manifest intent-filter가 닫히지 않았습니다."
                        }
                        activities += requireNotNull(activity).build()
                        activity = null
                    }
                }
                else -> throw IllegalArgumentException(
                    "지원하지 않는 binary Android manifest chunk입니다.",
                )
            }
            offset += size
        }
        require(
            offset == rootSize &&
                elements.isEmpty() &&
                activity == null &&
                intentFilter == null &&
                manifestSeen &&
                applicationCount == 1 &&
                requiredAttributeCounts.values.all { it == 1 },
        ) {
            "binary Android manifest가 완전하지 않습니다."
        }
        return BinaryManifestFacts(
            packageName = requireNotNull(packageName) {
                "binary Android manifest package가 없습니다."
            },
            versionCode = requireNotNull(versionCode) {
                "binary Android manifest versionCode가 없습니다."
            },
            minSdkVersion = requireNotNull(minSdkVersion) {
                "binary Android manifest minSdkVersion이 없습니다."
            },
            targetSdkVersion = requireNotNull(targetSdkVersion) {
                "binary Android manifest targetSdkVersion이 없습니다."
            },
            metadata = metadata,
            activities = activities,
            providers = providers,
        )
    }

    private fun readElement(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        size: Int,
        strings: StringPool,
        resourceMap: ResourceMap,
    ): Element {
        require(headerSize >= XML_NODE_HEADER_SIZE && size >= headerSize + ATTRIBUTE_EXTENSION_SIZE) {
            "binary Android manifest start element가 잘렸습니다."
        }
        val extension = offset + headerSize
        val name = strings.required(bytes.u32(extension + 4))
        val attributeStart = bytes.u16(extension + 8)
        val attributeSize = bytes.u16(extension + 10)
        val attributeCount = bytes.u16(extension + 12)
        require(attributeStart >= ATTRIBUTE_EXTENSION_SIZE && attributeSize >= ATTRIBUTE_SIZE) {
            "binary Android manifest attribute header가 올바르지 않습니다."
        }
        val firstAttribute = extension + attributeStart
        val attributeBytes = attributeCount.toLong() * attributeSize
        require(firstAttribute >= extension &&
            firstAttribute.toLong() + attributeBytes <= offset.toLong() + size
        ) {
            "binary Android manifest attribute 범위가 올바르지 않습니다."
        }
        val attributes = ArrayList<Attribute>(attributeCount)
        repeat(attributeCount) { index ->
            val attributeOffset = firstAttribute + index * attributeSize
            val namespaceIndex = bytes.u32(attributeOffset)
            val nameIndex = bytes.u32(attributeOffset + 4)
            val attributeName = strings.required(nameIndex)
            val rawValueIndex = bytes.u32(attributeOffset + 8)
            val valueSize = bytes.u16(attributeOffset + 12)
            val valueReserved = bytes.u8(attributeOffset + 14)
            val valueType = bytes.u8(attributeOffset + 15)
            val valueData = bytes.u32(attributeOffset + 16)
            require(valueSize == TYPED_VALUE_SIZE && valueReserved == 0) {
                "binary Android manifest typed attribute header가 올바르지 않습니다."
            }
            val namespace = strings.optional(namespaceIndex)
            validateAndroidAttribute(
                namespace = namespace,
                name = attributeName,
                resourceId = resourceMap.id(nameIndex),
                valueType = valueType,
            )
            val rawValue = strings.optional(rawValueIndex)
            val value = when (valueType) {
                TYPE_STRING -> strings.required(valueData).also { typedValue ->
                    require(rawValue == null || rawValue == typedValue) {
                        "binary Android manifest typed attribute raw string이 일치하지 않습니다."
                    }
                }
                TYPE_INT_BOOLEAN -> if (valueData == 0) "false" else "true"
                TYPE_INT_DEC, TYPE_INT_HEX -> {
                    require(rawValueIndex == NO_INDEX) {
                        "binary Android manifest typed integer raw string이 올바르지 않습니다."
                    }
                    valueData.toString()
                }
                else -> rawValue
            }
            attributes += Attribute(
                namespace = namespace,
                name = attributeName,
                value = value,
                resourceId = resourceMap.id(nameIndex),
                rawValueIndex = rawValueIndex,
                valueType = valueType,
                valueData = valueData,
            )
        }
        return Element(name, attributes)
    }

    private fun readEndElement(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        size: Int,
        strings: StringPool,
    ): String {
        require(headerSize >= XML_NODE_HEADER_SIZE && size >= headerSize + END_ELEMENT_EXTENSION_SIZE) {
            "binary Android manifest end element가 잘렸습니다."
        }
        return strings.required(bytes.u32(offset + headerSize + 4))
    }

    private fun validateAndroidAttribute(
        namespace: String?,
        name: String,
        resourceId: Int,
        valueType: Int,
    ) {
        val expected = VERIFIED_ANDROID_ATTRIBUTES[name]
        val usesVerifiedId = resourceId in VERIFIED_ANDROID_ATTRIBUTE_IDS
        if (expected != null || usesVerifiedId) {
            require(
                namespace == ANDROID_NAMESPACE &&
                    expected?.resourceId == resourceId &&
                    expected.valueType == valueType,
            ) {
                "binary Android manifest resource map 또는 typed attribute가 일치하지 않습니다."
            }
        }
    }

    private data class Element(
        val name: String,
        val attributes: List<Attribute>,
    ) {
        fun androidValue(name: String): String? =
            attributes.singleOrNull { it.namespace == ANDROID_NAMESPACE && it.name == name }?.value

        fun androidBoolean(name: String): Boolean = androidValue(name) == "true"

        fun requiredStringAttribute(namespace: String?, name: String, resourceId: Int): String {
            val attribute = attributes.singleOrNull {
                it.namespace == namespace && it.name == name && it.resourceId == resourceId
            } ?: throw IllegalArgumentException("binary Android manifest string attribute가 올바르지 않습니다.")
            require(attribute.valueType == TYPE_STRING && attribute.value != null) {
                "binary Android manifest string attribute가 올바르지 않습니다."
            }
            return attribute.value
        }

        fun requiredIntegerAttribute(name: String, resourceId: Int): Int {
            val attribute = attributes.singleOrNull {
                it.namespace == ANDROID_NAMESPACE && it.name == name && it.resourceId == resourceId
            } ?: throw IllegalArgumentException("binary Android manifest integer attribute가 올바르지 않습니다.")
            require(attribute.valueType == TYPE_INT_DEC && attribute.rawValueIndex == NO_INDEX) {
                "binary Android manifest integer attribute가 올바르지 않습니다."
            }
            return attribute.valueData
        }
    }

    private data class Attribute(
        val namespace: String?,
        val name: String,
        val value: String?,
        val resourceId: Int,
        val rawValueIndex: Int,
        val valueType: Int,
        val valueData: Int,
    )

    private data class ActivityBuilder(
        val name: String,
        val exported: Boolean,
        val intentFilters: MutableList<ArchiveIntentFilter> = mutableListOf(),
    ) {
        fun build() = ArchiveActivity(name, exported, intentFilters.toList())
    }

    private data class IntentFilterBuilder(
        val actions: MutableSet<String> = linkedSetOf(),
        val categories: MutableSet<String> = linkedSetOf(),
        val schemes: MutableSet<String> = linkedSetOf(),
        val hosts: MutableSet<String> = linkedSetOf(),
        var actionCount: Int = 0,
        var categoryCount: Int = 0,
        var dataCount: Int = 0,
    ) {
        fun build() = ArchiveIntentFilter(
            actions.toSet(),
            categories.toSet(),
            schemes.toSet(),
            hosts.toSet(),
            actionCount,
            categoryCount,
            dataCount,
        )
    }

    private fun readStringPool(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        size: Int,
    ): StringPool {
        require(headerSize >= STRING_POOL_HEADER_SIZE) {
            "binary Android manifest string pool header가 잘렸습니다."
        }
        val stringCount = bytes.u32(offset + 8)
        val styleCount = bytes.u32(offset + 12)
        val flags = bytes.u32(offset + 16)
        val stringsStart = bytes.u32(offset + 20)
        require(stringCount in 0..MAX_STRING_COUNT && styleCount in 0..MAX_STRING_COUNT) {
            "binary Android manifest string pool이 너무 큽니다."
        }
        val offsetsEnd = offset.toLong() + headerSize +
            (stringCount.toLong() + styleCount) * INT_SIZE
        require(stringsStart >= headerSize && offsetsEnd <= offset.toLong() + stringsStart) {
            "binary Android manifest string offset table이 올바르지 않습니다."
        }
        val chunkEnd = offset + size
        val stringData = offset + stringsStart
        require(stringData in offset..chunkEnd) {
            "binary Android manifest string data 범위가 올바르지 않습니다."
        }
        val utf8 = flags and UTF8_FLAG != 0
        val values = ArrayList<String>(stringCount)
        repeat(stringCount) { index ->
            val relative = bytes.u32(offset + headerSize + index * INT_SIZE)
            val stringOffset = stringData.toLong() + relative
            require(stringOffset in stringData.toLong() until chunkEnd.toLong()) {
                "binary Android manifest string 범위가 올바르지 않습니다."
            }
            values += if (utf8) {
                bytes.readUtf8(stringOffset.toInt(), chunkEnd)
            } else {
                bytes.readUtf16(stringOffset.toInt(), chunkEnd)
            }
        }
        return StringPool(values)
    }

    private fun readResourceMap(
        bytes: ByteArray,
        offset: Int,
        headerSize: Int,
        size: Int,
        strings: StringPool,
    ): ResourceMap {
        require(headerSize == CHUNK_HEADER_SIZE && (size - headerSize) % INT_SIZE == 0) {
            "binary Android manifest resource map 범위가 올바르지 않습니다."
        }
        val count = (size - headerSize) / INT_SIZE
        require(count <= strings.size) {
            "binary Android manifest resource map이 string pool보다 큽니다."
        }
        return ResourceMap(
            List(count) { index ->
                bytes.u32(offset + headerSize + index * INT_SIZE)
            },
        )
    }

    private class StringPool(
        private val values: List<String>,
    ) {
        val size: Int
            get() = values.size

        fun required(index: Int): String {
            require(index != NO_INDEX && index in values.indices) {
                "binary Android manifest string index가 올바르지 않습니다."
            }
            return values[index]
        }

        fun optional(index: Int): String? = if (index == NO_INDEX) null else required(index)
    }

    private class ResourceMap(
        private val resourceIds: List<Int>,
    ) {
        fun id(stringIndex: Int): Int = resourceIds.getOrElse(stringIndex) { 0 }
    }

    private fun ByteArray.readUtf8(offset: Int, end: Int): String {
        val (_, afterUtf16Length) = readUtf8Length(offset, end)
        val (byteLength, contentOffset) = readUtf8Length(afterUtf16Length, end)
        require(byteLength >= 0 && contentOffset <= end - byteLength - 1) {
            "binary Android manifest UTF-8 string이 잘렸습니다."
        }
        require(u8(contentOffset + byteLength) == 0) {
            "binary Android manifest UTF-8 terminator가 없습니다."
        }
        return decode(contentOffset, byteLength, StandardCharsets.UTF_8)
    }

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

    private fun ByteArray.readUtf16(offset: Int, end: Int): String {
        val (length, contentOffset) = readUtf16Length(offset, end)
        val byteLength = length.toLong() * 2
        require(byteLength <= Int.MAX_VALUE &&
            contentOffset.toLong() + byteLength + 2 <= end
        ) {
            "binary Android manifest UTF-16 string이 잘렸습니다."
        }
        val bytesLength = byteLength.toInt()
        require(u16(contentOffset + bytesLength) == 0) {
            "binary Android manifest UTF-16 terminator가 없습니다."
        }
        return decode(contentOffset, bytesLength, StandardCharsets.UTF_16LE)
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
        require(offset >= 0 && offset <= size - 2) {
            "binary Android manifest uint16 범위가 올바르지 않습니다."
        }
        return ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun ByteArray.u32(offset: Int): Int {
        require(offset >= 0 && offset <= size - 4) {
            "binary Android manifest uint32 범위가 올바르지 않습니다."
        }
        val value = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(value >= 0 || value == NO_INDEX) {
            "binary Android manifest uint32 값이 너무 큽니다."
        }
        return value
    }

    private companion object {
        const val XML_TYPE = 0x0003
        const val STRING_POOL_TYPE = 0x0001
        const val RESOURCE_MAP_TYPE = 0x0180
        const val START_NAMESPACE_TYPE = 0x0100
        const val END_NAMESPACE_TYPE = 0x0101
        const val START_ELEMENT_TYPE = 0x0102
        const val END_ELEMENT_TYPE = 0x0103
        const val CHUNK_HEADER_SIZE = 8
        const val XML_NODE_HEADER_SIZE = 16
        const val STRING_POOL_HEADER_SIZE = 28
        const val ATTRIBUTE_EXTENSION_SIZE = 20
        const val END_ELEMENT_EXTENSION_SIZE = 8
        const val ATTRIBUTE_SIZE = 20
        const val TYPED_VALUE_SIZE = 8
        const val INT_SIZE = 4
        const val MAX_STRING_COUNT = 100_000
        const val NO_INDEX = -1
        const val UTF8_FLAG = 0x00000100
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_HEX = 0x11
        const val TYPE_INT_BOOLEAN = 0x12
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val VERIFIED_ANDROID_ATTRIBUTES = mapOf(
            "name" to ExpectedAttribute(0x01010003, TYPE_STRING),
            "value" to ExpectedAttribute(0x01010024, TYPE_STRING),
            "exported" to ExpectedAttribute(0x01010010, TYPE_INT_BOOLEAN),
            "authorities" to ExpectedAttribute(0x01010018, TYPE_STRING),
            "scheme" to ExpectedAttribute(0x01010027, TYPE_STRING),
            "host" to ExpectedAttribute(0x01010028, TYPE_STRING),
            "versionCode" to ExpectedAttribute(VERSION_CODE_RESOURCE_ID, TYPE_INT_DEC),
            "minSdkVersion" to ExpectedAttribute(MIN_SDK_VERSION_RESOURCE_ID, TYPE_INT_DEC),
            "targetSdkVersion" to ExpectedAttribute(TARGET_SDK_VERSION_RESOURCE_ID, TYPE_INT_DEC),
        )
        val VERIFIED_ANDROID_ATTRIBUTE_IDS =
            VERIFIED_ANDROID_ATTRIBUTES.values.mapTo(hashSetOf(), ExpectedAttribute::resourceId)
        const val VERSION_CODE_RESOURCE_ID = 0x0101021b
        const val MIN_SDK_VERSION_RESOURCE_ID = 0x0101020c
        const val TARGET_SDK_VERSION_RESOURCE_ID = 0x01010270
    }

    private data class ExpectedAttribute(
        val resourceId: Int,
        val valueType: Int,
    )
}
