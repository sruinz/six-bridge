package dev.sixbridge.manager.packager

import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractSourceEvidence
import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.manager.conversion.ConversionIdentityDao
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import dev.sixbridge.manager.conversion.ConversionIdentityLeaseEntity
import dev.sixbridge.manager.conversion.ConversionIdentityLedger
import dev.sixbridge.manager.conversion.ConversionIdentityReservation
import dev.sixbridge.manager.conversion.ConversionJobEntity
import dev.sixbridge.manager.conversion.ConversionPackageBinding
import dev.sixbridge.manager.conversion.InstallationIdentityKey
import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.conversion.LogicalSourceIdentity
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import org.junit.Test

class OnDeviceWrapperRequestTest {
    private val canonicalZip = canonicalZip()
    private val passthroughCrx = signedCrx(canonicalZip)
    private val passthroughProof = Crx3Verifier.verify(passthroughCrx)
    private val passthroughKey = identityKey(InstallationIdentityMode.PASSTHROUGH)
    private val convertedKey = identityKey(InstallationIdentityMode.CONVERTED)
    private val preparedKey = identityKey(InstallationIdentityMode.PREPARED)

    @Test
    fun `canonical ZIP과 passthrough CRX를 동시에 또는 모두 없이 받지 않는다`() {
        val identity = packageIdentity(passthroughProof.extensionId)

        assertFailsWith<IllegalArgumentException> {
            passthroughRequest().copy(canonicalSourceZip = canonicalZip).validateFor(
                identity,
                passthroughCrx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            convertedRequest().copy(canonicalSourceZip = null).validateFor(
                identity,
                passthroughCrx,
            )
        }
    }

    @Test
    fun `identity mode와 source 분기가 다르면 거부한다`() {
        val identity = packageIdentity(passthroughProof.extensionId)

        assertFailsWith<IllegalArgumentException> {
            passthroughRequest().copy(
                reservation = reservation(convertedKey, passthroughProof.extensionId),
            ).validateFor(
                identity,
                passthroughCrx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            convertedRequest().copy(
                reservation = reservation(passthroughKey, passthroughProof.extensionId),
            ).validateFor(
                identity,
                passthroughCrx,
            )
        }
    }

    @Test
    fun `실제 compatibility v4의 source와 converted ID가 CRX proof와 일치해야 한다`() {
        val identity = packageIdentity(passthroughProof.extensionId)

        assertFailsWith<IllegalArgumentException> {
            passthroughRequest(
                compatibility = compatibility(
                    extensionId = passthroughProof.extensionId,
                    sourceSha256 = "0".repeat(64),
                ),
            ).validateFor(identity, passthroughCrx)
        }
        assertFailsWith<IllegalArgumentException> {
            passthroughRequest(
                compatibility = compatibility(
                    extensionId = "abcdefghijklmnopabcdefghijklmnop",
                    sourceSha256 = passthroughProof.zipSha256,
                ),
            ).validateFor(identity, passthroughCrx)
        }
    }

    @Test
    fun `descriptor identity와 실제 partial 보고서를 변경하지 않고 승인한다`() {
        val report = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = passthroughProof.zipSha256,
        )
        val request = passthroughRequest(report)
        val validated = request.validateFor(
            packageIdentity(passthroughProof.extensionId),
            passthroughCrx,
        )

        assertEquals(ContractCompatibilityStatus.PARTIAL, validated.compatibility.status)
        assertEquals(
            "desktop-selection-event-bridge",
            validated.compatibility.adapterDecisions.single().id,
        )
        assertEquals(report, validated.compatibility)
    }

    @Test
    fun `compatibility를 canonical JSON 순서로 snapshot한다`() {
        val report = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = passthroughProof.zipSha256,
        ).copy(
            declaredPermissions = listOf("z.permission", "a.permission"),
            usedApis = listOf("chrome.z", "chrome.a"),
            adapterDecisions = listOf(
                compatibility(
                    extensionId = passthroughProof.extensionId,
                    sourceSha256 = passthroughProof.zipSha256,
                ).adapterDecisions.single().copy(id = "z-adapter"),
                compatibility(
                    extensionId = passthroughProof.extensionId,
                    sourceSha256 = passthroughProof.zipSha256,
                ).adapterDecisions.single().copy(id = "a-adapter"),
            ),
        )

        val validated = passthroughRequest(report).validateFor(
            packageIdentity(passthroughProof.extensionId),
            passthroughCrx,
        )

        assertEquals(listOf("a.permission", "z.permission"), validated.compatibility.declaredPermissions)
        assertEquals(listOf("chrome.a", "chrome.z"), validated.compatibility.usedApis)
        assertEquals(listOf("a-adapter", "z-adapter"), validated.compatibility.adapterDecisions.map { it.id })
        assertNotSame(report.declaredPermissions, validated.compatibility.declaredPermissions)
    }

