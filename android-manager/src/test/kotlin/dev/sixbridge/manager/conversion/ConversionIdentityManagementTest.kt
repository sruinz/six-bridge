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
    fun `PASSTHROUGH와 PREPARED는 Keystore 없이 high watermark row를 retire한다`() = runTest {
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

        assertRetired(passthrough)
        assertRetired(prepared)
        assertEquals(0, keys.inspectCalls)
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
        assertRetired(target)
        assertTrue(keys.contains(target.identitySha256))
        assertEquals(other, dao.get(other.identitySha256))
        assertTrue(keys.contains(other.identitySha256))
    }

    @Test
    fun `final proof 직후 crash는 CLEANING key를 남기고 startup이 retire를 재개한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "2a".repeat(32), ownerToken = JOB_ONE)
        dao.insert(target)
        keys.add(keyProof(target))

        assertFailsWith<IllegalStateException> {
            management(
                hooks = ConversionIdentityManagementHooks(
                    afterFinalProof = { error("simulated crash after final proof") },
                ),
            ).discardIdentityOwner(JOB_ONE)
        }
        assertEquals(ConversionIdentityLifecycleState.CLEANING.name, dao.get(target.identitySha256)?.lifecycleState)
        assertTrue(keys.contains(target.identitySha256))

        assertTrue(management().recoverIdentityCleanup())
        assertRetired(target)
        assertTrue(keys.contains(target.identitySha256))
    }

    @Test
    fun `row retire 직전 crash는 CLEANING key를 보존하고 idempotent하게 마무리한다`() = runTest {
        val target = convertedIdentity(identitySha256 = "3a".repeat(32), ownerToken = JOB_ONE)
        dao.insert(target)
        keys.add(keyProof(target))

        assertFailsWith<IllegalStateException> {
            management(
                hooks = ConversionIdentityManagementHooks(
                    beforeRowRetire = { error("simulated crash before row retire") },
                ),
            ).discardIdentityOwner(JOB_ONE)
        }
        assertNotNull(dao.get(target.identitySha256))
        assertTrue(keys.contains(target.identitySha256))

        assertTrue(management().recoverIdentityCleanup())
        assertRetired(target)
        assertTrue(keys.contains(target.identitySha256))
    }

    @Test
    fun `v4 converted legacy는 exact public key를 CLEANING CAS에 저장한 뒤 retire한다`() = runTest {
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
        val retired = assertRetired(legacy)
        assertEquals(proof.publicKeySha256, retired.crxPublicKeySha256)
        assertTrue(keys.contains(legacy.identitySha256))
        assertEquals(
            ConversionIdentityCleanupResult.NothingOwned,
            management().discardLegacyUnknown(legacy.identitySha256),
        )
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
    fun `두 번째 proof 직후 exact wrapper가 설치되어도 converted key를 영구 삭제하지 않는다`() =
        runTest {
            val target = convertedIdentity(identitySha256 = "5b".repeat(32), ownerToken = JOB_ONE)
            dao.insert(target)
            keys.add(keyProof(target))
            bundleProofs.afterRead = { readCount ->
                if (readCount == 2) installed.current = installed(target)
            }

            management().discardIdentityOwner(JOB_ONE)

            assertNotNull(installed.current)
            assertNotNull(dao.get(target.identitySha256))
            assertTrue(keys.contains(target.identitySha256))
        }

    @Test
    fun `두 번째 proof 직후 BUNDLE_READY 전이는 CLEANING row와 converted key를 모두 보존한다`() =
        runTest {
            val target = convertedIdentity(identitySha256 = "5c".repeat(32), ownerToken = JOB_ONE)
            dao.insert(target)
            keys.add(keyProof(target))
            bundleProofs.afterRead = { readCount ->
                if (readCount == 2) {
                    setBundleReadyJob(
                        JOB_TWO,
                        VerifiedIdentityProof(
                            packageName = "dev.sixbridge.ext.${"c".repeat(32)}",
                            extensionId = "c".repeat(32),
                            wrapperSignerSha256 = SIGNER,
                            versionCode = 9,
                        ),
                    )
                }
            }

            val result = management().discardIdentityOwner(JOB_ONE)

            assertIs<ConversionIdentityCleanupResult.Blocked>(result)
            assertEquals(
                ConversionIdentityLifecycleState.CLEANING.name,
                dao.get(target.identitySha256)?.lifecycleState,
            )
            assertTrue(keys.contains(target.identitySha256))
        }

    @Test
    fun `real Room은 RETIRED external high watermark를 다음 PROVISIONAL owner에게 원자적으로 잇는다`() =
        runTest {
            val target = externalIdentity(
                identitySha256 = "5d".repeat(32),
                ownerToken = JOB_ONE,
                mode = InstallationIdentityMode.PASSTHROUGH,
            )
            dao.insert(target)
            assertEquals(
                ConversionIdentityCleanupResult.Removed,
                management().discardIdentityOwner(JOB_ONE),
            )

            val reserved = dao.reserve(
                requested = target.copy(displayName = "Reactivated external"),
                installedVersionCode = null,
                readyOwnerToken = null,
                reservationOwnerToken = JOB_TWO,
                createdAtEpochMillis = 2345L,
                crxPublicKeySha256 = null,
            )

            assertEquals(5, reserved.lastReservedVersionCode)
            assertEquals(ConversionIdentityLifecycleState.PROVISIONAL.name, reserved.lifecycleState)
            assertEquals(JOB_TWO, reserved.provisionOwnerToken)
            assertEquals(reserved, dao.get(target.identitySha256))
            assertEquals(5, dao.getLease(target.identitySha256)?.reservedVersionCode)
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

    @Test
    fun `installed exact proof는 legacy만 ACTIVE로 승격하고 성공 version과 reserved floor를 보존한다`() =
        runTest {
            val converted = convertedIdentity(
                identitySha256 = "7a".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            val passthrough = externalIdentity(
                identitySha256 = "7b".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PASSTHROUGH,
            ).copy(
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                lastReservedVersionCode = 12,
            )
            val retired = convertedIdentity(
                identitySha256 = "7c".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.RETIRED,
            )
            for (identity in listOf(converted, passthrough, retired)) dao.insert(identity)
            keys.add(keyProof(converted))
            keys.add(keyProof(retired))
            installed.values = listOf(
                installed(converted).copy(versionCode = 8),
                installed(passthrough).copy(versionCode = 7),
                installed(retired).copy(versionCode = 9),
            )

            val result = management().reconcileLegacyIdentities()

            assertEquals(setOf(converted.identitySha256, passthrough.identitySha256), result.promotedIdentitySha256)
            assertTrue(result.retainedIdentitySha256.isEmpty())
            assertTrue(result.blockedReasons.isEmpty())
            requireNotNull(dao.get(converted.identitySha256)).also { active ->
                assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
                assertEquals(8, active.lastSuccessfulVersionCode)
                assertEquals(8, active.lastReservedVersionCode)
                assertEquals(publicKeySha256(converted.identitySha256), active.crxPublicKeySha256)
            }
            requireNotNull(dao.get(passthrough.identitySha256)).also { active ->
                assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
                assertEquals(7, active.lastSuccessfulVersionCode)
                assertEquals(12, active.lastReservedVersionCode)
                assertNull(active.crxPublicKeySha256)
            }
            assertEquals(retired, dao.get(retired.identitySha256))
            assertTrue(keys.contains(converted.identitySha256))
            assertTrue(keys.contains(retired.identitySha256))
        }

    @Test
    fun `bundle proof unreadable은 독립 installed 승격을 막지 않고 bundle 후보만 보존한다`() =
        runTest {
            val installedLegacy = externalIdentity(
                identitySha256 = "8a".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PASSTHROUGH,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name)
            val bundleLegacy = externalIdentity(
                identitySha256 = "8b".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PREPARED,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name)
            dao.insert(installedLegacy)
            dao.insert(bundleLegacy)
            installed.values = listOf(installed(installedLegacy).copy(versionCode = 6))
            bundleProofs.current = CurrentBundleReadyProof.Unreadable("private bundle read failed")

            val result = management().reconcileLegacyIdentities()

            assertEquals(setOf(installedLegacy.identitySha256), result.promotedIdentitySha256)
            assertEquals(setOf(bundleLegacy.identitySha256), result.retainedIdentitySha256)
            assertTrue(result.blockedReasons.getValue(bundleLegacy.identitySha256).contains("private bundle read failed"))
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, dao.get(installedLegacy.identitySha256)?.lifecycleState)
            assertEquals(ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name, dao.get(bundleLegacy.identitySha256)?.lifecycleState)
        }

    @Test
    fun `current BUNDLE_READY와 retained import exact proof만 legacy를 idempotent하게 승격한다`() =
        runTest {
            val currentBundle = externalIdentity(
                identitySha256 = "9a".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PREPARED,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name)
            val imported = convertedIdentity(
                identitySha256 = "9b".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            val retired = externalIdentity(
                identitySha256 = "9c".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PASSTHROUGH,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.RETIRED.name)
            for (identity in listOf(currentBundle, imported, retired)) dao.insert(identity)
            keys.add(keyProof(imported))
            setBundleReadyJob(
                JOB_ONE,
                proof(currentBundle, versionCode = 21),
            )

            val startup = management().reconcileLegacyIdentities()
            val importPromoted = management().promoteLegacyFromVerifiedBundle(
                proof(imported, versionCode = 22),
            )
            val retiredPromoted = management().promoteLegacyFromVerifiedBundle(
                proof(retired, versionCode = 23),
            )
            val repeated = management().reconcileLegacyIdentities()

            assertEquals(setOf(currentBundle.identitySha256), startup.promotedIdentitySha256)
            assertEquals(setOf(imported.identitySha256), startup.retainedIdentitySha256)
            assertTrue(importPromoted)
            assertFalse(retiredPromoted)
            assertTrue(repeated.promotedIdentitySha256.isEmpty())
            assertTrue(repeated.retainedIdentitySha256.isEmpty())
            assertEquals(21, dao.get(currentBundle.identitySha256)?.lastSuccessfulVersionCode)
            assertEquals(22, dao.get(imported.identitySha256)?.lastSuccessfulVersionCode)
            assertEquals(publicKeySha256(imported.identitySha256), dao.get(imported.identitySha256)?.crxPublicKeySha256)
            assertEquals(retired, dao.get(retired.identitySha256))
        }

    @Test
    fun `partial binding invalid version과 converted alias key mismatch는 legacy row와 key를 보존한다`() =
        runTest {
            val partial = externalIdentity(
                identitySha256 = "aa".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PASSTHROUGH,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name)
            val invalidVersion = externalIdentity(
                identitySha256 = "ab".repeat(32),
                ownerToken = null,
                mode = InstallationIdentityMode.PREPARED,
            ).copy(lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name)
            val wrongKey = convertedIdentity(
                identitySha256 = "ac".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            for (identity in listOf(partial, invalidVersion, wrongKey)) dao.insert(identity)
            installed.values = listOf(
                installed(partial).copy(extensionId = "p".repeat(32)),
                installed(invalidVersion).copy(versionCode = Int.MAX_VALUE.toLong() + 1),
                installed(wrongKey).copy(versionCode = 17),
            )
            keys.add(
                keyProof(wrongKey).copy(
                    alias = "wrong.${wrongKey.identitySha256}",
                ),
            )

            val result = management().reconcileLegacyIdentities()

            assertEquals(emptySet<String>(), result.promotedIdentitySha256)
            assertEquals(
                setOf(partial.identitySha256, invalidVersion.identitySha256, wrongKey.identitySha256),
                result.retainedIdentitySha256,
            )
            listOf(partial, invalidVersion, wrongKey).forEach { original ->
                assertEquals(original, dao.get(original.identitySha256))
            }
            assertTrue(keys.contains(wrongKey.identitySha256))
        }

    @Test
    fun `converted key proof가 없거나 extension digest가 다르고 읽기 실패하면 legacy를 보존한다`() =
        runTest {
            val aliasMissingBase = convertedIdentity(
                identitySha256 = "ad".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            )
            val aliasMissing = aliasMissingBase.copy(
                crxKeyAlias = null,
                crxPublicKeySha256 = null,
            )
            val keyAbsent = convertedIdentity(
                identitySha256 = "ae".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            val extensionMismatch = convertedIdentity(
                identitySha256 = "af".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            val digestMismatchBase = convertedIdentity(
                identitySha256 = "ba".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            )
            val digestMismatch = digestMismatchBase.copy(
                crxPublicKeySha256 = "f".repeat(64),
            )
            val unreadable = convertedIdentity(
                identitySha256 = "bb".repeat(32),
                ownerToken = null,
                lifecycle = ConversionIdentityLifecycleState.LEGACY_UNKNOWN,
            ).copy(crxPublicKeySha256 = null)
            val identities = listOf(
                aliasMissing,
                keyAbsent,
                extensionMismatch,
                digestMismatch,
                unreadable,
            )
            identities.forEach { identity ->
                dao.insert(identity)
                installed.values += installed(identity).copy(versionCode = 17)
            }
            keys.add(keyProof(aliasMissingBase))
            keys.add(
                keyProof(extensionMismatch).copy(
                    extensionId = "p".repeat(32),
                ),
            )
            keys.add(keyProof(digestMismatchBase))
            keys.set(
                unreadable.identitySha256,
                ConversionIdentityKeyProof.Unreadable("Keystore read failed"),
            )

            val result = management().reconcileLegacyIdentities()

            assertEquals(emptySet<String>(), result.promotedIdentitySha256)
            assertEquals(
                identities.mapTo(linkedSetOf(), ConversionIdentityEntity::identitySha256),
                result.retainedIdentitySha256,
            )
            identities.forEach { identity ->
                assertEquals(identity, dao.get(identity.identitySha256))
            }
            assertTrue(keys.contains(aliasMissing.identitySha256))
            assertFalse(keys.contains(keyAbsent.identitySha256))
            assertTrue(keys.contains(extensionMismatch.identitySha256))
            assertTrue(keys.contains(digestMismatch.identitySha256))
            assertTrue(keys.contains(unreadable.identitySha256))
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

    private suspend fun assertRetired(
        original: ConversionIdentityEntity,
    ): ConversionIdentityEntity = requireNotNull(dao.get(original.identitySha256)).also { retired ->
        assertEquals(ConversionIdentityLifecycleState.RETIRED.name, retired.lifecycleState)
        assertNull(retired.provisionOwnerToken)
        assertEquals(original.lastReservedVersionCode, retired.lastReservedVersionCode)
        assertEquals(original.crxKeyAlias, retired.crxKeyAlias)
    }

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

    private fun proof(
        identity: ConversionIdentityEntity,
        versionCode: Int,
    ) = VerifiedIdentityProof(
        packageName = identity.packageName,
        extensionId = identity.extensionId,
        wrapperSignerSha256 = identity.wrapperSignerSha256,
        versionCode = versionCode,
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
        var values: List<InstalledWrapper> = emptyList()
        var current: InstalledWrapper?
            get() = values.singleOrNull()
            set(value) {
                values = listOfNotNull(value)
            }
        var failure: Throwable? = null
        override suspend fun findAll(): List<InstalledWrapper> = values
        override suspend fun find(packageName: String): InstalledWrapper? {
            failure?.let { throw it }
            return values.singleOrNull { it.packageName == packageName }
        }
    }

    private class FakeCurrentBundleReadyProofReader : CurrentBundleReadyProofReader {
        var current: CurrentBundleReadyProof = CurrentBundleReadyProof.Absent
        private val queued = ArrayDeque<CurrentBundleReadyProof>()
        var afterRead: suspend (Int) -> Unit = {}
        var readCalls = 0
            private set

        fun enqueue(vararg values: CurrentBundleReadyProof) {
            values.forEach(queued::addLast)
        }

        override suspend fun read(): CurrentBundleReadyProof {
            readCalls += 1
            val result = if (queued.isEmpty()) current else queued.removeFirst()
            afterRead(readCalls)
            return result
        }
    }

    private class FakeConversionIdentityKeyInspector : ConversionIdentityKeyInspector {
        private val proofs = linkedMapOf<String, ConversionIdentityKeyProof>()
        var inspectCalls = 0
            private set
        val size: Int
            get() = proofs.size

        fun add(proof: ConversionIdentityKeyProof.Verified) {
            proofs[proof.alias.substringAfterLast('.')] = proof
        }

        fun set(identitySha256: String, proof: ConversionIdentityKeyProof) {
            proofs[identitySha256] = proof
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
