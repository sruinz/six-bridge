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
    fun `canonical identity는 길이 prefix tuple의 고정 SHA-256이다`() {
        val key = InstallationIdentityKey.create(
            logicalSourceIdentity = "source",
            profileMajorVersion = 30,
            mode = InstallationIdentityMode.CONVERTED,
        )

        assertEquals(
            "89c8e94ae1a94bc6be4053d4873c143d0daaedaf951d4814055c564dc4960419",
            key.identitySha256,
        )
    }

    @Test
    fun `같은 source의 PASSTHROUGH와 CONVERTED identity는 다르다`() {
        val passthrough = InstallationIdentityKey.create(
            "chrome:${"a".repeat(32)}",
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        val converted = InstallationIdentityKey.create(
            "chrome:${"a".repeat(32)}",
            30,
            InstallationIdentityMode.CONVERTED,
        )

        assertNotEquals(passthrough.identitySha256, converted.identitySha256)
    }

    @Test
    fun `같은 논리 원본도 프로필 주 버전이 다르면 converted identity가 다르다`() {
        val profile30 = InstallationIdentityKey.create(
            "project:stable",
            30,
            InstallationIdentityMode.CONVERTED,
        )
        val profile31 = InstallationIdentityKey.create(
            "project:stable",
            31,
            InstallationIdentityMode.CONVERTED,
        )

        assertNotEquals(profile30.identitySha256, profile31.identitySha256)
    }

    @Test
    fun `동시 예약은 versionCode를 중복하지 않는다`() = runTest {
        val ledger = ledger()

        val values = coroutineScope {
            List(8) {
                async { ledger.reserve(stableRequest()).versionCode }
            }.awaitAll()
        }

        assertEquals((1..8).toSet(), values.toSet())
    }

    @Test
    fun `실패한 빌드가 예약한 번호를 다시 사용하지 않는다`() = runTest {
        val ledger = ledger()

        assertEquals(1, ledger.reserve(stableRequest()).versionCode)
        assertEquals(2, ledger.reserve(stableRequest()).versionCode)
    }

    @Test
    fun `설치본 version 7이면 최초 예약은 8이다`() = runTest {
        val reserved = ledger().reserve(
            stableRequest(installedVersionCode = 7),
        )

        assertEquals(8, reserved.versionCode)
    }

    @Test
    fun `versionCode floor와 예약값 overflow를 fail closed 한다`() = runTest {
        val ledger = ledger()

        assertFailsWith<IllegalArgumentException> {
            ledger.reserve(stableRequest(installedVersionCode = Int.MAX_VALUE.toLong()))
        }
        val almostFull = stableRequest(logicalSourceIdentity = "project:almost-full")
        ledger.reserve(almostFull.copy(installedVersionCode = Int.MAX_VALUE - 1L))
        assertFailsWith<IllegalStateException> {
            ledger.reserve(almostFull)
        }
    }

    @Test
    fun `불안정 ZIP은 명시 선택 없이는 예약되지 않는다`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            ledger().reserve(unstableRequest(selection = null))
        }
    }

    @Test
    fun `원본 URI와 외부 경로는 논리 신원으로 DB에 저장하지 않는다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)

        listOf(
            "content://private.provider/customer-secret.zip",
            "file:///sdcard/Download/customer-secret.zip",
            "/sdcard/Download/customer-secret.zip",
        ).forEach { source ->
            assertFailsWith<IllegalArgumentException> {
                ledger.reserve(stableRequest(logicalSourceIdentity = source))
            }
        }

        assertTrue(
            dao.listByModeAndProfile(InstallationIdentityMode.CONVERTED.name, 30)
                .isEmpty(),
        )
    }

    @Test
    fun `CreateNew는 local UUID 논리 신원의 converted 행을 만든다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(
            dao = dao,
            localIdentityFactory = {
                UUID.fromString("11111111-2222-3333-4444-555555555555")
            },
        )

        val reserved = ledger.reserve(
            unstableRequest(selection = InstallationIdentitySelection.CreateNew),
        )
        val stored = requireNotNull(dao.get(reserved.identitySha256))

        assertEquals(
            "local-11111111-2222-3333-4444-555555555555",
            stored.logicalSourceIdentity,
        )
        assertEquals(InstallationIdentityMode.CONVERTED.name, stored.mode)
    }

    @Test
    fun `기존 대상 응답은 이름 ID package signer를 모두 포함하고 후보를 필터링한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val selected = ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:selected",
                displayName = "비슷한 이름",
            ),
        )
        ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:passthrough",
                displayName = "비슷한 이름",
                mode = InstallationIdentityMode.PASSTHROUGH,
                crxKeyAlias = null,
            ),
        )
        ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:other-major",
                profileMajorVersion = 31,
                displayName = "비슷한 이름",
            ),
        )

        val targets = ledger.listExistingTargets(30)

        assertEquals(1, targets.size)
        assertEquals(selected.identitySha256, targets.single().identitySha256)
        assertEquals("비슷한 이름", targets.single().displayName)
        assertEquals(EXTENSION_ID, targets.single().extensionId)
        assertEquals(PACKAGE_NAME, targets.single().packageName)
        assertEquals(SIGNER, targets.single().wrapperSignerSha256)
    }

    @Test
    fun `이름이 같아도 UseExisting 선택 없이는 자동 연결하지 않는다`() = runTest {
        val ledger = ledger()
        ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:existing",
                displayName = "Same extension",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ledger.reserve(
                unstableRequest(
                    displayName = "Same extension",
                    selection = null,
                ),
            )
        }
    }

    @Test
    fun `UseExisting은 같은 프로필 converted 후보만 허용한다`() = runTest {
        val ledger = ledger()
        val converted = ledger.reserve(
            stableRequest(logicalSourceIdentity = "project:converted"),
        )
        val passthrough = ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:passthrough",
                mode = InstallationIdentityMode.PASSTHROUGH,
                crxKeyAlias = null,
            ),
        )
        val otherMajor = ledger.reserve(
            stableRequest(
                logicalSourceIdentity = "project:other",
                profileMajorVersion = 31,
            ),
        )

        val reused = ledger.reserve(
            unstableRequest(
                selection = InstallationIdentitySelection.UseExisting(
                    converted.identitySha256,
                ),
            ),
        )
        assertEquals(converted.identitySha256, reused.identitySha256)
        assertEquals(2, reused.versionCode)

        listOf(passthrough.identitySha256, otherMajor.identitySha256).forEach { identity ->
            assertFailsWith<IllegalArgumentException> {
                ledger.reserve(
                    unstableRequest(
                        selection = InstallationIdentitySelection.UseExisting(identity),
                    ),
                )
            }
        }
    }

    @Test
    fun `existing extension ID package signer key 불일치를 모두 차단한다`() = runTest {
        val ledger = ledger()
        val reserved = ledger.reserve(
            stableRequest(logicalSourceIdentity = "project:mismatch"),
        )
        val selection = InstallationIdentitySelection.UseExisting(reserved.identitySha256)

        listOf(
            unstableRequest(selection = selection).copy(extensionId = "b".repeat(32)),
            unstableRequest(selection = selection).copy(
                packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
                extensionId = "b".repeat(32),
            ),
            unstableRequest(selection = selection).copy(wrapperSignerSha256 = "b".repeat(64)),
            unstableRequest(selection = selection).copy(crxKeyAlias = "sixbridge-crx-other"),
        ).forEach { request ->
            assertFailsWith<IllegalArgumentException> {
                ledger.reserve(request)
            }
        }
    }

    @Test
    fun `extension ID와 package 자체가 맞지 않으면 새 신원도 거부한다`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            ledger().reserve(
                stableRequest(packageName = "dev.sixbridge.ext.wrong"),
            )
        }

        assertTrue(error.message.orEmpty().contains("package"))
    }

    private fun ledger() = ConversionIdentityLedger(FakeConversionIdentityDao())

    private fun stableRequest(
        logicalSourceIdentity: String = "project:stable",
        profileMajorVersion: Int = 30,
        mode: InstallationIdentityMode = InstallationIdentityMode.CONVERTED,
        displayName: String = "Extension",
        extensionId: String = EXTENSION_ID,
        packageName: String = PACKAGE_NAME,
        wrapperSignerSha256: String = SIGNER,
        crxKeyAlias: String? = "sixbridge-crx-stable",
        installedVersionCode: Long? = null,
    ) = ConversionIdentityRequest(
        logicalSourceIdentity = logicalSourceIdentity,
        profileMajorVersion = profileMajorVersion,
        mode = mode,
        displayName = displayName,
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        crxKeyAlias = crxKeyAlias,
        installedVersionCode = installedVersionCode,
        selection = null,
    )

    private fun unstableRequest(
        profileMajorVersion: Int = 30,
        displayName: String = "Extension",
        extensionId: String = EXTENSION_ID,
        packageName: String = PACKAGE_NAME,
        wrapperSignerSha256: String = SIGNER,
        crxKeyAlias: String? = "sixbridge-crx-stable",
        installedVersionCode: Long? = null,
        selection: InstallationIdentitySelection?,
    ) = ConversionIdentityRequest(
        logicalSourceIdentity = null,
        profileMajorVersion = profileMajorVersion,
        mode = InstallationIdentityMode.CONVERTED,
        displayName = displayName,
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        crxKeyAlias = crxKeyAlias,
        installedVersionCode = installedVersionCode,
        selection = selection,
    )

    private class FakeConversionIdentityDao : ConversionIdentityDao {
        private val rows = linkedMapOf<String, ConversionIdentityEntity>()

        override suspend fun get(identitySha256: String): ConversionIdentityEntity? =
            synchronized(rows) { rows[identitySha256] }

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
    }

    private companion object {
        val EXTENSION_ID = "a".repeat(32)
        val PACKAGE_NAME = "dev.sixbridge.ext.$EXTENSION_ID"
        val SIGNER = "a".repeat(64)
    }
}