    @Test
    fun `ledger가 발급한 예약 신원의 extension 또는 signer가 다르면 거부한다`() {
        val identity = packageIdentity(passthroughProof.extensionId)
        val request = passthroughRequest()
        listOf(
            reservation(
                passthroughKey,
                "abcdefghijklmnopabcdefghijklmnop",
            ),
            reservation(
                passthroughKey,
                passthroughProof.extensionId,
                wrapperSignerSha256 = "0".repeat(64),
            ),
        ).forEach { changed ->
            assertFailsWith<IllegalArgumentException> {
                request.copy(reservation = changed).validateFor(identity, passthroughCrx)
            }
        }
    }

    @Test
    fun `schema v3 또는 descriptor name version 불일치를 거부한다`() {
        val identity = packageIdentity(passthroughProof.extensionId)
        val report = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = passthroughProof.zipSha256,
        )

        assertFailsWith<IllegalArgumentException> {
            passthroughRequest(report.copy(schemaVersion = 3)).validateFor(
                identity,
                passthroughCrx,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            passthroughRequest(report.copy(extensionName = "다른 이름")).validateFor(
                identity,
                passthroughCrx,
            )
        }
    }

    @Test
    fun `converted canonical ZIP은 passthrough terminal 보고서를 허용한다`() {
        val fixture = convertedRequestFixture(conversionState = "passthrough")

        val validated = fixture.request.validateFor(fixture.identity, fixture.crx)

        assertEquals("passthrough", validated.compatibility.conversionState)
    }

    @Test
    fun `non-terminal compatibility state는 prepared와 converted package를 거부한다`() {
        listOf("automatic", "requires-approval", "unavailable").forEach { conversionState ->
            val converted = convertedRequestFixture(conversionState)
            val preparedIdentity = packageIdentity(passthroughProof.extensionId)
            val prepared = preparedRequest(conversionState)

            assertFailsWith<IllegalArgumentException> {
                converted.request.validateFor(converted.identity, converted.crx)
            }
            assertFailsWith<IllegalArgumentException> {
                prepared.validateFor(preparedIdentity, passthroughCrx)
            }
        }
    }

