package dev.sixbridge.manager.conversion

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class ConversionIdentityLedgerTest {
    @Test
    fun `mode와 profile은 canonical identity를 분리한다`() {
        val source = LogicalSourceIdentity.projectMarker("1".repeat(32))
        val passthrough = InstallationIdentityKey.create(
            source,
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        val converted = InstallationIdentityKey.create(
            source,
            30,
            InstallationIdentityMode.CONVERTED,
        )
        val nextProfile = InstallationIdentityKey.create(
            source,
            31,
            InstallationIdentityMode.CONVERTED,
        )

        assertNotEquals(passthrough.identitySha256, converted.identitySha256)
        assertNotEquals(converted.identitySha256, nextProfile.identitySha256)
    }

    @Test
    fun `동시 예약은 versionCode를 중복하지 않고 실패한 번호도 재사용하지 않는다`() =
        runTest {
            val ledger = ledger()
            val key = stableKey()

            val values = coroutineScope {
                List(8) {
                    async { ledger.reserve(reservation(key)).versionCode }
                }.awaitAll()
            }

            assertEquals((1..8).toSet(), values.toSet())
            assertEquals(9, ledger.reserve(reservation(key)).versionCode)
        }

    @Test
    fun `설치본 floor와 overflow를 fail closed 한다`() = runTest {
        val ledger = ledger()
        val key = stableKey()

        assertEquals(
            8,
            ledger.reserve(reservation(key, installedVersionCode = 7)).versionCode,
        )
        assertFailsWith<IllegalArgumentException> {
            ledger.reserve(
                reservation(
                    key,
                    installedVersionCode = Int.MAX_VALUE.toLong(),
                ),
            )
        }
    }

    @Test
    fun `markerless CreateNew는 package identity 계산 전에 local key를 고정한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(
                dao = dao,
                localIdentityFactory = {
                    UUID.fromString("11111111-2222-3333-4444-555555555555")
                },
            )
            val key = ledger.resolveSelection(
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.CreateNew,
            )
            val binding = bindingFor(key)

            val reserved = ledger.reserve(reservation(key, binding = binding))
            val stored = requireNotNull(dao.get(key.identitySha256))

            assertEquals(key, reserved.identityKey)
            assertEquals(binding.crxKeyAlias, reserved.crxKeyAlias)
            assertEquals(
                "local-11111111-2222-3333-4444-555555555555",
                stored.logicalSourceIdentity,
            )
        }

    @Test
    fun `UseExisting은 canonical hash를 재검증하고 같은 converted key를 package 단계에 돌려준다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val original = stableKey()
            val binding = bindingFor(original)
            ledger.reserve(reservation(original, binding = binding))

            val selected = ledger.resolveSelection(
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.UseExisting(original.identitySha256),
            )
            val reserved = ledger.reserve(reservation(selected, binding = binding))

            assertEquals(original, selected)
            assertEquals(original, reserved.identityKey)
            assertEquals(binding.crxKeyAlias, reserved.crxKeyAlias)
            assertEquals(2, reserved.versionCode)
        }

    @Test
    fun `UseExisting은 손상 hash와 다른 profile 및 passthrough를 거부한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val converted = stableKey()
        dao.insert(entity(converted, bindingFor(converted)))
        dao.force(
            entity(
                converted,
                bindingFor(converted),
            ).copy(identitySha256 = "f".repeat(64)),
        )
        val passthrough = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("b".repeat(32)),
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        dao.insert(entity(passthrough, bindingFor(passthrough)))

        assertFailsWith<IllegalArgumentException> {
            ledger.resolveSelection(
                null,
                30,
                InstallationIdentityMode.CONVERTED,
                InstallationIdentitySelection.UseExisting("f".repeat(64)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ledger.resolveSelection(
                null,
                31,
                InstallationIdentityMode.CONVERTED,
                InstallationIdentitySelection.UseExisting(converted.identitySha256),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ledger.resolveSelection(
                null,
                30,
                InstallationIdentityMode.CONVERTED,
                InstallationIdentitySelection.UseExisting(passthrough.identitySha256),
            )
        }
    }

    @Test
    fun `raw URI percent filename은 logical source value로 만들 수 없다`() {
        listOf(
            "content:customer-secret.zip",
            "file:secret",
            "https:private-name",
            "project:%2Fsdcard%2Fsecret.zip",
            "customer-secret.zip",
        ).forEach { raw ->
            assertFailsWith<IllegalArgumentException> {
                LogicalSourceIdentity.signedDraft(raw)
            }
        }
    }

    @Test
    fun `지원 source별 constructor만 canonical 값을 만든다`() {
        assertEquals(
            "store:${"a".repeat(32)}",
            LogicalSourceIdentity.storeExtensionId("a".repeat(32)).canonicalValue,
        )
        assertEquals(
            "project:${"1".repeat(32)}",
            LogicalSourceIdentity.projectMarker("1".repeat(32)).canonicalValue,
        )
        assertEquals(
            "manifest:${"b".repeat(32)}",
            LogicalSourceIdentity.manifestKeyExtensionId("b".repeat(32)).canonicalValue,
        )
    }

    @Test
    fun `서로 다른 identity의 extension package alias 재사용은 모두 거부한다`() = runTest {
        val ledger = ledger()
        val first = stableKey()
        val second = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("2".repeat(32)),
            30,
            InstallationIdentityMode.CONVERTED,
        )
        val firstBinding = bindingFor(first)
        ledger.reserve(reservation(first, binding = firstBinding))

        listOf(
            firstBinding.copy(
                packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
                crxKeyAlias = "sixbridge.crx.${second.identitySha256}",
            ),
            firstBinding.copy(
                extensionId = "b".repeat(32),
                crxKeyAlias = "sixbridge.crx.${second.identitySha256}",
            ),
            bindingFor(second).copy(crxKeyAlias = firstBinding.crxKeyAlias),
        ).forEach { collision ->
            assertFailsWith<IllegalArgumentException> {
                ledger.reserve(reservation(second, binding = collision))
            }
        }
    }

    @Test
    fun `기존 후보는 같은 profile의 converted만 노출한다`() = runTest {
        val ledger = ledger()
        val selected = stableKey()
        ledger.reserve(reservation(selected))
        val passthrough = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("b".repeat(32)),
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        ledger.reserve(reservation(passthrough, binding = bindingFor(passthrough)))
        val other = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("3".repeat(32)),
            31,
            InstallationIdentityMode.CONVERTED,
        )
        ledger.reserve(reservation(other, binding = bindingFor(other)))

        val targets = ledger.listExistingTargets(30)

        assertEquals(1, targets.size)
        assertEquals(selected.identitySha256, targets.single().identitySha256)
        assertTrue(targets.single().displayName.isNotBlank())
    }

    private fun ledger() = ConversionIdentityLedger(FakeConversionIdentityDao())

    private fun stableKey() = InstallationIdentityKey.create(
        LogicalSourceIdentity.projectMarker("1".repeat(32)),
        30,
        InstallationIdentityMode.CONVERTED,
    )

    private fun reservation(
        key: InstallationIdentityKey,
        binding: ConversionPackageBinding = bindingFor(key),
        installedVersionCode: Long? = null,
    ) = ConversionIdentityReservation(
        identityKey = key,
        displayName = "Extension",
        packageBinding = binding,
        installedVersionCode = installedVersionCode,
    )

    private fun bindingFor(key: InstallationIdentityKey): ConversionPackageBinding {
        val extensionId = key.identitySha256.take(32).map { ('a'.code + it.digitToInt(16)).toChar() }
            .joinToString("")
        return ConversionPackageBinding(
            extensionId = extensionId,
            packageName = "dev.sixbridge.ext.$extensionId",
            wrapperSignerSha256 = SIGNER,
            crxKeyAlias = if (key.mode == InstallationIdentityMode.CONVERTED) {
                "sixbridge.crx.${key.identitySha256}"
            } else {
                null
            },
        )
    }

    private fun entity(
        key: InstallationIdentityKey,
        binding: ConversionPackageBinding,
    ) = ConversionIdentityEntity(
        identitySha256 = key.identitySha256,
        logicalSourceIdentity = key.logicalSourceIdentity.canonicalValue,
        profileMajorVersion = key.profileMajorVersion,
        mode = key.mode.name,
        displayName = "Extension",
        extensionId = binding.extensionId,
        packageName = binding.packageName,
        wrapperSignerSha256 = binding.wrapperSignerSha256,
        crxKeyAlias = binding.crxKeyAlias,
        lastReservedVersionCode = 0,
    )

    private class FakeConversionIdentityDao : ConversionIdentityDao {
        private val rows = linkedMapOf<String, ConversionIdentityEntity>()

        override suspend fun get(identitySha256: String): ConversionIdentityEntity? =
            synchronized(rows) { rows[identitySha256] }

        override suspend fun findByExtensionId(extensionId: String): ConversionIdentityEntity? =
            synchronized(rows) { rows.values.firstOrNull { it.extensionId == extensionId } }

        override suspend fun findByPackageName(packageName: String): ConversionIdentityEntity? =
            synchronized(rows) { rows.values.firstOrNull { it.packageName == packageName } }

        override suspend fun findByCrxKeyAlias(crxKeyAlias: String): ConversionIdentityEntity? =
            synchronized(rows) { rows.values.firstOrNull { it.crxKeyAlias == crxKeyAlias } }

        override suspend fun insert(entity: ConversionIdentityEntity) {
            synchronized(rows) {
                check(rows.putIfAbsent(entity.identitySha256, entity) == null)
            }
        }

        override suspend fun update(entity: ConversionIdentityEntity) {
            synchronized(rows) {
                check(rows.containsKey(entity.identitySha256))
                rows[entity.identitySha256] = entity
            }
        }

        override suspend fun listByModeAndProfile(
            mode: String,
            profileMajorVersion: Int,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter {
                it.mode == mode && it.profileMajorVersion == profileMajorVersion
            }.sortedBy(ConversionIdentityEntity::identitySha256)
        }

        fun force(entity: ConversionIdentityEntity) {
            synchronized(rows) { rows[entity.identitySha256] = entity }
        }
    }

    private companion object {
        const val SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
