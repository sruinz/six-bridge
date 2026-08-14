package dev.sixbridge.manager.conversion

import androidx.room.Room
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class ConversionIdentityManagementTest {
    private lateinit var database: ManagerDatabase
    private lateinit var dao: ConversionIdentityDao
    private lateinit var installed: FakeInstalledWrappers
    private lateinit var bundleProofs: FakeCurrentBundleReadyProofReader
    private lateinit var keys: FakeConversionIdentityKeyInspector

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.conversionIdentityDao()
        installed = FakeInstalledWrappers()
        bundleProofs = FakeCurrentBundleReadyProofReader()
        keys = FakeConversionIdentityKeyInspector()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `active lease와 installed exact match와 BUNDLE_READY proof는 cleanup을 막는다`() = runTest {
        val leaseOwned = convertedIdentity(identitySha256 = "1".repeat(64), ownerToken = JOB_ONE)
        val installedOwned = convertedIdentity(identitySha256 = "2".repeat(64), ownerToken = JOB_TWO)
        val bundleOwned = convertedIdentity(identitySha256 = "3".repeat(64), ownerToken = JOB_THREE)
        listOf(leaseOwned, installedOwned, bundleOwned).forEach {
            dao.insert(it)
            keys.add(keyProof(it))
        }
        dao.insertLease(lease(leaseOwned, JOB_ONE))
        installed.current = installed(installedOwned)

        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_ONE),
        )
        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_TWO),
        )

        installed.current = null
        val proof = VerifiedIdentityProof(
            packageName = bundleOwned.packageName,
            extensionId = bundleOwned.extensionId,
            wrapperSignerSha256 = bundleOwned.wrapperSignerSha256,
            versionCode = bundleOwned.lastReservedVersionCode,
        )
        setBundleReadyJob(JOB_THREE, proof)
        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_THREE),
        )

        listOf(leaseOwned, installedOwned, bundleOwned).forEach {
            assertEquals(ConversionIdentityLifecycleState.PROVISIONAL.name, dao.get(it.identitySha256)?.lifecycleState)
            assertTrue(keys.contains(it.identitySha256))
        }
    }

    @Test
    fun `mismatched public key와 missing alias와 unreadable proof는 converted row와 key를 보존한다`() = runTest {
        val mismatched = convertedIdentity(identitySha256 = "4".repeat(64), ownerToken = JOB_ONE)
        val missing = convertedIdentity(identitySha256 = "5".repeat(64), ownerToken = JOB_TWO)
        val unreadable = convertedIdentity(identitySha256 = "6".repeat(64), ownerToken = JOB_THREE)
        dao.insert(mismatched)
        dao.insert(missing)
        dao.insert(unreadable)
        keys.add(keyProof(mismatched).copy(publicKeySha256 = "f".repeat(64)))
        keys.add(keyProof(unreadable))

        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_ONE),
        )
        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_TWO),
        )
        bundleProofs.current = CurrentBundleReadyProof.Unreadable("private bundle read failed")
        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_THREE),
        )

        assertEquals(mismatched, dao.get(mismatched.identitySha256))
        assertEquals(missing, dao.get(missing.identitySha256))
        assertEquals(unreadable, dao.get(unreadable.identitySha256))
        assertTrue(keys.contains(mismatched.identitySha256))
        assertFalse(keys.contains(missing.identitySha256))
        assertTrue(keys.contains(unreadable.identitySha256))
    }

    @Test
    fun `other owner와 ACTIVE 또는 LEGACY_UNKNOWN 자동 추론은 row와 key를 삭제하지 않는다`() = runTest {
        val provisional = convertedIdentity(identitySha256 = "7".repeat(64), ownerToken = JOB_ONE)
        val active = convertedIdentity(
            identitySha256 = "8".repeat(64),
            ownerToken = null,
            lifecycle = ConversionIdentityLifecycleState.ACTIVE,
        )
        val legacy = convertedIdentity(
            identitySha256 = "9".repeat(64),
            ownerToken = null,
            lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
        )
        listOf(provisional, active, legacy).forEach {
            dao.insert(it)
            keys.add(keyProof(it))
        }

        assertEquals(
            ConversionIdentityCleanupResult.NothingOwned,
            management().discardIdentityOwner(JOB_TWO),
        )
        assertEquals(true, management().recoverIdentityCleanup())

        assertEquals(provisional, dao.get(provisional.identitySha256))
        assertEquals(active, dao.get(active.identitySha256))
        assertEquals(legacy, dao.get(legacy.identitySha256))
        assertEquals(3, keys.size)
    }

    @Test
    fun `PASSTHROUGH와 PREPARED는 null alias proof를 확인하고 Keystore 없이 row만 제거한다`() = runTest {
        val passthrough = externalIdentity(
            identitySha256 = "a".repeat(64),
            ownerToken = JOB_ONE,
            mode = InstallationIdentityMode.PASSTHROUGH,
        )
        val prepared = externalIdentity(
            identitySha256 = "b".repeat(64),
            ownerToken = JOB_TWO,
            mode = InstallationIdentityMode.PREPARED,
        )
        dao.insert(passthrough)
        dao.insert(prepared)

        assertEquals(
            ConversionIdentityCleanupResult.Removed,
            management().discardIdentityOwner(JOB_ONE),
        )
        assertEquals(
            ConversionIdentityCleanupResult.Removed,
            management().discardIdentityOwner(JOB_TWO),
        )

        assertNull(dao.get(passthrough.identitySha256))
        assertNull(dao.get(prepared.identitySha256))
        assertEquals(0, keys.inspectCalls)
        assertEquals(0, keys.deleteCalls)
    }

    @Test
    fun `external mode에 alias 또는 public key가 있으면 row를 보존한다`() = runTest {
        val alias = externalIdentity(
            identitySha256 = "c".repeat(64),
            ownerToken = JOB_ONE,
            mode = InstallationIdentityMode.PASSTHROUGH,
        ).copy(crxKeyAlias = "unexpected")
        val publicKey = externalIdentity(
            identitySha256 = "d".repeat(64),
            ownerToken = JOB_TWO,
            mode = InstallationIdentityMode.PREPARED,
        ).copy(crxPublicKeySha256 = "e".repeat(64))
        dao.insert(alias)
        dao.insert(publicKey)

        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_ONE),
        )
        assertIs<ConversionIdentityCleanupResult.Blocked>(
            management().discardIdentityOwner(JOB_TWO),
        )

        assertEquals(alias, dao.get(alias.identitySha256))
        assertEquals(publicKey, dao.get(publicKey.identitySha256))
        assertEquals(0, keys.inspectCalls)
    }

    @Test
    fun `claim 직후 crash는 CLEANING을 남기고 startup이 exact alias부터 재개한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "1a".repeat(32), ownerToken = JOB_ONE)
        val other = convertedIdentity(identitySha256 = "1b".repeat(32), ownerToken = JOB_TWO)
        dao.insert(target)
        dao.insert(other)
        keys.add(keyProof(target))
        keys.add(keyProof(other))
        val crashing = management(
            hooks = ConversionIdentityManagementHooks(
                afterCleanupClaim = { error("simulated crash after CLEANING claim") },
            ),
        )

        assertFailsWith<IllegalStateException> {
            crashing.discardIdentityOwner(JOB_ONE)
        }
        assertEquals(ConversionIdentityLifecycleState.CLEANING.name, dao.get(target.identitySha256)?.lifecycleState)
        assertTrue(keys.contains(target.identitySha256))

        assertTrue(management().recoverIdentityCleanup())
        assertNull(dao.get(target.identitySha256))
        assertFalse(keys.contains(target.identitySha256))
        assertEquals(other, dao.get(other.identitySha256))
        assertTrue(keys.contains(other.identitySha256))
    }

    @Test
    fun `alias 삭제 직후 crash는 CLEANING row만 남기고 startup이 row delete를 재개한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "2a".repeat(32), ownerToken = JOB_ONE)
        dao.insert(target)
        keys.add(keyProof(target))

        assertFailsWith<IllegalStateException> {
            management(
                hooks = ConversionIdentityManagementHooks(
                    afterAliasDeleted = { error("simulated crash after alias delete") },
                ),
            ).discardIdentityOwner(JOB_ONE)
        }
        assertEquals(ConversionIdentityLifecycleState.CLEANING.name, dao.get(target.identitySha256)?.lifecycleState)
        assertFalse(keys.contains(target.identitySha256))

        assertTrue(management().recoverIdentityCleanup())
        assertNull(dao.get(target.identitySha256))
    }

    @Test
    fun `row delete 직전 crash는 alias absent CLEANING을 idempotent하게 마무리한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "3a".repeat(32), ownerToken = JOB_ONE)
        dao.insert(target)
        keys.add(keyProof(target))

        assertFailsWith<IllegalStateException> {
            management(
                hooks = ConversionIdentityManagementHooks(
                    beforeRowDelete = { error("simulated crash before row delete") },
                ),
            ).discardIdentityOwner(JOB_ONE)
        }
        assertNotNull(dao.get(target.identitySha256))
        assertFalse(keys.contains(target.identitySha256))

        assertTrue(management().recoverIdentityCleanup())
        assertNull(dao.get(target.identitySha256))
    }

    @Test
    fun `v4 converted legacy는 exact alias public key를 CLEANING CAS에 저장한 뒤 삭제한다`() = runTest {
        val legacy = convertedIdentity(
            identitySha256 = "4a".repeat(32),
            ownerToken = null,
            lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
        ).copy(crxPublicKeySha256 = null)
        dao.insert(legacy)
        val proof = keyProof(legacy).copy(publicKeySha256 = "4b".repeat(32))
        keys.add(proof)

        assertFailsWith<IllegalStateException> {
            management(
                hooks = ConversionIdentityManagementHooks(
                    afterCleanupClaim = { error("simulated crash after legacy claim") },
                ),
            ).discardLegacyUnknown(legacy.identitySha256)
        }

        val cleaning = requireNotNull(dao.get(legacy.identitySha256))
        assertEquals(ConversionIdentityLifecycleState.CLEANING.name, cleaning.lifecycleState)
        assertEquals(proof.publicKeySha256, cleaning.crxPublicKeySha256)
        assertTrue(keys.contains(legacy.identitySha256))
        assertTrue(management().recoverIdentityCleanup())
        assertNull(dao.get(legacy.identitySha256))
    }

    @Test
    fun `CAS 뒤 alias delete 경계에서 bundle proof가 unreadable이면 CLEANING과 key를 보존한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "5a".repeat(32), ownerToken = JOB_ONE)
        dao.insert(target)
        keys.add(keyProof(target))
        bundleProofs.enqueue(
            CurrentBundleReadyProof.Absent,
            CurrentBundleReadyProof.Unreadable("second read failed"),
        )

        val result = management().discardIdentityOwner(JOB_ONE)

        assertIs<ConversionIdentityCleanupResult.Blocked>(result)
        assertEquals(ConversionIdentityLifecycleState.CLEANING.name, dao.get(target.identitySha256)?.lifecycleState)
        assertTrue(keys.contains(target.identitySha256))
        assertEquals(2, bundleProofs.readCalls)
    }

    @Test
    fun `legacy 목록은 exact command fields만 lifecycle 순서로 반환한다`() = runTest {
        val second = convertedIdentity(
            identitySha256 = "6b".repeat(32),
            ownerToken = null,
            lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
        ).copy(displayName = "Zulu")
        val first = externalIdentity(
            identitySha256 = "6a".repeat(32),
            ownerToken = null,
            mode = InstallationIdentityMode.PREPARED,
        ).copy(
            lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
            displayName = "Alpha",
        )
        dao.insert(second)
        dao.insert(first)

        assertEquals(
            listOf(
                LegacyConversionIdentity(
                    identitySha256 = first.identitySha256,
                    displayName = "Alpha",
                    mode = InstallationIdentityMode.PREPARED,
                    packageName = first.packageName,
                    extensionId = first.extensionId,
                    wrapperSignerSha256 = first.wrapperSignerSha256,
                ),
                LegacyConversionIdentity(
                    identitySha256 = second.identitySha256,
                    displayName = "Zulu",
                    mode = InstallationIdentityMode.CONVERTED,
                    packageName = second.packageName,
                    extensionId = second.extensionId,
                    wrapperSignerSha256 = second.wrapperSignerSha256,
                ),
            ),
            management().listLegacyUnknown(),
        )
    }

    private fun management(
        hooks: ConversionIdentityManagementHooks = ConversionIdentityManagementHooks(),
    ) = ConversionIdentityManagement(
        dao = dao,
        installedWrappers = installed,
        currentBundleReadyProofReader = bundleProofs,
        keyInspector = keys,
        currentTimeMillis = { 1234L },
        hooks = hooks,
    )

    private suspend fun setBundleReadyJob(
        ownerToken: String,
        proof: VerifiedIdentityProof,
    ) {
        val jobs = database.conversionJobDao()
        jobs.claimCreate(
            ConversionJobEntity(
                token = ownerToken,
                generation = 0,
                inputKind = OnDeviceInputKind.ZIP.name,
                inputSha256 = "1".repeat(64),
                inputFileName = "11111111-1111-4111-8111-111111111111.input",
                stage = ConversionJobStage.INPUT_READY.name,
                approvedAdapterIdsJson = null,
                profileSha256 = null,
                bundleFileName = null,
                bundleSha256 = null,
                cleanupTokensJson = "[]",
                updatedAtEpochMillis = 1,
            ),
        )
        val current = requireNotNull(jobs.get())
        assertEquals(
            1,
            jobs.transition(
                token = ownerToken,
                expectedStage = current.stage,
                expectedGeneration = current.generation,
                expectedProfileSha256 = null,
                nextStage = ConversionJobStage.BUNDLE_READY.name,
                approvedAdapterIdsJson = "[]",
                profileSha256 = "2".repeat(64),
                bundleFileName = "22222222-2222-4222-8222-222222222222.six",
                bundleSha256 = "3".repeat(64),
                updatedAtEpochMillis = 2,
            ),
        )
        val ready = requireNotNull(jobs.get())
        bundleProofs.current = CurrentBundleReadyProof.Verified(
            ownerToken = ready.token,
            generation = ready.generation,
            bundleSha256 = requireNotNull(ready.bundleSha256),
            proof = proof,
        )
    }

    private fun convertedIdentity(
        identitySha256: String,
        ownerToken: String?,
        lifecycle: ConversionIdentityLifecycleState = ConversionIdentityLifecycleState.PROVISIONAL,
    ) = ConversionIdentityEntity(
        identitySha256 = identitySha256,
        logicalSourceIdentity = "project:${identitySha256.take(32)}",
        profileMajorVersion = 30,
        mode = InstallationIdentityMode.CONVERTED.name,
        displayName = "Converted $identitySha256",
        extensionId = extensionId(identitySha256),
        packageName = "dev.sixbridge.ext.${extensionId(identitySha256)}",
        wrapperSignerSha256 = SIGNER,
        crxKeyAlias = "sixbridge.crx.rsa.v1.$identitySha256",
        lastReservedVersionCode = 4,
        lifecycleState = lifecycle.name,
        provisionOwnerToken = ownerToken,
        crxPublicKeySha256 = publicKeySha256(identitySha256),
        lastSuccessfulVersionCode = null,
        lifecycleUpdatedAtEpochMillis = 1,
    )

    private fun externalIdentity(
        identitySha256: String,
        ownerToken: String?,
        mode: InstallationIdentityMode,
    ) = ConversionIdentityEntity(
        identitySha256 = identitySha256,
        logicalSourceIdentity = "project:${identitySha256.take(32)}",
        profileMajorVersion = 30,
        mode = mode.name,
        displayName = mode.name,
        extensionId = extensionId(identitySha256),
        packageName = "dev.sixbridge.ext.${extensionId(identitySha256)}",
        wrapperSignerSha256 = SIGNER,
        crxKeyAlias = null,
        lastReservedVersionCode = 4,
        lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
        provisionOwnerToken = ownerToken,
        crxPublicKeySha256 = null,
        lastSuccessfulVersionCode = null,
        lifecycleUpdatedAtEpochMillis = 1,
    )

    private fun keyProof(identity: ConversionIdentityEntity) = ConversionIdentityKeyProof.Verified(
        alias = requireNotNull(identity.crxKeyAlias),
        extensionId = identity.extensionId,
        publicKeySha256 = identity.crxPublicKeySha256 ?: publicKeySha256(identity.identitySha256),
    )

    private fun installed(identity: ConversionIdentityEntity) = InstalledWrapper(
        packageName = identity.packageName,
        extensionId = identity.extensionId,
        name = identity.displayName,
        versionCode = identity.lastReservedVersionCode.toLong(),
        signerSha256 = identity.wrapperSignerSha256,
    )

    private fun lease(identity: ConversionIdentityEntity, ownerToken: String) =
        ConversionIdentityLeaseEntity(
            identitySha256 = identity.identitySha256,
            crxKeyAlias = identity.crxKeyAlias,
            ownerToken = ownerToken,
            state = ConversionIdentityLeaseState.RESERVED.name,
            createdAtEpochMillis = 1,
            extensionId = identity.extensionId,
            packageName = identity.packageName,
            wrapperSignerSha256 = identity.wrapperSignerSha256,
            crxPublicKeySha256 = identity.crxPublicKeySha256,
            reservedVersionCode = identity.lastReservedVersionCode,
        )

    private class FakeInstalledWrappers : InstalledWrapperRepository {
        var current: InstalledWrapper? = null
        var failure: Throwable? = null
        override suspend fun findAll(): List<InstalledWrapper> = listOfNotNull(current)
        override suspend fun find(packageName: String): InstalledWrapper? {
            failure?.let { throw it }
            return current?.takeIf { it.packageName == packageName }
        }
    }

    private class FakeCurrentBundleReadyProofReader : CurrentBundleReadyProofReader {
        var current: CurrentBundleReadyProof = CurrentBundleReadyProof.Absent
        private val queued = ArrayDeque<CurrentBundleReadyProof>()
        var readCalls = 0
            private set

        fun enqueue(vararg values: CurrentBundleReadyProof) {
            values.forEach(queued::addLast)
        }

        override suspend fun read(): CurrentBundleReadyProof {
            readCalls += 1
            return if (queued.isEmpty()) current else queued.removeFirst()
        }
    }

    private class FakeConversionIdentityKeyInspector : ConversionIdentityKeyInspector {
        private val proofs = linkedMapOf<String, ConversionIdentityKeyProof.Verified>()
        var inspectCalls = 0
            private set
        var deleteCalls = 0
            private set
        val size: Int
            get() = proofs.size

        fun add(proof: ConversionIdentityKeyProof.Verified) {
            proofs[proof.alias.substringAfterLast('.')] = proof
        }

        fun contains(identitySha256: String): Boolean = identitySha256 in proofs

        override suspend fun <T> withIdentityLock(
            identitySha256: String,
            block: suspend () -> T,
        ): T = block()

        override fun inspect(identitySha256: String): ConversionIdentityKeyProof {
            inspectCalls += 1
            return proofs[identitySha256] ?: ConversionIdentityKeyProof.Absent
        }

        override fun delete(
            identitySha256: String,
            expectedAlias: String,
            expectedExtensionId: String,
            expectedPublicKeySha256: String,
        ): Boolean {
            deleteCalls += 1
            val current = proofs[identitySha256] ?: return true
            require(
                current.alias == expectedAlias &&
                    current.extensionId == expectedExtensionId &&
                    current.publicKeySha256 == expectedPublicKeySha256,
            )
            proofs.remove(identitySha256)
            return true
        }
    }

    private companion object {
        const val JOB_ONE = "00000000-0000-4000-8000-000000000001"
        const val JOB_TWO = "00000000-0000-4000-8000-000000000002"
        const val JOB_THREE = "00000000-0000-4000-8000-000000000003"
        const val SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        fun extensionId(identitySha256: String): String =
            identitySha256.take(32).map { value ->
                ('a'.code + value.digitToInt(16)).toChar()
            }.joinToString("")

        fun publicKeySha256(identitySha256: String): String =
            identitySha256.reversed()
    }
}
