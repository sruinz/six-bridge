package dev.sixbridge.manager.wrapper

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.manager.importer.ImportFailureCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Test
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidWrapperVerifierTest {
    @Test
    fun `returns verified wrapper when all APK descriptor CRX and report identities agree`() {
        val fixture = fixture()

        val result = verifier(fixture.facts).verify(
            fixture.wrapperFile,
            fixture.descriptor,
            fixture.report,
        )

        val success = assertIs<WrapperVerificationResult.Success>(result)
        assertEquals(fixture.descriptor.extensionId, success.value.extensionId)
        assertEquals(fixture.descriptor.wrapperPackage, success.value.packageName)
        assertEquals(fixture.facts.providers.single().authority, success.value.providerAuthority)
    }

    @Test
    fun `maps every wrapper contract mismatch to its own failure code`() {
        val base = fixture()
        val otherId = if (base.descriptor.extensionId == "a".repeat(32)) {
            "b".repeat(32)
        } else {
            "a".repeat(32)
        }
        val otherPackage = "dev.sixbridge.ext.$otherId"
        val validFilter = base.facts.activities.single().intentFilters.single()
        val cases = listOf(
            Case(
                ImportFailureCode.WRAPPER_HASH_MISMATCH,
                descriptor = base.descriptor.copy(wrapperSha256 = "0".repeat(64)),
            ),
            Case(
                ImportFailureCode.PACKAGE_MISMATCH,
                facts = base.facts.copy(packageName = "dev.sixbridge.ext.${"p".repeat(32)}"),
            ),
            Case(
                ImportFailureCode.SIGNER_MISMATCH,
                facts = base.facts.copy(signerSha256 = "9".repeat(64)),
            ),
            Case(
                ImportFailureCode.VERSION_CODE_MISMATCH,
                facts = base.facts.copy(versionCode = base.facts.versionCode + 1),
            ),
            Case(
                ImportFailureCode.METADATA_MISMATCH,
                facts = base.facts.copy(metadata = mapOf("extension-id" to otherId)),
            ),
            Case(
                ImportFailureCode.ACTIVITY_MISMATCH,
                facts = base.facts.copy(
                    activities = listOf(
                        ArchiveActivity("Discovery", exported = false, listOf(validFilter)),
                    ),
                ),
            ),
            Case(
                ImportFailureCode.PROVIDER_MISMATCH,
                facts = base.facts.copy(
                    providers = listOf(
                        ArchiveProvider("Provider", "${base.facts.packageName}.wrong", true),
                    ),
                ),
            ),
            Case(
                ImportFailureCode.CRX_HASH_MISMATCH,
                descriptor = base.descriptor.copy(crxSha256 = "0".repeat(64)),
            ),
            Case(
                ImportFailureCode.CRX_ID_MISMATCH,
                descriptor = base.descriptor.copy(
                    extensionId = otherId,
                    wrapperPackage = otherPackage,
                ),
                report = base.report.copy(convertedExtensionId = otherId),
                facts = base.facts.copy(
                    packageName = otherPackage,
                    metadata = mapOf("extension-id" to otherId),
                    providers = listOf(
                        ArchiveProvider("Provider", "$otherPackage.contentprovider", true),
                    ),
                ),
            ),
            Case(
                ImportFailureCode.REPORT_SCHEMA_UNSUPPORTED,
                report = base.report.copy(
                    schemaVersion = 2,
                    sourceSha256 = null,
                    browserProfileSha256 = null,
                ),
            ),
            Case(
                ImportFailureCode.REPORT_IDENTITY_MISMATCH,
                report = base.report.copy(convertedExtensionId = otherId),
            ),
            Case(
                ImportFailureCode.REPORT_PROOF_INVALID,
                report = base.report.copy(sourceSha256 = "A".repeat(64)),
            ),
            Case(
                ImportFailureCode.CRX_INVALID,
                facts = base.facts.copy(crxBytes = byteArrayOf(1, 2, 3)),
            ),
        )

        cases.forEach { case ->
            val result = verifier(case.facts ?: base.facts).verify(
                base.wrapperFile,
                case.descriptor ?: base.descriptor,
                case.report ?: base.report,
            )
            val failure = assertIs<WrapperVerificationResult.Failure>(
                result,
                "expected ${case.expected}",
            )
            assertEquals(case.expected, failure.reason.code)
        }
    }

    @Test
    fun `requires VIEW and BROWSABLE and six ex on the same exported intent filter`() {
        val base = fixture()
        val split = base.facts.copy(
            activities = listOf(
                ArchiveActivity(
                    name = "Discovery",
                    exported = true,
                    intentFilters = listOf(
                        ArchiveIntentFilter(
                            actions = setOf("android.intent.action.VIEW"),
                            categories = emptySet(),
                            schemes = setOf("six"),
                            hosts = setOf("ex"),
                        ),
                        ArchiveIntentFilter(
                            actions = emptySet(),
                            categories = setOf("android.intent.category.BROWSABLE"),
                            schemes = setOf("six"),
                            hosts = setOf("ex"),
                        ),
                    ),
                ),
            ),
        )

        val result = verifier(split).verify(base.wrapperFile, base.descriptor, base.report)

        assertEquals(
            ImportFailureCode.ACTIVITY_MISMATCH,
            assertIs<WrapperVerificationResult.Failure>(result).reason.code,
        )
    }

    @Test
    fun `rejects path aliases and bounded overflow for embedded CRX entry`() {
        val crx = fixture().facts.crxBytes
        val aliasApk = zipFile(
            "assets/extension.crx" to crx,
            "assets/./extension.crx" to crx,
        )
        val oversizedApk = zipFile("assets/extension.crx" to byteArrayOf(1, 2, 3))

        val alias = assertFailsWith<ArchiveInspectionException> {
            ApkArchiveEntries.readUniqueEntry(
                aliasApk,
                "assets/extension.crx",
                1024,
            )
        }
        val oversized = assertFailsWith<ArchiveInspectionException> {
            ApkArchiveEntries.readUniqueEntry(
                oversizedApk,
                "assets/extension.crx",
                2,
            )
        }

        assertEquals(ImportFailureCode.CRX_ENTRY_INVALID, alias.failure.code)
        assertEquals(ImportFailureCode.CRX_TOO_LARGE, oversized.failure.code)
    }

    @Test
    fun `parses a reproducible real wrapper binary manifest matching aapt2 golden facts`() {
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val packageName = "dev.sixbridge.ext.$extensionId"
        val output = buildBinaryManifestFixture(
            packageName = packageName,
            extensionId = extensionId,
        )
        val manifestBytes = ZipFile(output.toFile()).use { apk ->
            apk.getInputStream(apk.getEntry("AndroidManifest.xml")).readBytes()
        }
        val parsed = BinaryAndroidManifestReader().read(manifestBytes)
        val xmlTree = runCommand(
            aapt2().toString(),
            "dump",
            "xmltree",
            output.toString(),
            "--file",
            "AndroidManifest.xml",
        )

        assertEquals(parseAaptFacts(xmlTree), parsed)
    }

    @Test
    fun `rejects truncated binary manifest chunks`() {
        val error = assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestReader().read(byteArrayOf(3, 0, 8, 0, 8, 0, 0))
        }

        assertContains(error.message.orEmpty(), "manifest")
    }

    @Test
    fun `rejects an Android attribute name whose resource map ID was changed`() {
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val apk = buildBinaryManifestFixture(
            packageName = "dev.sixbridge.ext.$extensionId",
            extensionId = extensionId,
        )
        val manifest = ZipFile(apk.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        }
        val corrupted = replaceResourceMapId(
            manifest,
            from = 0x01010010,
            to = 0x0101000e,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestReader().read(corrupted)
        }

        assertContains(error.message.orEmpty(), "resource map")
    }

    @Test
    fun `rejects a verified Android resource ID moved to another namespace`() {
        val extensionId = "abcdefghijklmnopabcdefghijklmnop"
        val apk = buildBinaryManifestFixture(
            packageName = "dev.sixbridge.ext.$extensionId",
            extensionId = extensionId,
        )
        val manifest = ZipFile(apk.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        }
        val corrupted = clearNamespaceForResourceId(manifest, 0x01010027)

        val error = assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestReader().read(corrupted)
        }

        assertContains(error.message.orEmpty(), "resource map")
    }

    private fun verifier(facts: ArchiveFacts): AndroidWrapperVerifier =
        AndroidWrapperVerifier(ArchiveInspector { facts })

    private fun fixture(): WrapperFixture {
        val crxBytes = createTestCrx().bytes
        val crx = Crx3Verifier.verify(crxBytes)
        val wrapperFile = Files.createTempFile("six-wrapper", ".apk").toFile().apply {
            writeBytes("verified wrapper".encodeToByteArray())
        }
        val packageName = "dev.sixbridge.ext.${crx.extensionId}"
        val signer = "1".repeat(64)
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = crx.extensionId,
            name = "Fixture",
            version = "1.0",
            sourceKind = "CRX3",
            crxSha256 = crx.crxSha256,
            wrapperSha256 = sha256(wrapperFile.readBytes()),
            wrapperPackage = packageName,
            wrapperVersionCode = 7,
            signerSha256 = signer,
        )
        val report = ContractCompatibilityReport(
            schemaVersion = 3,
            status = ContractCompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = descriptor.name,
            extensionVersion = descriptor.version,
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "converted",
            adapterDecisions = emptyList(),
            originalExtensionId = null,
            convertedExtensionId = descriptor.extensionId,
            sourceSha256 = "2".repeat(64),
            browserProfileSha256 = "3".repeat(64),
        )
        val facts = ArchiveFacts(
            packageName = packageName,
            versionCode = 7,
            signerSha256 = signer,
            metadata = mapOf("extension-id" to descriptor.extensionId),
            activities = listOf(
                ArchiveActivity(
                    "Discovery",
                    exported = true,
                    intentFilters = listOf(
                        ArchiveIntentFilter(
                            actions = setOf("android.intent.action.VIEW"),
                            categories = setOf("android.intent.category.BROWSABLE"),
                            schemes = setOf("six"),
                            hosts = setOf("ex"),
                        ),
                    ),
                ),
            ),
            providers = listOf(
                ArchiveProvider(
                    "Provider",
                    "$packageName.contentprovider",
                    exported = true,
                ),
            ),
            crxBytes = crxBytes,
        )
        return WrapperFixture(wrapperFile, descriptor, report, facts)
    }

    private fun zipFile(vararg entries: Pair<String, ByteArray>): File =
        Files.createTempFile("six-apk-entries", ".apk").toFile().also { output ->
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun buildBinaryManifestFixture(packageName: String, extensionId: String): Path {
        val work = Files.createTempDirectory("six-manager-binary-manifest")
        val manifest = work.resolve("AndroidManifest.xml")
        val output = work.resolve("wrapper.apk")
        manifest.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName"
                android:versionCode="7"
                android:versionName="1.0.7">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
                <application android:allowBackup="false" android:hasCode="true" android:label="Golden">
                    <meta-data android:name="extension-id" android:value="$extensionId" />
                    <activity
                        android:name="dev.sixbridge.runtime.SixDiscoveryActivity"
                        android:excludeFromRecents="true"
                        android:exported="true"
                        android:theme="@android:style/Theme.NoDisplay">
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="six" android:host="ex" />
                        </intent-filter>
                    </activity>
                    <provider
                        android:name="dev.sixbridge.runtime.ExtensionProvider"
                        android:authorities="$packageName.contentprovider"
                        android:enabled="true"
                        android:exported="true"
                        android:grantUriPermissions="false" />
                </application>
            </manifest>
            """.trimIndent(),
        )
        runCommand(
            aapt2().toString(),
            "link",
            "-o",
            output.toString(),
            "-I",
            androidJar().toString(),
            "--manifest",
            manifest.toString(),
            "--min-sdk-version",
            "30",
            "--target-sdk-version",
            "36",
            "--version-code",
            "7",
            "--version-name",
            "1.0.7",
        )
        return output
    }

    private fun aapt2(): Path = Files.list(SDK_ROOT.resolve("build-tools")).use { versions ->
        versions
            .filter { Files.isRegularFile(it.resolve("aapt2")) }
            .max { left, right ->
                val leftParts = left.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
                val rightParts = right.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
                compareVersionParts(leftParts, rightParts)
            }
            .orElseThrow()
            .resolve("aapt2")
    }

    private fun androidJar(): Path = SDK_ROOT.resolve("platforms/android-36/android.jar")

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { stderr }
        return stdout
    }

    private fun parseAaptFacts(xmlTree: String): BinaryManifestFacts {
        val roots = mutableListOf<DumpNode>()
        val stack = mutableListOf<DumpNode>()
        xmlTree.lineSequence().forEach { line ->
            val indent = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return@forEach
            val content = line.trimStart()
            if (content.startsWith("E: ")) {
                while (stack.lastOrNull()?.indent?.let { it >= indent } == true) {
                    stack.removeAt(stack.lastIndex)
                }
                val node = DumpNode(
                    name = content.removePrefix("E: ").substringBefore(' '),
                    indent = indent,
                )
                stack.lastOrNull()?.children?.add(node) ?: roots.add(node)
                stack += node
            } else if (content.startsWith("A: ")) {
                val assignment = content.removePrefix("A: ")
                val equals = assignment.indexOf('=')
                require(equals > 0)
                val rawName = assignment.substring(0, equals).substringAfterLast(':')
                val name = rawName.substringBefore('(')
                val encodedValue = assignment.substring(equals + 1).substringBefore(" (Raw:")
                stack.last().attributes[name] = encodedValue.removeSurrounding("\"")
            }
        }
        val manifest = roots.single { it.name == "manifest" }
        val application = manifest.children.single { it.name == "application" }
        val metadata = application.children
            .filter { it.name == "meta-data" }
            .associate { it.attributes.getValue("name") to it.attributes.getValue("value") }
        val activities = application.children.filter { it.name == "activity" }.map { node ->
            ArchiveActivity(
                name = node.attributes.getValue("name"),
                exported = node.attributes["exported"] == "true",
                intentFilters = node.children.filter { it.name == "intent-filter" }.map { filter ->
                    ArchiveIntentFilter(
                        actions = filter.children
                            .filter { it.name == "action" }
                            .mapTo(linkedSetOf()) { it.attributes.getValue("name") },
                        categories = filter.children
                            .filter { it.name == "category" }
                            .mapTo(linkedSetOf()) { it.attributes.getValue("name") },
                        schemes = filter.children
                            .filter { it.name == "data" }
                            .mapNotNullTo(linkedSetOf()) { it.attributes["scheme"] },
                        hosts = filter.children
                            .filter { it.name == "data" }
                            .mapNotNullTo(linkedSetOf()) { it.attributes["host"] },
                    )
                },
            )
        }
        val providers = application.children.filter { it.name == "provider" }.map { node ->
            ArchiveProvider(
                name = node.attributes.getValue("name"),
                authority = node.attributes.getValue("authorities"),
                exported = node.attributes["exported"] == "true",
            )
        }
        return BinaryManifestFacts(metadata, activities, providers)
    }

    private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
        repeat(maxOf(left.size, right.size)) { index ->
            val compared = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
            if (compared != 0) return compared
        }
        return 0
    }

    private fun replaceResourceMapId(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val result = bytes.copyOf()
        var offset = result.u16(2)
        while (offset < result.size) {
            val type = result.u16(offset)
            val headerSize = result.u16(offset + 2)
            val size = result.u32(offset + 4)
            if (type == 0x0180) {
                var entry = offset + headerSize
                while (entry < offset + size) {
                    if (result.u32(entry) == from) {
                        ByteBuffer.wrap(result, entry, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(to)
                        return result
                    }
                    entry += 4
                }
            }
            offset += size
        }
        error("resource map ID was not found")
    }

    private fun clearNamespaceForResourceId(bytes: ByteArray, resourceId: Int): ByteArray {
        val result = bytes.copyOf()
        var stringIndex = -1
        var offset = result.u16(2)
        while (offset < result.size) {
            val type = result.u16(offset)
            val headerSize = result.u16(offset + 2)
            val size = result.u32(offset + 4)
            if (type == 0x0180) {
                var entry = offset + headerSize
                while (entry < offset + size) {
                    if (result.u32(entry) == resourceId) {
                        stringIndex = (entry - offset - headerSize) / 4
                        break
                    }
                    entry += 4
                }
            }
            offset += size
        }
        require(stringIndex >= 0)

        offset = result.u16(2)
        while (offset < result.size) {
            val type = result.u16(offset)
            val headerSize = result.u16(offset + 2)
            val size = result.u32(offset + 4)
            if (type == 0x0102) {
                val extension = offset + headerSize
                val attributeStart = result.u16(extension + 8)
                val attributeSize = result.u16(extension + 10)
                val attributeCount = result.u16(extension + 12)
                repeat(attributeCount) { index ->
                    val attribute = extension + attributeStart + index * attributeSize
                    if (result.u32(attribute + 4) == stringIndex) {
                        ByteBuffer.wrap(result, attribute, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(-1)
                        return result
                    }
                }
            }
            offset += size
        }
        error("attribute using resource map ID was not found")
    }

    private fun ByteArray.u16(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun ByteArray.u32(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class WrapperFixture(
        val wrapperFile: File,
        val descriptor: ContractExtensionDescriptor,
        val report: ContractCompatibilityReport,
        val facts: ArchiveFacts,
    )

    private data class Case(
        val expected: ImportFailureCode,
        val descriptor: ContractExtensionDescriptor? = null,
        val report: ContractCompatibilityReport? = null,
        val facts: ArchiveFacts? = null,
    )

    private data class DumpNode(
        val name: String,
        val indent: Int,
        val attributes: MutableMap<String, String> = linkedMapOf(),
        val children: MutableList<DumpNode> = mutableListOf(),
    )

    private companion object {
        val SDK_ROOT: Path = Path.of(
            System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: "/opt/homebrew/share/android-commandlinetools",
        )
    }
}

internal data class TestCrx(
    val extensionId: String,
    val bytes: ByteArray,
)

internal fun createTestCrx(): TestCrx {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val zipBytes = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"manifest_version":3,"name":"Fixture","version":"1.0"}""".encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
    val crxId = MessageDigest.getInstance("SHA-256")
        .digest(keyPair.public.encoded)
        .copyOfRange(0, 16)
    val signedHeaderData = protoBytes(1, crxId)
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(keyPair.private)
        update("CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII))
        update(uint32(signedHeaderData.size))
        update(signedHeaderData)
        update(zipBytes)
        sign()
    }
    val proof = protoBytes(1, keyPair.public.encoded) + protoBytes(2, signature)
    val header = protoBytes(2, proof) + protoBytes(10_000, signedHeaderData)
    val bytes = byteArrayOf(
        'C'.code.toByte(),
        'r'.code.toByte(),
        '2'.code.toByte(),
        '4'.code.toByte(),
    ) + uint32(3) + uint32(header.size) + header + zipBytes
    val extensionId = crxId.joinToString("") { byte ->
        val value = byte.toInt() and 0xff
        "${('a'.code + (value ushr 4)).toChar()}${('a'.code + (value and 15)).toChar()}"
    }
    return TestCrx(extensionId, bytes)
}

private fun protoBytes(field: Int, value: ByteArray): ByteArray =
    ByteArrayOutputStream().run {
        writeVarInt((field.toLong() shl 3) or 2)
        writeVarInt(value.size.toLong())
        write(value)
        toByteArray()
    }

private fun ByteArrayOutputStream.writeVarInt(value: Long) {
    var current = value
    while (true) {
        if (current and 0x7f.inv().toLong() == 0L) {
            write(current.toInt())
            return
        }
        write((current.toInt() and 0x7f) or 0x80)
        current = current ushr 7
    }
}

private fun uint32(value: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