    private fun passthroughRequest(
        compatibility: ContractCompatibilityReport = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = passthroughProof.zipSha256,
        ),
    ) = OnDeviceWrapperRequest(
        reservation = reservation(passthroughKey, passthroughProof.extensionId),
        canonicalSourceZip = null,
        passthroughCrx = passthroughCrx,
        descriptorName = "SIX 테스트",
        descriptorVersion = "1.0",
        sourceKind = "CRX3",
        compatibility = compatibility,
        outputApk = File("unused.apk"),
    )

    private fun convertedRequest() = OnDeviceWrapperRequest(
        reservation = reservation(convertedKey, passthroughProof.extensionId, "test.alias"),
        canonicalSourceZip = canonicalZip,
        passthroughCrx = null,
        descriptorName = "SIX 테스트",
        descriptorVersion = "1.0",
        sourceKind = "ZIP",
        compatibility = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = sha256(canonicalZip),
        ),
        outputApk = File("unused.apk"),
    )

    private fun preparedRequest(conversionState: String) = OnDeviceWrapperRequest(
        reservation = reservation(preparedKey, passthroughProof.extensionId),
        canonicalSourceZip = null,
        passthroughCrx = passthroughCrx,
        descriptorName = "SIX 테스트",
        descriptorVersion = "1.0",
        sourceKind = "ZIP",
        compatibility = compatibility(
            extensionId = passthroughProof.extensionId,
            sourceSha256 = passthroughProof.zipSha256,
        ).copy(conversionState = conversionState),
        outputApk = File("unused.apk"),
    )

    private fun convertedRequestFixture(
        conversionState: String,
    ): ConvertedRequestFixture {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crx = Crx3Writer.create(
            canonicalZip,
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
        val proof = Crx3Verifier.verify(crx)
        val identity = OnDevicePackageIdentity(
            extensionId = proof.extensionId,
            packageName = "dev.sixbridge.ext.${proof.extensionId}",
            wrapperSignerSha256 = "f".repeat(64),
            crxKeyAlias = "test.alias",
            crxPublicKeyDer = pair.public.encoded,
        )
        return ConvertedRequestFixture(
            request = OnDeviceWrapperRequest(
                reservation = reservation(convertedKey, proof.extensionId, "test.alias"),
                canonicalSourceZip = canonicalZip,
                passthroughCrx = null,
                descriptorName = "SIX 테스트",
                descriptorVersion = "1.0",
                sourceKind = "ZIP",
                compatibility = compatibility(
                    extensionId = proof.extensionId,
                    sourceSha256 = proof.zipSha256,
                ).copy(conversionState = conversionState),
                outputApk = File("unused.apk"),
            ),
            identity = identity,
            crx = crx,
        )
    }

    private fun packageIdentity(extensionId: String) = OnDevicePackageIdentity(
        extensionId = extensionId,
        packageName = "dev.sixbridge.ext.$extensionId",
        wrapperSignerSha256 = "f".repeat(64),
        crxKeyAlias = null,
        crxPublicKeyDer = null,
    )

    private fun reservation(
        key: InstallationIdentityKey,
        extensionId: String,
        alias: String? = null,
        packageName: String = "dev.sixbridge.ext.$extensionId",
        wrapperSignerSha256: String = "f".repeat(64),
    ): ConversionIdentityLedger.Reservation = runBlocking {
        ConversionIdentityLedger(ReservationIssuingDao()).reserve(
            request = ConversionIdentityReservation(
                identityKey = key,
                displayName = "SIX 테스트",
                packageBinding = ConversionPackageBinding(
                    extensionId = extensionId,
                    packageName = packageName,
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxKeyAlias = alias,
                    crxPublicKeySha256 = if (alias == null) null else "e".repeat(64),
                ),
                installedVersionCode = null,
            ),
            ownerToken = JOB_TOKEN,
        )
    }

    private class ReservationIssuingDao : ConversionIdentityDao {
        override suspend fun get(identitySha256: String): ConversionIdentityEntity? = null
        override suspend fun findByExtensionId(extensionId: String): ConversionIdentityEntity? = null
        override suspend fun findByPackageName(packageName: String): ConversionIdentityEntity? = null
        override suspend fun findByCrxKeyAlias(crxKeyAlias: String): ConversionIdentityEntity? = null
        override suspend fun insert(entity: ConversionIdentityEntity) = error("unused")
        override suspend fun update(entity: ConversionIdentityEntity) = error("unused")
        override suspend fun getLease(identitySha256: String): ConversionIdentityLeaseEntity? = null
        override suspend fun getLeaseByOwnerToken(ownerToken: String): ConversionIdentityLeaseEntity? = null
        override suspend fun getJobForIdentityFinalization(): ConversionJobEntity? = null
        override suspend fun insertLease(entity: ConversionIdentityLeaseEntity) = error("unused")
        override suspend fun markLeaseReady(
            identitySha256: String,
            ownerToken: String,
            extensionId: String,
            packageName: String,
            wrapperSignerSha256: String,
            crxPublicKeySha256: String,
        ): Int = error("unused")

        override suspend fun transitionLease(
            identitySha256: String,
            ownerToken: String,
            expectedState: String,
            nextState: String,
        ): Int = error("unused")

        override suspend fun consumeReadyLease(
            identitySha256: String,
            readyOwnerToken: String,
            reservationOwnerToken: String,
            reservedVersionCode: Int,
        ): Int = error("unused")

        override suspend fun deleteLease(
            identitySha256: String,
            ownerToken: String,
            state: String,
        ): Int = error("unused")

        override suspend fun reserve(
            requested: ConversionIdentityEntity,
            installedVersionCode: Long?,
            readyOwnerToken: String?,
            reservationOwnerToken: String,
            createdAtEpochMillis: Long,
            crxPublicKeySha256: String?,
        ): ConversionIdentityEntity = requested.copy(lastReservedVersionCode = 1)

        override suspend fun listByModeAndProfile(
            mode: String,
            profileMajorVersion: Int,
        ): List<ConversionIdentityEntity> = emptyList()

        override suspend fun claimForPackaging(
            requested: ConversionIdentityEntity,
            versionCode: Int,
            ownerToken: String,
            crxPublicKeySha256: String?,
            packagingState: String,
        ) = error("unused")
    }

    private fun identityKey(mode: InstallationIdentityMode) = InstallationIdentityKey.create(
        LogicalSourceIdentity.storeExtensionId("abcdefghijklmnopabcdefghijklmnop"),
        profileMajorVersion = 28,
        mode = mode,
    )

    private fun compatibility(
        extensionId: String,
        sourceSha256: String,
    ) = ContractCompatibilityReport(
        schemaVersion = 4,
        status = ContractCompatibilityStatus.PARTIAL,
        manifestVersion = 3,
        extensionName = "SIX 테스트",
        extensionVersion = "1.0",
        manifestKeyPresent = false,
        manifestKeyExtensionId = null,
        declaredPermissions = listOf("storage"),
        usedApis = listOf("chrome.storage.local"),
        unsupportedApis = listOf("chrome.desktopCapture"),
        issues = emptyList(),
        conversionState = "passthrough",
        adapterDecisions = listOf(
            ContractAdapterDecision(
                id = "desktop-selection-event-bridge",
                version = 1,
                risk = "behavior-change",
                disposition = "requires-approval",
                applied = true,
                evidence = listOf(
                    ContractSourceEvidence("content.js", 7, "desktop selection listener"),
                ),
                changes = listOf("selection event bridge"),
                remainingLimitations = listOf("desktop selection UI unavailable"),
            ),
        ),
        originalExtensionId = extensionId,
        convertedExtensionId = extensionId,
        sourceSha256 = sourceSha256,
        browserProfileSha256 = "b".repeat(64),
        originalSourceSha256 = "c".repeat(64),
        planSha256 = "d".repeat(64),
    )

    private fun canonicalZip(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0L })
            zip.write("""{"manifest_version":3,"name":"SIX 테스트","version":"1.0"}""".encodeToByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private fun signedCrx(zip: ByteArray): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Crx3Writer.create(
            zip,
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class ConvertedRequestFixture(
        val request: OnDeviceWrapperRequest,
        val identity: OnDevicePackageIdentity,
        val crx: ByteArray,
    )

    private companion object {
        const val JOB_TOKEN = "00000000-0000-4000-8000-000000000001"
    }
}
