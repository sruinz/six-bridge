package dev.sixbridge.manager.wrapper

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.manager.importer.ImportFailureCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
        assertEquals(
            setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
            fixture.facts.activities.single().intentFilters.single().categories,
        )

        val result = verifier(fixture.facts).verify(
            fixture.wrapperFile,
            fixture.descriptor,
            fixture.report,
        )

        val success = assertIs<WrapperVerificationResult.Success>(result)
        assertEquals(fixture.descriptor.extensionId, success.value.extensionId)
        assertEquals(fixture.descriptor.wrapperPackage, success.value.packageName)
        assertEquals(fixture.facts.providers.single().authority, success.value.providerAuthority)
        assertEquals(fixture.descriptor.wrapperSha256, success.value.wrapperSha256)
    }

    @Test
    fun `accepts v4 report when all provenance proofs are valid`() {
        val fixture = fixture()

        val result = verifier(fixture.facts).verify(
            fixture.wrapperFile,
            fixture.descriptor,
            fixture.report.copy(
                schemaVersion = 4,
                originalSourceSha256 = "4".repeat(64),
                planSha256 = "5".repeat(64),
            ),
        )

        assertIs<WrapperVerificationResult.Success>(result)
    }

    @Test
    fun `streaming inspector의 CRX Path를 검증하고 legacy CRX ByteArray를 요구하지 않는다`() {
        val fixture = fixture()
        var pathInspectionCalled = false
        val streamingInspector = object : StreamingArchiveInspector {
            override fun inspect(apk: File): ArchiveFacts =
                error("streaming verifier는 legacy inspect를 호출하면 안 됩니다.")

            override fun inspect(apk: File, crxOutput: File): ArchiveFacts {
                pathInspectionCalled = true
                crxOutput.writeBytes(fixture.facts.crxBytes)
                return fixture.facts.copy(crxBytes = ByteArray(0))
            }
        }

        val result = AndroidWrapperVerifier(streamingInspector).verify(
            fixture.wrapperFile,
            fixture.descriptor,
            fixture.report,
        )

        assertTrue(pathInspectionCalled)
        assertIs<WrapperVerificationResult.Success>(result)
    }

    @Test
    fun `managed temporary root의 process death CRX orphan을 시작 시 정리한다`() {
        val directory = Files.createTempDirectory("wrapper-verifier-managed-temp").toFile()
        try {
            val orphan = File(directory, ".sixbridge-wrapper-crx-interrupted.tmp").apply {
                writeText("sensitive extension payload")
            }

            AndroidWrapperVerifier(
                ArchiveInspector { error("검증하지 않습니다.") },
                directory,
            )

            assertTrue(!orphan.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects a staged APK mutated while archive inspection is running`() {
        val fixture = fixture()
        val verifier = AndroidWrapperVerifier(
            ArchiveInspector { apk ->
                apk.writeBytes("mutated during inspection".encodeToByteArray())
                fixture.facts
            },
        )

        val result = verifier.verify(
            fixture.wrapperFile,
            fixture.descriptor,
            fixture.report,
        )

        assertEquals(
            ImportFailureCode.WRAPPER_HASH_MISMATCH,
            assertIs<WrapperVerificationResult.Failure>(result).reason.code,
        )
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
                        ArchiveActivity(
                            "dev.sixbridge.runtime.SixDiscoveryActivity",
                            exported = false,
                            listOf(validFilter),
                        ),
                    ),
                ),
            ),
            Case(
                ImportFailureCode.PROVIDER_MISMATCH,
                facts = base.facts.copy(
                    providers = listOf(
                        ArchiveProvider(
                            "dev.sixbridge.runtime.ExtensionProvider",
                            "${base.facts.packageName}.wrong",
                            true,
                        ),
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
                        ArchiveProvider(
                            "dev.sixbridge.runtime.ExtensionProvider",
                            "$otherPackage.contentprovider",
                            true,
                        ),
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
                ImportFailureCode.REPORT_PROOF_INVALID,
                report = base.report.copy(sourceSha256 = "2".repeat(64)),
            ),
            Case(
                ImportFailureCode.REPORT_PROOF_INVALID,
                report = base.report.copy(
                    schemaVersion = 4,
                    originalSourceSha256 = "A".repeat(64),
                    planSha256 = "4".repeat(64),
                ),
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
    fun `requires VIEW DEFAULT BROWSABLE and six ex on the same exported intent filter`() {
        val base = fixture()
        val split = base.facts.copy(
            activities = listOf(
                ArchiveActivity(
                    name = "dev.sixbridge.runtime.SixDiscoveryActivity",
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
                            categories = setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
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
    fun `requires the exact runtime activity and provider classes`() {
        val base = fixture()
        val wrongActivity = base.facts.copy(
            activities = listOf(
                base.facts.activities.single().copy(name = "dev.example.DecoyActivity"),
            ),
        )
        val wrongProvider = base.facts.copy(
            providers = listOf(
                base.facts.providers.single().copy(name = "dev.example.DecoyProvider"),
            ),
        )

        val activityResult = verifier(wrongActivity).verify(
            base.wrapperFile,
            base.descriptor,
            base.report,
        )
        val providerResult = verifier(wrongProvider).verify(
            base.wrapperFile,
            base.descriptor,
            base.report,
        )

        assertEquals(
            ImportFailureCode.ACTIVITY_MISMATCH,
            assertIs<WrapperVerificationResult.Failure>(activityResult).reason.code,
        )
        assertEquals(
            ImportFailureCode.PROVIDER_MISMATCH,
            assertIs<WrapperVerificationResult.Failure>(providerResult).reason.code,
        )
    }

    @Test
    fun `rejects missing or extra discovery filter actions categories schemes and hosts`() {
        val base = fixture()
        val activity = base.facts.activities.single()
        val filter = activity.intentFilters.single()
        val invalidFilters = listOf(
            listOf(filter, filter),
            listOf(filter.copy(actions = filter.actions + "android.intent.action.EDIT")),
            listOf(filter.copy(categories = filter.categories - DEFAULT_CATEGORY, categoryCount = 1)),
            listOf(filter.copy(categories = filter.categories - BROWSABLE_CATEGORY, categoryCount = 1)),
            listOf(filter.copy(categories = filter.categories + "android.intent.category.ALTERNATIVE", categoryCount = 3)),
            listOf(filter.copy(schemes = filter.schemes + "https")),
            listOf(filter.copy(hosts = filter.hosts + "other")),
        )

        invalidFilters.forEach { filters ->
            val facts = base.facts.copy(
                activities = listOf(activity.copy(intentFilters = filters)),
            )
            val result = verifier(facts).verify(
                base.wrapperFile,
                base.descriptor,
                base.report,
            )

            assertEquals(
                ImportFailureCode.ACTIVITY_MISMATCH,
                assertIs<WrapperVerificationResult.Failure>(result).reason.code,
            )
        }
    }

    @Test
    fun `rejects duplicate data elements even when their scheme and host are equal`() {
        val base = fixture()
        val manifest = binaryManifest(base.descriptor.extensionId)
        val duplicated = duplicateElement(manifest, "data")
        val parsed = BinaryAndroidManifestReader().read(duplicated)
        val facts = base.facts.copy(
            metadata = parsed.metadata,
            activities = parsed.activities,
            providers = parsed.providers,
        )

        val result = verifier(facts).verify(
            base.wrapperFile,
            base.descriptor,
            base.report,
        )

        assertEquals(
            ImportFailureCode.ACTIVITY_MISMATCH,
            assertIs<WrapperVerificationResult.Failure>(result).reason.code,
        )
    }

    @Test
    fun `APK preflight rejects duplicate canonical names and directory entries`() {
        val duplicate = validApk(
            "assets/extension.cry" to byteArrayOf(2),
        ).also { replaceAscii(it, "assets/extension.cry", "assets/extension.crx") }
        val directory = validApk(
            "res/" to byteArrayOf(),
        )

        listOf(duplicate, directory).forEach { apk ->
            val error = assertFailsWith<ArchiveInspectionException> {
                ApkArchiveEntries.preflight(apk)
            }
            assertEquals(ImportFailureCode.ARCHIVE_INVALID, error.failure.code)
        }
    }

    @Test
    fun `APK preflight rejects compression bombs excessive entry padding and file padding`() {
        val compressionBomb = validApk(
            "res/raw/bomb.bin" to ByteArray(1024 * 1024),
        )
        val excessiveEntries = validApk(
            *Array(65) { index -> "padding-$index.bin" to byteArrayOf(index.toByte()) },
        )
        val paddedFile = validApk().also { apk ->
            RandomAccessFile(apk, "rw").use { file ->
                file.setLength(SixBundleContract.MAX_WRAPPER_SIZE + 1)
            }
        }

        listOf(compressionBomb, excessiveEntries, paddedFile).forEach { apk ->
            val error = assertFailsWith<ArchiveInspectionException> {
                ApkArchiveEntries.preflight(apk)
            }
            assertEquals(ImportFailureCode.ARCHIVE_INVALID, error.failure.code)
        }
    }

    @Test
    fun `APK preflight returns the unique bounded manifest and CRX`() {
        val manifest = byteArrayOf(1, 2, 3)
        val crx = byteArrayOf(4, 5, 6)
        val apk = zipFile(
            "AndroidManifest.xml" to manifest,
            "classes.dex" to byteArrayOf(7),
            "assets/extension.crx" to crx,
        )

        val contents = ApkArchiveEntries.preflight(apk)

        assertTrue(manifest.contentEquals(contents.manifestBytes))
        assertTrue(crx.contentEquals(contents.crxBytes))
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

        assertEquals(
            setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
            parsed.activities.single().intentFilters.single().categories,
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

    @Test
    fun `rejects an application and discovery filter nested under an unknown subtree`() {
        val manifest = binaryManifest()
        val corrupted = moveEndElementAfterSibling(
            manifest,
            wrapperName = "uses-sdk",
            siblingName = "application",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestReader().read(corrupted)
        }

        assertContains(error.message.orEmpty(), "hierarchy")
    }

    @Test
    fun `rejects a duplicate discovery filter hidden in an unknown subtree`() {
        val corrupted = insertActivityInFakeApplication(
            binaryManifest(),
            unknownElement = "uses-sdk",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            BinaryAndroidManifestReader().read(corrupted)
        }

        assertContains(error.message.orEmpty(), "hierarchy")
    }

    @Test
    fun `rejects malformed typed value size reserved byte and divergent raw string`() {
        val manifest = binaryManifest()
        val mutations = listOf(
            mutateTypedAttribute(manifest, 0x01010003) { bytes, offset ->
                ByteBuffer.wrap(bytes, offset + 12, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(7.toShort())
            },
            mutateTypedAttribute(manifest, 0x01010003) { bytes, offset ->
                bytes[offset + 14] = 1
            },
            mutateTypedAttribute(manifest, 0x01010003) { bytes, offset ->
                val nameIndex = bytes.u32(offset + 4)
                ByteBuffer.wrap(bytes, offset + 8, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(nameIndex)
            },
        )

        mutations.forEach { corrupted ->
            val error = assertFailsWith<IllegalArgumentException> {
                BinaryAndroidManifestReader().read(corrupted)
            }
            assertContains(error.message.orEmpty(), "typed attribute")
        }
    }

    private fun verifier(facts: ArchiveFacts): AndroidWrapperVerifier =
        AndroidWrapperVerifier(ArchiveInspector { facts })

    private fun binaryManifest(
        extensionId: String = "abcdefghijklmnopabcdefghijklmnop",
    ): ByteArray {
        val apk = buildBinaryManifestFixture(
            packageName = "dev.sixbridge.ext.$extensionId",
            extensionId = extensionId,
        )
        return ZipFile(apk.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        }
    }

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
            sourceSha256 = crx.zipSha256,
            browserProfileSha256 = "3".repeat(64),
        )
        val facts = ArchiveFacts(
            packageName = packageName,
            versionCode = 7,
            signerSha256 = signer,
            metadata = mapOf("extension-id" to descriptor.extensionId),
            activities = listOf(
                ArchiveActivity(
                    "dev.sixbridge.runtime.SixDiscoveryActivity",
                    exported = true,
                    intentFilters = listOf(
                        ArchiveIntentFilter(
                            actions = setOf("android.intent.action.VIEW"),
                            categories = setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
                            schemes = setOf("six"),
                            hosts = setOf("ex"),
                        ),
                    ),
                ),
            ),
            providers = listOf(
                ArchiveProvider(
                    "dev.sixbridge.runtime.ExtensionProvider",
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

    private fun validApk(vararg extraEntries: Pair<String, ByteArray>): File = zipFile(
        "AndroidManifest.xml" to byteArrayOf(1),
        "assets/extension.crx" to byteArrayOf(1),
        *extraEntries,
    )

    private fun replaceAscii(file: File, from: String, to: String) {
        require(from.length == to.length)
        val bytes = file.readBytes()
        val needle = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        var replacements = 0
        for (offset in 0..bytes.size - needle.size) {
            if (bytes.copyOfRange(offset, offset + needle.size).contentEquals(needle)) {
                replacement.copyInto(bytes, offset)
                replacements += 1
            }
        }
        require(replacements == 2)
        file.writeBytes(bytes)
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
                        actionCount = filter.children.count { it.name == "action" },
                        categoryCount = filter.children.count { it.name == "category" },
                        dataCount = filter.children.count { it.name == "data" },
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
        val usesSdk = manifest.children.single { it.name == "uses-sdk" }
        return BinaryManifestFacts(
            packageName = manifest.attributes.getValue("package"),
            versionCode = aaptInteger(manifest.attributes.getValue("versionCode")),
            minSdkVersion = aaptInteger(usesSdk.attributes.getValue("minSdkVersion")),
            targetSdkVersion = aaptInteger(usesSdk.attributes.getValue("targetSdkVersion")),
            metadata = metadata,
            activities = activities,
            providers = providers,
        )
    }

    private fun aaptInteger(value: String): Int {
        val number = Regex("0x[0-9a-fA-F]+|[0-9]+")
            .findAll(value)
            .lastOrNull()
            ?.value
            ?: error("aapt integer가 없습니다: $value")
        return number.removePrefix("0x").toInt(if (number.startsWith("0x")) 16 else 10)
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

    private fun moveEndElementAfterSibling(
        bytes: ByteArray,
        wrapperName: String,
        siblingName: String,
    ): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val wrapperEnd = chunks.indexOfFirst {
            it.type == END_ELEMENT_TYPE && it.name == wrapperName
        }
        require(wrapperEnd >= 0)
        val chunk = chunks.removeAt(wrapperEnd)
        val siblingEnd = chunks.indexOfLast {
            it.type == END_ELEMENT_TYPE && it.name == siblingName
        }
        require(siblingEnd >= 0)
        chunks.add(siblingEnd + 1, chunk)
        return rebuildXml(bytes, chunks)
    }

    private fun insertActivityInFakeApplication(
        bytes: ByteArray,
        unknownElement: String,
    ): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val applicationStart = chunks.indexOfFirst {
            it.type == START_ELEMENT_TYPE && it.name == "application"
        }
        val applicationEnd = matchingEnd(chunks, applicationStart)
        val activityStart = chunks.indexOfFirst {
            it.type == START_ELEMENT_TYPE && it.name == "activity"
        }
        val activityEnd = matchingEnd(chunks, activityStart)
        val unknownEnd = chunks.indexOfFirst {
            it.type == END_ELEMENT_TYPE && it.name == unknownElement
        }
        require(applicationStart >= 0 && applicationEnd >= 0)
        require(activityStart >= 0 && activityEnd >= activityStart && unknownEnd >= 0)
        val fakeApplication = buildList {
            add(chunks[applicationStart])
            addAll(chunks.subList(activityStart, activityEnd + 1))
            add(chunks[applicationEnd])
        }
        chunks.addAll(unknownEnd, fakeApplication)
        return rebuildXml(bytes, chunks)
    }

    private fun matchingEnd(chunks: List<XmlChunk>, start: Int): Int {
        require(start in chunks.indices && chunks[start].type == START_ELEMENT_TYPE)
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
        error("matching end element was not found")
    }

    private fun mutateTypedAttribute(
        bytes: ByteArray,
        resourceId: Int,
        mutation: (ByteArray, Int) -> Unit,
    ): ByteArray {
        val result = bytes.copyOf()
        val attribute = attributeOffsetForResourceId(result, resourceId)
        mutation(result, attribute)
        return result
    }

    private fun attributeOffsetForResourceId(bytes: ByteArray, resourceId: Int): Int {
        var stringIndex = -1
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            if (type == RESOURCE_MAP_TYPE) {
                var entry = offset + headerSize
                while (entry < offset + size) {
                    if (bytes.u32(entry) == resourceId) {
                        stringIndex = (entry - offset - headerSize) / 4
                        break
                    }
                    entry += 4
                }
            }
            offset += size
        }
        require(stringIndex >= 0)
        offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            if (type == START_ELEMENT_TYPE) {
                val extension = offset + headerSize
                val attributeStart = bytes.u16(extension + 8)
                val attributeSize = bytes.u16(extension + 10)
                val attributeCount = bytes.u16(extension + 12)
                repeat(attributeCount) { index ->
                    val attribute = extension + attributeStart + index * attributeSize
                    if (bytes.u32(attribute + 4) == stringIndex) return attribute
                }
            }
            offset += size
        }
        error("attribute using resource map ID was not found")
    }

    private fun duplicateElement(bytes: ByteArray, name: String): ByteArray {
        val chunks = xmlChunks(bytes).toMutableList()
        val start = chunks.indexOfFirst {
            it.type == START_ELEMENT_TYPE && it.name == name
        }
        val end = matchingEnd(chunks, start)
        require(start >= 0 && end >= start)
        chunks.addAll(end + 1, chunks.subList(start, end + 1).toList())
        return rebuildXml(bytes, chunks)
    }

    private fun xmlChunks(bytes: ByteArray): List<XmlChunk> {
        val stringPool = binaryStringPool(bytes)
        val chunks = mutableListOf<XmlChunk>()
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            val name = if (type == START_ELEMENT_TYPE || type == END_ELEMENT_TYPE) {
                stringPool[bytes.u32(offset + headerSize + 4)]
            } else {
                null
            }
            chunks += XmlChunk(type, name, bytes.copyOfRange(offset, offset + size))
            offset += size
        }
        return chunks
    }

    private fun binaryStringPool(bytes: ByteArray): List<String> {
        var offset = bytes.u16(2)
        while (offset < bytes.size) {
            val type = bytes.u16(offset)
            val headerSize = bytes.u16(offset + 2)
            val size = bytes.u32(offset + 4)
            if (type == STRING_POOL_TYPE) {
                val stringCount = bytes.u32(offset + 8)
                val flags = bytes.u32(offset + 16)
                val stringsStart = offset + bytes.u32(offset + 20)
                return List(stringCount) { index ->
                    var cursor = stringsStart + bytes.u32(offset + headerSize + index * 4)
                    if (flags and UTF8_FLAG != 0) {
                        cursor = readUtf8Length(bytes, cursor).second
                        val byteLength = readUtf8Length(bytes, cursor)
                        cursor = byteLength.second
                        bytes.copyOfRange(cursor, cursor + byteLength.first)
                            .toString(StandardCharsets.UTF_8)
                    } else {
                        val characterLength = readUtf16Length(bytes, cursor)
                        cursor = characterLength.second
                        bytes.copyOfRange(cursor, cursor + characterLength.first * 2)
                            .toString(StandardCharsets.UTF_16LE)
                    }
                }
            }
            offset += size
        }
        error("string pool was not found")
    }

    private fun readUtf8Length(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val first = bytes[offset].toInt() and 0xff
        return if (first and 0x80 == 0) {
            first to offset + 1
        } else {
            (((first and 0x7f) shl 8) or (bytes[offset + 1].toInt() and 0xff)) to offset + 2
        }
    }

    private fun readUtf16Length(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val first = bytes.u16(offset)
        return if (first and 0x8000 == 0) {
            first to offset + 2
        } else {
            (((first and 0x7fff) shl 16) or bytes.u16(offset + 2)) to offset + 4
        }
    }

    private fun rebuildXml(original: ByteArray, chunks: List<XmlChunk>): ByteArray {
        val rootHeaderSize = original.u16(2)
        val output = ByteArrayOutputStream()
        output.write(original, 0, rootHeaderSize)
        chunks.forEach { output.write(it.bytes) }
        return output.toByteArray().also { rebuilt ->
            ByteBuffer.wrap(rebuilt, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(rebuilt.size)
        }
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

    private data class XmlChunk(
        val type: Int,
        val name: String?,
        val bytes: ByteArray,
    )

    private companion object {
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
        const val STRING_POOL_TYPE = 0x0001
        const val RESOURCE_MAP_TYPE = 0x0180
        const val START_ELEMENT_TYPE = 0x0102
        const val END_ELEMENT_TYPE = 0x0103
        const val UTF8_FLAG = 0x00000100
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
