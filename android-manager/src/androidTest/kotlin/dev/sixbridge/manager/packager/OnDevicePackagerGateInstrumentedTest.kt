package dev.sixbridge.manager.packager

import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractSourceEvidence
import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.conversion.ConversionIdentityLedger
import dev.sixbridge.manager.conversion.ConversionIdentityReservation
import dev.sixbridge.manager.conversion.ConversionPackageBinding
import dev.sixbridge.manager.conversion.InstallationIdentityKey
import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.conversion.LogicalSourceIdentity
import dev.sixbridge.manager.conversion.PackagingConversionIdentity
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OnDevicePackagerGateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testContext = instrumentation.context
    private val root = File(context.filesDir, "ondevice-packager-gate")

    @Test
    fun packagesVerifiedWrapperWithExactDescriptor() {
        val source = canonicalSourceZip()
        val packager = OnDeviceWrapperPackager(context)
        val v1 = File(root, "orchestration-v1.apk")
        val v2 = File(root, "orchestration-v2.apk")
        val first = packageClaim(packager, request(packager, source, 1, v1))
        val second = packageClaim(packager, request(packager, source, 2, v2))

        assertEquals(first.extensionId, second.extensionId)
        assertEquals(first.packageName, second.packageName)
        assertEquals(first.signerSha256, second.signerSha256)
        assertEquals(first.crxSha256, second.crxSha256)
        assertEquals(1, first.versionCode)
        assertEquals(2, second.versionCode)
        assertEquals(first.crxSha256, first.descriptor.crxSha256)
        assertEquals(first.wrapperSha256, first.descriptor.wrapperSha256)
        assertEquals(first.signerSha256, first.descriptor.signerSha256)
        assertEquals(first.extensionId, first.compatibility.convertedExtensionId)
        assertEquals(ContractCompatibilityStatus.PARTIAL, first.compatibility.status)
        assertEquals(
            "desktop-selection-event-bridge",
            first.compatibility.adapterDecisions.single().id,
        )
        assertTrue(first.outputApk.isFile)
        val verified = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager)).verify(
            first.outputApk,
            first.descriptor,
            first.compatibility,
        )
        assertTrue(verified is WrapperVerificationResult.Success)
        val verifiedWrapper = (verified as WrapperVerificationResult.Success).value
        assertEquals("${first.packageName}.contentprovider", verifiedWrapper.providerAuthority)
        assertEquals(1L, verifiedWrapper.versionCode)
        val corruptedDescriptor = first.descriptor.copy(crxSha256 = first.wrapperSha256)
        val corrupted = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager)).verify(
            first.outputApk,
            corruptedDescriptor,
            first.compatibility,
        )
        assertTrue(corrupted is WrapperVerificationResult.Failure)

        val invalidSource = request(
            packager,
            source,
            3,
            File(root, "invalid-source.apk"),
        ).let { claimed ->
            claimed.copy(
                request = claimed.request.copy(
                    compatibility = claimed.request.compatibility.copy(
                        sourceSha256 = "0".repeat(64),
                    ),
                ),
            )
        }
        assertRejected { packageClaim(packager, invalidSource) }
        assertFalse(invalidSource.request.outputApk.exists())
        val invalidProfile = request(
            packager,
            source,
            3,
            File(root, "invalid-profile.apk"),
        ).let { claimed ->
            claimed.copy(
                request = claimed.request.copy(
                    compatibility = claimed.request.compatibility.copy(
                        browserProfileSha256 = "z".repeat(64),
                    ),
                ),
            )
        }
        assertRejected { packageClaim(packager, invalidProfile) }
        assertFalse(invalidProfile.request.outputApk.exists())

        val target = File(root, "symlink-target.apk")
        val symlink = File(root, "symlink-output.apk")
        Files.deleteIfExists(symlink.toPath())
        Files.createSymbolicLink(symlink.toPath(), target.toPath())
        assertRejected { packageClaim(packager, request(packager, source, 3, symlink)) }
        assertFalse(target.exists())
        Files.deleteIfExists(symlink.toPath())
    }

    @Test
    fun snapshotsMutableSourceBeforeValidationAndCrxCreation() {
        val originalZip = canonicalSourceZip()
        val mutableZip = originalZip.copyOf()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                afterRequestValidated = {
                    mutableZip.fill(0x5a)
                },
            ),
        )
        val result = packageClaim(
            packager,
            request(packager, mutableZip, 4, File(root, "snapshot.apk")),
        )
        val expected = Crx3Writer.create(
            originalZip,
            AndroidKeystoreIdentityStore().crxSigningKey(CONVERTED_KEY.identitySha256),
        )
        val facts = AndroidArchiveInspector(context.packageManager).inspect(result.outputApk)
        val crx = Crx3Verifier.verify(facts.crxBytes)

        assertEquals(sha256(originalZip), result.compatibility.sourceSha256)
        assertEquals(expected.extensionId, result.extensionId)
        assertEquals(expected.extensionId, crx.extensionId)
        assertTrue(
            originalZip.contentEquals(
                facts.crxBytes.copyOfRange(crx.zipOffset, crx.zipOffset + crx.zipLength),
            ),
        )
    }

    @Test
    fun validationFailureCleansStagedSourceAndGeneratedCrx() {
        val temporaryPrefixes = listOf(".source.", ".extension.", ".wrapper-template.", ".unsigned.", ".signed.")
        root.listFiles().orEmpty()
            .filter { file -> temporaryPrefixes.any(file.name::startsWith) }
            .forEach(File::delete)
        val before = root.listFiles().orEmpty().map(File::getName).toSet()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                afterRequestValidated = {
                    throw IllegalArgumentException("test validation boundary failure")
                },
            ),
        )

        assertRejected {
            packageClaim(
                packager,
                request(packager, canonicalSourceZip(), 41, File(root, "cleanup-failure.apk")),
            )
        }

        assertEquals(before, root.listFiles().orEmpty().map(File::getName).toSet())
    }

    @Test
    fun passthroughPreservesOriginalCrxIdentityBytesAndDoesNotCreateCrxKey() {
        val originalCrx = externalSignedCrx(canonicalSourceZip())
        val proof = Crx3Verifier.verify(originalCrx)
        val identityKey = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker(
                UUID.randomUUID().toString().replace("-", ""),
            ),
            profileMajorVersion = 28,
            mode = InstallationIdentityMode.PASSTHROUGH,
        )
        val forbiddenAlias = "sixbridge.crx.rsa.v1.${identityKey.identitySha256}"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(keyStore.containsAlias(forbiddenAlias))
        val packager = OnDeviceWrapperPackager(context)
        val identity = packager.resolveIdentity(identityKey, originalCrx)

        assertEquals(proof.extensionId, identity.extensionId)
        assertNull(identity.crxKeyAlias)
        assertNull(identity.crxPublicKeyDer)
        val report = compatibility(
            extensionId = proof.extensionId,
            sourceSha256 = proof.zipSha256,
            conversionState = "passthrough",
            originalExtensionId = proof.extensionId,
        )
        val output = File(root, "passthrough.apk")
        val result = packageClaim(
            packager,
            passthroughRequest(
                identityKey = identityKey,
                identity = identity,
                originalCrx = originalCrx,
                report = report,
                output = output,
            ),
        )
        val embedded = AndroidArchiveInspector(context.packageManager)
            .inspect(result.outputApk)
            .crxBytes

        assertArrayEquals(originalCrx, embedded)
        assertEquals(proof.extensionId, result.extensionId)
        assertEquals(report, result.compatibility)
        assertFalse(keyStore.containsAlias(forbiddenAlias))
    }

    @Test
    fun unreservedCreateNewLeaseRecoversAcrossRestartAndReservationConsumesOwner() {
        val identityKey = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker(
                UUID.randomUUID().toString().replace("-", ""),
            ),
            profileMajorVersion = 28,
            mode = InstallationIdentityMode.CONVERTED,
        )
        val database = Room.inMemoryDatabaseBuilder(context, ManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val packager = OnDeviceWrapperPackager(context)
        val lease = runBlocking { ledger.resolveUnreservedIdentity(identityKey, packager) }
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = requireNotNull(lease.identity.crxKeyAlias)
        assertTrue(keyStore.containsAlias(alias))

        val restartedPackager = OnDeviceWrapperPackager(context)
        val recovered = runBlocking {
            ledger.resolveUnreservedIdentity(identityKey, restartedPackager)
        }
        assertEquals(lease.identitySha256, recovered.identitySha256)
        val source = canonicalSourceZip()
        val reserved = runBlocking {
            ledger.reserve(
                ConversionIdentityReservation(
                    identityKey = identityKey,
                    displayName = "lease recovery",
                    packageBinding = binding(recovered.identity),
                    installedVersionCode = null,
                ),
                recovered,
            )
        }
        val capability = runBlocking { ledger.claimForPackaging(reserved) }
        var succeeded = false
        try {
            restartedPackager.packageWrapper(
            OnDeviceWrapperRequest(
                reservation = capability,
                canonicalSourceZip = source,
                passthroughCrx = null,
                descriptorName = "SIX Bridge On-Device Gate",
                descriptorVersion = "1.0",
                sourceKind = "ZIP",
                compatibility = compatibility(
                    extensionId = recovered.identity.extensionId,
                    sourceSha256 = sha256(source),
                    conversionState = "converted",
                    originalExtensionId = ORIGINAL_EXTENSION_ID,
                ),
                outputApk = File(root, "lease-reserved.apk"),
            ),
            ).also { succeeded = true }
        } finally {
            runBlocking { ledger.finishPackaging(capability, succeeded) }
        }
        assertTrue(keyStore.containsAlias(alias))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ledger.resolveUnreservedIdentity(identityKey, restartedPackager) }
        }
        database.close()
    }

    @Test
    fun cleaningLeaseRecoversBeforeAndAfterKeystoreDeletion() {
        repeat(2) { iteration ->
            val deleteBeforeRestart = iteration == 1
            val identityKey = InstallationIdentityKey.create(
                LogicalSourceIdentity.projectMarker(
                    UUID.randomUUID().toString().replace("-", ""),
                ),
                profileMajorVersion = 28,
                mode = InstallationIdentityMode.CONVERTED,
            )
            val database = Room.inMemoryDatabaseBuilder(context, ManagerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val firstLedger = ConversionIdentityLedger(database.conversionIdentityDao())
            val firstPackager = OnDeviceWrapperPackager(context)
            val lease = runBlocking {
                firstLedger.resolveUnreservedIdentity(identityKey, firstPackager)
            }
            val alias = requireNotNull(lease.identity.crxKeyAlias)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(keyStore.containsAlias(alias))
            runBlocking {
                database.conversionIdentityDao().claimCleanup(
                    identityKey.identitySha256,
                    lease.ownerToken.toString(),
                )
            }
            if (deleteBeforeRestart) {
                assertTrue(
                    firstPackager.deleteUnreservedIdentity(
                        identitySha256 = identityKey.identitySha256,
                        expectedAlias = alias,
                        expectedExtensionId = lease.identity.extensionId,
                        expectedPublicKeySha256 = sha256(requireNotNull(lease.identity.crxPublicKeyDer)),
                    ),
                )
            }

            val restartedLedger = ConversionIdentityLedger(database.conversionIdentityDao())
            val restartedPackager = OnDeviceWrapperPackager(context)
            assertTrue(
                runBlocking {
                    restartedLedger.resumeUnreservedIdentityCleanup(
                        identityKey,
                        restartedPackager,
                    )
                },
            )
            assertFalse(keyStore.containsAlias(alias))
            assertNull(
                runBlocking {
                    database.conversionIdentityDao().getLease(identityKey.identitySha256)
                },
            )
            database.close()
        }
    }

    @Test
    fun packagerStartupReconcilesOnlyOwnedDirectTemporaryFiles() {
        val temporaryRoot = File(root, "tmp").apply { mkdirs() }
        val owned = listOf(
            ".source.101.bin",
            ".extension.102.crx",
            ".wrapper-template.103.apk",
            ".unsigned.104.apk",
            ".signed.105.apk",
            "..unsigned.104.apk.201.tmp",
            "..signed.105.apk.202.tmp",
        ).map { File(temporaryRoot, it).apply { writeText("orphan") } }
        val nonOwnedTemporary = File(temporaryRoot, "..signed.105.apk.keep.202.tmp").apply {
            writeText("not-owned")
        }
        val unrelated = File(root, "keep.apk").apply { writeText("keep") }
        val reservedPrefixFinal = File(root, ".signed.release.apk").apply {
            writeText("final")
        }

        OnDeviceWrapperPackager(context)

        owned.forEach { assertFalse(it.exists()) }
        assertTrue(nonOwnedTemporary.exists())
        assertTrue(unrelated.exists())
        assertTrue(reservedPrefixFinal.exists())
    }

    @Test
    fun rootReplacementBeforeFinalPublishIsRejectedAndPreservesExistingOutput() {
        assertTrue(root.mkdirs() || root.isDirectory)
        val existing = File(root, "preserved.apk").apply { writeText("keep") }
        val savedRoot = File(context.filesDir, "ondevice-packager-gate-saved")
        val replacement = File(context.filesDir, "ondevice-packager-gate-replacement")
        Files.deleteIfExists(savedRoot.toPath())
        replacement.deleteRecursively()
        val packager = OnDeviceWrapperPackager(
            context,
            OnDevicePackagerTestHooks(
                beforeFinalPublish = {
                    Files.move(root.toPath(), savedRoot.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    assertTrue(replacement.mkdirs())
                    Files.createSymbolicLink(root.toPath(), replacement.toPath())
                },
            ),
        )
        try {
            assertRejected {
                packageClaim(packager, request(packager, canonicalSourceZip(), 5, existing))
            }
            assertFalse(File(replacement, "preserved.apk").exists())
            assertEquals("keep", File(savedRoot, "preserved.apk").readText())
        } finally {
            Files.deleteIfExists(root.toPath())
            replacement.deleteRecursively()
            if (savedRoot.exists()) Files.move(savedRoot.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    @Test
    fun generateV1AndPersistIdentityEvidence() {
        val source = canonicalSourceZip()
        val output = File(root, "gate-v1.apk")
        val packager = OnDeviceWrapperPackager(context)
        context.deleteDatabase(GATE_LEDGER_DATABASE)
        val result = packageClaim(
            packager,
            request(packager, source, 1, output, GATE_LEDGER_DATABASE),
        )
        val alias = "sixbridge.crx.rsa.v1.${CONVERTED_KEY.identitySha256}"
        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        assertNull(entry.privateKey.encoded)
        assertEquals(KeyProperties.KEY_ALGORITHM_RSA, entry.privateKey.algorithm)
        writeJson(
            File(root, "gate-state.json"),
            """{"schemaVersion":1,"extensionId":"${result.extensionId}","packageName":"${result.packageName}","wrapperSignerSha256":"${result.signerSha256}","crxSha256":"${result.crxSha256}","v1Sha256":"${result.wrapperSha256}"}""",
        )
        assertTrue(output.isFile)
    }

    @Test
    fun generateV2AfterProcessRestartAndMatchIdentity() {
        val state = File(root, "gate-state.json")
        assertTrue("v1 계측 method를 먼저 실행해야 합니다.", state.isFile)
        val old = state.readText()
        val v1 = File(root, "gate-v1.apk")
        assertTrue("v1 wrapper APK가 있어야 합니다.", v1.isFile)
        val source = canonicalSourceZip()
        val v2 = File(root, "gate-v2.apk")
        val packager = OnDeviceWrapperPackager(context)
        val result = packageClaim(
            packager,
            request(packager, source, 2, v2, GATE_LEDGER_DATABASE),
        )
        assertTrue(old.contains("\"extensionId\":\"${result.extensionId}\""))
        assertTrue(old.contains("\"packageName\":\"${result.packageName}\""))
        assertTrue(old.contains("\"wrapperSignerSha256\":\"${result.signerSha256}\""))
        assertTrue(old.contains("\"crxSha256\":\"${result.crxSha256}\""))
        val v1Hash = jsonField(old, "v1Sha256")
        assertNotNull(v1Hash)
        assertEquals(v1Hash, sha256(v1.readBytes()))
        assertNotEquals(v1Hash, sha256(v2.readBytes()))
        val verifier = AndroidWrapperVerifier(AndroidArchiveInspector(context.packageManager))
        val v1Descriptor = result.descriptor.copy(
            wrapperSha256 = requireNotNull(v1Hash),
            wrapperVersionCode = 1,
        )
        val verifiedV1 = verifier.verify(v1, v1Descriptor, result.compatibility)
        val verifiedV2 = verifier.verify(v2, result.descriptor, result.compatibility)
        assertTrue(verifiedV1 is WrapperVerificationResult.Success)
        assertTrue(verifiedV2 is WrapperVerificationResult.Success)
        val v1Artifact = (verifiedV1 as WrapperVerificationResult.Success).value
        val v2Artifact = (verifiedV2 as WrapperVerificationResult.Success).value
        assertEquals(v1Artifact.extensionId, v2Artifact.extensionId)
        assertEquals(v1Artifact.packageName, v2Artifact.packageName)
        assertEquals(v1Artifact.signerSha256, v2Artifact.signerSha256)
        assertEquals(1L, v1Artifact.versionCode)
        assertEquals(2L, v2Artifact.versionCode)
        writeJson(
            File(root, "gate-result.json"),
            """{"schemaVersion":1,"v1":{"file":"gate-v1.apk","sha256":"${v1Artifact.wrapperSha256}","versionCode":${v1Artifact.versionCode}},"v2":{"file":"gate-v2.apk","sha256":"${v2Artifact.wrapperSha256}","versionCode":${v2Artifact.versionCode}}}""",
        )
        assertTrue(v2.isFile)
    }

    private fun request(
        packager: OnDeviceWrapperPackager,
        source: ByteArray,
        versionCode: Int,
        output: File,
        persistentDatabaseName: String? = null,
    ): ClaimedRequest {
        val database = if (persistentDatabaseName == null) {
            Room.inMemoryDatabaseBuilder(context, ManagerDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        } else {
            Room.databaseBuilder(context, ManagerDatabase::class.java, persistentDatabaseName)
                .addMigrations(
                    ManagerDatabase.MIGRATION_1_2,
                    ManagerDatabase.MIGRATION_2_3,
                    ManagerDatabase.MIGRATION_3_4,
                )
                .allowMainThreadQueries()
                .build()
        }
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        return try {
            runBlocking {
                val selected = ledger.resolveSelection(
                    logicalSourceIdentity = CONVERTED_KEY.logicalSourceIdentity,
                    profileMajorVersion = CONVERTED_KEY.profileMajorVersion,
                    mode = InstallationIdentityMode.CONVERTED,
                    selection = null,
                )
                val existing = database.conversionIdentityDao().get(selected.identitySha256)
                val lease = if (existing == null) {
                    ledger.resolveUnreservedIdentity(selected, packager)
                } else {
                    null
                }
                val identity = lease?.identity ?: packager.resolveIdentity(selected, null)
                val publicKeySha256 = sha256(requireNotNull(identity.crxPublicKeyDer))
                val reserved = ledger.reserve(
                    request = ConversionIdentityReservation(
                        identityKey = selected,
                        displayName = "SIX Bridge On-Device Gate",
                        packageBinding = ConversionPackageBinding(
                            extensionId = identity.extensionId,
                            packageName = identity.packageName,
                            wrapperSignerSha256 = identity.wrapperSignerSha256,
                            crxKeyAlias = identity.crxKeyAlias,
                            crxPublicKeySha256 = publicKeySha256,
                        ),
                        installedVersionCode = (versionCode - 1).toLong(),
                    ),
                    unreservedLease = lease,
                )
                assertEquals(versionCode, reserved.versionCode)
                val capability = ledger.claimForPackaging(reserved)
                ClaimedRequest(
                    request = OnDeviceWrapperRequest(
                        reservation = capability,
                        canonicalSourceZip = source,
                        passthroughCrx = null,
                        descriptorName = "SIX Bridge On-Device Gate",
                        descriptorVersion = "1.0",
                        sourceKind = "ZIP",
                        compatibility = compatibility(
                            extensionId = identity.extensionId,
                            sourceSha256 = sha256(source),
                            conversionState = "converted",
                            originalExtensionId = ORIGINAL_EXTENSION_ID,
                        ),
                        outputApk = output,
                    ),
                    ledger = ledger,
                    capability = capability,
                    database = database,
                )
            }
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }

    private fun passthroughRequest(
        identityKey: InstallationIdentityKey,
        identity: OnDevicePackageIdentity,
        originalCrx: ByteArray,
        report: ContractCompatibilityReport,
        output: File,
    ): ClaimedRequest {
        val database = Room.inMemoryDatabaseBuilder(context, ManagerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        return try {
            runBlocking {
                val selected = ledger.resolveSelection(
                    logicalSourceIdentity = identityKey.logicalSourceIdentity,
                    profileMajorVersion = identityKey.profileMajorVersion,
                    mode = InstallationIdentityMode.PASSTHROUGH,
                    selection = null,
                )
                val reserved = ledger.reserve(
                    ConversionIdentityReservation(
                        identityKey = selected,
                        displayName = "SIX Bridge On-Device Gate",
                        packageBinding = binding(identity),
                        installedVersionCode = null,
                    ),
                )
                val capability = ledger.claimForPackaging(reserved)
                ClaimedRequest(
                    request = OnDeviceWrapperRequest(
                        reservation = capability,
                        canonicalSourceZip = null,
                        passthroughCrx = originalCrx,
                        descriptorName = "SIX Bridge On-Device Gate",
                        descriptorVersion = "1.0",
                        sourceKind = "CRX3",
                        compatibility = report,
                        outputApk = output,
                    ),
                    ledger = ledger,
                    capability = capability,
                    database = database,
                )
            }
        } catch (error: Throwable) {
            database.close()
            throw error
        }
    }

    private fun binding(identity: OnDevicePackageIdentity) = ConversionPackageBinding(
        extensionId = identity.extensionId,
        packageName = identity.packageName,
        wrapperSignerSha256 = identity.wrapperSignerSha256,
        crxKeyAlias = identity.crxKeyAlias,
        crxPublicKeySha256 = identity.crxPublicKeyDer?.let(::sha256),
    )

    private fun packageClaim(
        packager: OnDeviceWrapperPackager,
        claimed: ClaimedRequest,
    ): OnDeviceWrapperResult {
        var succeeded = false
        return try {
            packager.packageWrapper(claimed.request).also { succeeded = true }
        } finally {
            runBlocking { claimed.ledger.finishPackaging(claimed.capability, succeeded) }
            claimed.database.close()
        }
    }

    private data class ClaimedRequest(
        val request: OnDeviceWrapperRequest,
        val ledger: ConversionIdentityLedger,
        val capability: PackagingConversionIdentity,
        val database: ManagerDatabase,
    )

    private fun compatibility(
        extensionId: String,
        sourceSha256: String,
        conversionState: String,
        originalExtensionId: String,
    ) = ContractCompatibilityReport(
        schemaVersion = 4,
        status = ContractCompatibilityStatus.PARTIAL,
        manifestVersion = 3,
        extensionName = "SIX Bridge On-Device Gate",
        extensionVersion = "1.0",
        manifestKeyPresent = false,
        manifestKeyExtensionId = null,
        declaredPermissions = listOf("storage"),
        usedApis = listOf("chrome.storage.local"),
        unsupportedApis = listOf("chrome.desktopCapture"),
        issues = emptyList(),
        conversionState = conversionState,
        adapterDecisions = listOf(
            ContractAdapterDecision(
                id = "desktop-selection-event-bridge",
                version = 1,
                risk = "behavior-change",
                disposition = "requires-approval",
                applied = true,
                evidence = listOf(
                    ContractSourceEvidence(
                        relativePath = "content.js",
                        line = 1,
                        detail = "desktop selection event listener",
                    ),
                ),
                changes = listOf("selection event bridge"),
                remainingLimitations = listOf("desktop selection UI unavailable"),
            ),
        ),
        originalExtensionId = originalExtensionId,
        convertedExtensionId = extensionId,
        sourceSha256 = sourceSha256,
        browserProfileSha256 = PROFILE_SHA256,
        originalSourceSha256 = "a".repeat(64),
        planSha256 = "b".repeat(64),
    )

    private fun externalSignedCrx(source: ByteArray): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Crx3Writer.create(
            source,
            object : Crx3SigningKey {
                override fun publicKeyDer(): ByteArray = pair.public.encoded

                override fun signSha256Rsa(payload: ByteArray): ByteArray =
                    Signature.getInstance("SHA256withRSA").run {
                        initSign(pair.private)
                        update(payload)
                        sign()
                    }
            },
        ).bytes
    }

    private fun canonicalSourceZip(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            addEntry(zip, "manifest.json", testContext.assets.open("ondevice-gate/manifest.json").readBytes())
            addEntry(zip, "content.js", testContext.assets.open("ondevice-gate/content.js").readBytes())
        }
        bytes.toByteArray()
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeJson(file: File, value: String) {
        assertTrue(root.mkdirs() || root.isDirectory)
        file.writeText(value)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun jsonField(json: String, name: String): String? =
        Regex("\\\"$name\\\":\\\"([0-9a-f]{64})\\\"").find(json)?.groupValues?.get(1)

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("입력이 거부되어야 합니다.")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private companion object {
        const val GATE_LEDGER_DATABASE = "ondevice-packager-gate-ledger.db"
        const val ORIGINAL_EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop"
        val CONVERTED_KEY = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId(ORIGINAL_EXTENSION_ID),
            profileMajorVersion = 28,
            mode = InstallationIdentityMode.CONVERTED,
        )
        const val PROFILE_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
