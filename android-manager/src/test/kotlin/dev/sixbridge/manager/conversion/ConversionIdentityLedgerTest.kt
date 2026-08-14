package dev.sixbridge.manager.conversion

import java.util.UUID
import java.lang.reflect.Modifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConversionIdentityLedgerTest {
    @Test
    fun `caller는 예약 capability를 생성 복사하거나 finish를 직접 호출할 수 없다`() {
        val issuedType = ConversionIdentityLedger::class.java.declaredClasses.single {
            it.simpleName == "IssuedReservation"
        }
        val receiptType = ConversionIdentityLedger::class.java.declaredClasses.single {
            it.simpleName == "IssuedPackagingReceipt"
        }

        assertTrue(Modifier.isPrivate(issuedType.modifiers))
        assertTrue(issuedType.declaredMethods.none { it.name == "copy" })
        assertTrue(Modifier.isPrivate(receiptType.modifiers))
        assertTrue(receiptType.declaredMethods.none { it.name == "copy" })
        assertTrue(
            ConversionIdentityLedger::class.java.methods.none {
                it.name == "claimForPackaging" || it.name == "finishPackaging"
            },
        )
        assertTrue(
            ConversionIdentityLedger::class.java.declaredMethods.none { method ->
                !Modifier.isPrivate(method.modifiers) &&
                    method.parameterTypes.any { parameter ->
                        parameter.name.startsWith("kotlin.jvm.functions.Function")
                    }
            },
        )
    }

    @Test
    fun `RESERVED와 PACKAGING abort는 owner token으로 lease만 결정적으로 제거한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val firstKey = passthroughKey()
            ledger.reserve(
                reservation(firstKey, binding = bindingFor(firstKey)),
                ownerToken = JOB_ONE,
            )

            assertTrue(!ledger.abortReservationByOwnerToken(jobToken(99)))
            assertEquals("RESERVED", dao.getLease(firstKey.identitySha256)?.state)
            assertTrue(ledger.abortReservationByOwnerToken(JOB_ONE))
            assertNull(dao.getLease(firstKey.identitySha256))
            assertTrue(dao.get(firstKey.identitySha256) != null)
            assertTrue(!ledger.abortReservationByOwnerToken(JOB_ONE))

            val secondKey = InstallationIdentityKey.create(
                LogicalSourceIdentity.storeExtensionId("c".repeat(32)),
                30,
                InstallationIdentityMode.PASSTHROUGH,
            )
            val reserved = ledger.reserve(
                reservation(secondKey, binding = bindingFor(secondKey)),
                ownerToken = JOB_TWO,
            )
            dao.claimForPackaging(
                requested = entity(secondKey, bindingFor(secondKey)),
                versionCode = reserved.versionCode,
                ownerToken = JOB_TWO,
                crxPublicKeySha256 = null,
                packagingState = "PACKAGING:11111111-1111-4111-8111-111111111111",
            )

            assertTrue(ledger.abortReservationByOwnerToken(JOB_TWO))
            assertNull(dao.getLease(secondKey.identitySha256))
            assertTrue(dao.get(secondKey.identitySha256) != null)
        }

    @Test
    fun `PREPARED 예약은 signed CRX ID를 bind하고 Android CRX key를 만들지 않는다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val signedCrxId = "d".repeat(32)
            val key = InstallationIdentityKey.create(
                LogicalSourceIdentity.storeExtensionId(signedCrxId),
                30,
                InstallationIdentityMode.PREPARED,
            )
            val binding = ConversionPackageBinding(
                extensionId = signedCrxId,
                packageName = "dev.sixbridge.ext.$signedCrxId",
                wrapperSignerSha256 = SIGNER,
                crxKeyAlias = null,
                crxPublicKeySha256 = null,
            )

            ledger.reserve(
                reservation(key, binding),
                ownerToken = JOB_ONE,
            )

            val stored = requireNotNull(dao.get(key.identitySha256))
            val lease = requireNotNull(dao.getLease(key.identitySha256))
            assertEquals(InstallationIdentityMode.PREPARED.name, stored.mode)
            assertEquals(signedCrxId, stored.extensionId)
            assertNull(stored.crxKeyAlias)
            assertEquals(signedCrxId, lease.extensionId)
            assertNull(lease.crxKeyAlias)
            assertNull(lease.crxPublicKeySha256)
            assertTrue(ledger.abortReservationByOwnerToken(JOB_ONE))
        }

    @Test
    fun `새 예약 row는 job owner에 귀속된 PROVISIONAL lifecycle로 시작한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(
            dao = dao,
            currentTimeMillis = { 1234L },
        )
        val key = passthroughKey()

        ledger.reserve(
            reservation(key, binding = bindingFor(key)),
            ownerToken = JOB_ONE,
        )

        val stored = requireNotNull(dao.get(key.identitySha256))
        assertEquals(ConversionIdentityLifecycleState.PROVISIONAL.name, stored.lifecycleState)
        assertEquals(JOB_ONE, stored.provisionOwnerToken)
        assertNull(stored.crxPublicKeySha256)
        assertNull(stored.lastSuccessfulVersionCode)
        assertEquals(1234L, stored.lifecycleUpdatedAtEpochMillis)
    }

    @Test
    fun `미예약 abort는 packager 없이 lease를 버리지 않는다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val key = stableKey()
        dao.insertLease(
            ConversionIdentityLeaseEntity(
                identitySha256 = key.identitySha256,
                crxKeyAlias = bindingFor(key).crxKeyAlias,
                ownerToken = JOB_ONE,
                state = "PENDING",
                createdAtEpochMillis = 1,
                extensionId = null,
                packageName = null,
                wrapperSignerSha256 = null,
                crxPublicKeySha256 = null,
                reservedVersionCode = null,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ledger.abortReservationByOwnerToken(JOB_ONE)
        }
        assertEquals("PENDING", dao.getLease(key.identitySha256)?.state)
    }

    @Test
    fun `commit 뒤 반환 전 사망한 예약은 durable job owner만으로 복구한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val key = passthroughKey()
        val request = reservation(key, binding = bindingFor(key))

        dao.reserve(
            requested = entity(key, request.packageBinding),
            installedVersionCode = null,
            readyOwnerToken = null,
            reservationOwnerToken = JOB_ONE,
            createdAtEpochMillis = 1,
            crxPublicKeySha256 = null,
        )

        val recovered = assertIs<ConversionIdentityLedger.Reservation>(
            ledger.recoverNonPublishedByOwnerTokenForTests(JOB_ONE),
        )
        assertEquals(key, recovered.identityKey)
        assertEquals(1, recovered.versionCode)
    }

    @Test
    fun `이전 PACKAGING nonce는 새 claim을 rollback하거나 삭제하지 못한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val key = passthroughKey()
        val reserved = ledger.reserve(
            reservation(key, binding = bindingFor(key)),
            ownerToken = JOB_ONE,
        )
        val firstClaim = "PACKAGING:11111111-1111-4111-8111-111111111111"
        val secondClaim = "PACKAGING:22222222-2222-4222-8222-222222222222"
        dao.claimForPackaging(
            requested = entity(key, bindingFor(key)),
            versionCode = reserved.versionCode,
            ownerToken = JOB_ONE,
            crxPublicKeySha256 = null,
            packagingState = firstClaim,
        )

        assertIs<ConversionIdentityLedger.Reservation>(
            ledger.recoverNonPublishedByOwnerTokenForTests(JOB_ONE),
        )
        dao.claimForPackaging(
            requested = entity(key, bindingFor(key)),
            versionCode = reserved.versionCode,
            ownerToken = JOB_ONE,
            crxPublicKeySha256 = null,
            packagingState = secondClaim,
        )

        assertEquals(
            0,
            dao.transitionLease(
                identitySha256 = key.identitySha256,
                ownerToken = JOB_ONE,
                expectedState = firstClaim,
                nextState = "RESERVED",
            ),
        )
        assertEquals(
            0,
            dao.deleteLease(key.identitySha256, JOB_ONE, firstClaim),
        )
        assertEquals(secondClaim, dao.getLease(key.identitySha256)?.state)
    }

    @Test
    fun `PACKAGED proof는 owner token으로 복구하고 BUNDLE_READY finalize만 제거한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val key = passthroughKey()
            val reserved = ledger.reserve(
                reservation(key, binding = bindingFor(key)),
                ownerToken = JOB_ONE,
            )
            val packaged = "PACKAGED:11111111-1111-4111-8111-111111111111:" +
                "${"c".repeat(64)}:777261707065722e61706b"
            assertEquals(
                1,
                dao.transitionLease(
                    identitySha256 = key.identitySha256,
                    ownerToken = JOB_ONE,
                    expectedState = "RESERVED",
                    nextState = packaged,
                ),
            )

            assertFailsWith<IllegalArgumentException> {
                ledger.recoverNonPublishedByOwnerTokenForTests(JOB_ONE)
            }
            dao.finalizationJob = bundleReadyJob(JOB_ONE, 7, "d".repeat(64))
            listOf(
                Triple(JOB_TWO, 7L, "d".repeat(64)),
                Triple(JOB_ONE, 8L, "d".repeat(64)),
                Triple(JOB_ONE, 7L, "e".repeat(64)),
            ).forEach { (ownerToken, generation, bundleSha256) ->
                assertFailsWith<IllegalArgumentException> {
                    dao.finalizePackagedForBundleReady(
                        ownerToken,
                        generation,
                        bundleSha256,
                    )
                }
                assertEquals(packaged, dao.getLease(key.identitySha256)?.state)
            }
            dao.finalizationJob = requireNotNull(dao.finalizationJob).copy(
                stage = ConversionJobStage.BUILDING.name,
            )
            assertFailsWith<IllegalArgumentException> {
                dao.finalizePackagedForBundleReady(JOB_ONE, 7, "d".repeat(64))
            }
            dao.finalizationJob = bundleReadyJob(JOB_ONE, 7, "d".repeat(64))
            assertTrue(dao.finalizePackagedForBundleReady(JOB_ONE, 7, "d".repeat(64)))
            assertNull(dao.getLease(key.identitySha256))
            assertTrue(dao.finalizePackagedForBundleReady(JOB_ONE, 7, "d".repeat(64)))
            assertEquals(1, reserved.versionCode)
        }

    @Test
    fun `PACKAGED filename proof는 hex 길이 UTF-8 direct child APK 경계를 검증한다`() =
        runTest {
            val invalidProofs = listOf(
                "61".repeat(256),
                "1",
                "ff2e61706b",
                encodeTestHex("../wrapper.apk"),
                encodeTestHex("wrapper.zip"),
            )
            invalidProofs.forEachIndexed { index, fileNameHex ->
                val dao = FakeConversionIdentityDao()
                val ledger = ConversionIdentityLedger(dao)
                val key = passthroughKey()
                val ownerToken = jobToken(index + 20)
                ledger.reserve(
                    reservation(key, binding = bindingFor(key)),
                    ownerToken = ownerToken,
                )
                val malformed = "PACKAGED:11111111-1111-4111-8111-111111111111:" +
                    "${"c".repeat(64)}:$fileNameHex"
                assertEquals(
                    1,
                    dao.transitionLease(
                        identitySha256 = key.identitySha256,
                        ownerToken = ownerToken,
                        expectedState = "RESERVED",
                        nextState = malformed,
                    ),
                )

                assertFailsWith<IllegalArgumentException> {
                    ledger.recoverNonPublishedByOwnerTokenForTests(ownerToken)
                }
                assertFailsWith<IllegalArgumentException> {
                    dao.finalizationJob = bundleReadyJob(ownerToken, 7, "d".repeat(64))
                    dao.finalizePackagedForBundleReady(ownerToken, 7, "d".repeat(64))
                }
                assertEquals(malformed, dao.getLease(key.identitySha256)?.state)
            }
        }

    @Test
    fun `PENDING과 READY는 durable job owner로 식별한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val key = stableKey()
        dao.insertLease(
            ConversionIdentityLeaseEntity(
                identitySha256 = key.identitySha256,
                crxKeyAlias = bindingFor(key).crxKeyAlias,
                ownerToken = JOB_ONE,
                state = "PENDING",
                createdAtEpochMillis = 1,
                extensionId = null,
                packageName = null,
                wrapperSignerSha256 = null,
                crxPublicKeySha256 = null,
                reservedVersionCode = null,
            ),
        )

        val pending = assertIs<ConversionIdentityLedger.UnreservedRecovery>(
            ledger.recoverNonPublishedByOwnerTokenForTests(JOB_ONE),
        )
        assertEquals("PENDING", pending.state)
        assertEquals(key.identitySha256, pending.identitySha256)
    }

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
    fun `완료한 연속 예약은 versionCode를 중복하거나 재사용하지 않는다`() =
        runTest {
            val ledger = ledger()
            val key = passthroughKey()

            val values = (1..8).map { index ->
                reserveAndRelease(
                    ledger,
                    reservation(key, binding = bindingFor(key)),
                    jobToken(index),
                ).versionCode
            }

            assertEquals((1..8).toSet(), values.toSet())
            assertEquals(
                9,
                reserveAndRelease(
                    ledger,
                    reservation(key, binding = bindingFor(key)),
                    jobToken(9),
                ).versionCode,
            )
        }

    @Test
    fun `설치본 floor와 overflow를 fail closed 한다`() = runTest {
        val ledger = ledger()
        val key = stableKey()

        assertEquals(
            8,
            reserveAndRelease(
                ledger,
                reservation(key, binding = bindingFor(key), installedVersionCode = 7),
                JOB_ONE,
            ).versionCode,
        )
        assertFailsWith<IllegalArgumentException> {
            ledger.reserve(
                reservation(
                    key,
                    binding = bindingFor(key),
                    installedVersionCode = Int.MAX_VALUE.toLong(),
                ),
                ownerToken = JOB_TWO,
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

            val reserved = reserveAndRelease(
                ledger,
                reservation(key, binding = binding),
                JOB_ONE,
            )
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
            reserveAndRelease(ledger, reservation(original, binding = binding), JOB_ONE)

            val selected = ledger.resolveSelection(
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.UseExisting(original.identitySha256),
            )
            val reserved = reserveAndRelease(
                ledger,
                reservation(selected, binding = binding),
                JOB_TWO,
            )

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
        reserveAndRelease(ledger, reservation(first, binding = firstBinding), JOB_ONE)

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
                ledger.reserve(
                    reservation(second, binding = collision),
                    ownerToken = JOB_TWO,
                )
            }
        }
    }

    @Test
    fun `기존 후보는 같은 profile의 converted만 노출한다`() = runTest {
        val ledger = ledger()
        val selected = stableKey()
        reserveAndRelease(ledger, reservation(selected), JOB_ONE)
        val passthrough = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("b".repeat(32)),
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        reserveAndRelease(
            ledger,
            reservation(passthrough, binding = bindingFor(passthrough)),
            JOB_TWO,
        )
        val other = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("3".repeat(32)),
            31,
            InstallationIdentityMode.CONVERTED,
        )
        reserveAndRelease(
            ledger,
            reservation(other, binding = bindingFor(other)),
            jobToken(3),
        )

        val targets = ledger.listExistingTargets(30)

        assertEquals(1, targets.size)
        assertEquals(selected.identitySha256, targets.single().identitySha256)
        assertTrue(targets.single().displayName.isNotBlank())
    }

    private fun ledger() = ConversionIdentityLedger(FakeConversionIdentityDao())

    private suspend fun reserveAndRelease(
        ledger: ConversionIdentityLedger,
        request: ConversionIdentityReservation,
        ownerToken: String,
    ): ConversionIdentityLedger.Reservation {
        val reservation = ledger.reserve(request, ownerToken)
        assertTrue(ledger.abortReservationByOwnerToken(ownerToken))
        return reservation
    }

    private fun jobToken(index: Int): String =
        "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun encodeTestHex(value: String): String =
        value.encodeToByteArray().joinToString("") { "%02x".format(it) }

    private fun stableKey() = InstallationIdentityKey.create(
        LogicalSourceIdentity.projectMarker("1".repeat(32)),
        30,
        InstallationIdentityMode.CONVERTED,
    )

    private fun passthroughKey() = InstallationIdentityKey.create(
        LogicalSourceIdentity.storeExtensionId("a".repeat(32)),
        30,
        InstallationIdentityMode.PASSTHROUGH,
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
            crxPublicKeySha256 = if (key.mode == InstallationIdentityMode.CONVERTED) {
                "b".repeat(64)
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
        lifecycleState = ConversionIdentityLifecycleState.ACTIVE.name,
        provisionOwnerToken = null,
        crxPublicKeySha256 = binding.crxPublicKeySha256,
        lastSuccessfulVersionCode = null,
        lifecycleUpdatedAtEpochMillis = 0,
    )

    private fun bundleReadyJob(
        token: String,
        generation: Long,
        bundleSha256: String,
    ) = ConversionJobEntity(
        token = token,
        generation = generation,
        inputKind = OnDeviceInputKind.ZIP.name,
        inputSha256 = "a".repeat(64),
        inputFileName = "${UUID(1, 1)}.input",
        stage = ConversionJobStage.BUNDLE_READY.name,
        approvedAdapterIdsJson = "[]",
        profileSha256 = "b".repeat(64),
        bundleFileName = "${UUID(2, 2)}.six",
        bundleSha256 = bundleSha256,
        cleanupTokensJson = "[]",
        updatedAtEpochMillis = 1,
    )

    private class FakeConversionIdentityDao : ConversionIdentityDao {
        private val rows = linkedMapOf<String, ConversionIdentityEntity>()
        private val leases = linkedMapOf<String, ConversionIdentityLeaseEntity>()
        var finalizationJob: ConversionJobEntity? = null

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

        override suspend fun getLease(identitySha256: String): ConversionIdentityLeaseEntity? =
            synchronized(rows) { leases[identitySha256] }

        override suspend fun getLeaseByOwnerToken(ownerToken: String): ConversionIdentityLeaseEntity? =
            synchronized(rows) { leases.values.singleOrNull { it.ownerToken == ownerToken } }

        override suspend fun getJobForIdentityFinalization(): ConversionJobEntity? =
            finalizationJob

        override suspend fun insertLease(entity: ConversionIdentityLeaseEntity) {
            synchronized(rows) {
                check(leases.putIfAbsent(entity.identitySha256, entity) == null)
            }
        }

        override suspend fun markLeaseReady(
            identitySha256: String,
            ownerToken: String,
            extensionId: String,
            packageName: String,
            wrapperSignerSha256: String,
            crxPublicKeySha256: String,
        ): Int = synchronized(rows) {
            val current = leases[identitySha256]
            if (current?.ownerToken != ownerToken || current.state != "PENDING") {
                0
            } else {
                leases[identitySha256] = current.copy(
                    state = "READY",
                    extensionId = extensionId,
                    packageName = packageName,
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxPublicKeySha256 = crxPublicKeySha256,
                )
                1
            }
        }

        override suspend fun transitionLease(
            identitySha256: String,
            ownerToken: String,
            expectedState: String,
            nextState: String,
        ): Int = synchronized(rows) {
            val current = leases[identitySha256]
            if (current?.ownerToken != ownerToken || current.state != expectedState) {
                0
            } else {
                leases[identitySha256] = current.copy(state = nextState)
                1
            }
        }

        override suspend fun consumeReadyLease(
            identitySha256: String,
            readyOwnerToken: String,
            reservationOwnerToken: String,
            reservedVersionCode: Int,
        ): Int = synchronized(rows) {
            val current = leases[identitySha256]
            if (current?.ownerToken != readyOwnerToken || current.state != "READY") {
                0
            } else {
                leases[identitySha256] = current.copy(
                    ownerToken = reservationOwnerToken,
                    state = "RESERVED",
                    reservedVersionCode = reservedVersionCode,
                )
                1
            }
        }

        override suspend fun deleteLease(
            identitySha256: String,
            ownerToken: String,
            state: String,
        ): Int = synchronized(rows) {
            val current = leases[identitySha256]
            if (current?.ownerToken != ownerToken || current.state != state) {
                0
            } else {
                leases.remove(identitySha256)
                1
            }
        }

        override suspend fun reserve(
            requested: ConversionIdentityEntity,
            installedVersionCode: Long?,
            readyOwnerToken: String?,
            reservationOwnerToken: String,
            createdAtEpochMillis: Long,
            crxPublicKeySha256: String?,
        ): ConversionIdentityEntity = synchronized(rows) {
            require(installedVersionCode == null || installedVersionCode in 0 until Int.MAX_VALUE.toLong())
            val current = rows[requested.identitySha256]
            if (current != null) {
                require(current.copy(
                    displayName = requested.displayName,
                    lastReservedVersionCode = 0,
                    lifecycleState = requested.lifecycleState,
                    provisionOwnerToken = requested.provisionOwnerToken,
                    crxPublicKeySha256 = requested.crxPublicKeySha256,
                    lastSuccessfulVersionCode = requested.lastSuccessfulVersionCode,
                    lifecycleUpdatedAtEpochMillis = requested.lifecycleUpdatedAtEpochMillis,
                ) ==
                    requested.copy(lastReservedVersionCode = 0))
            }
            require(rows.values.none {
                it.identitySha256 != requested.identitySha256 &&
                    (
                        it.extensionId == requested.extensionId ||
                            it.packageName == requested.packageName ||
                            (requested.crxKeyAlias != null && it.crxKeyAlias == requested.crxKeyAlias)
                        )
            })
            val next = maxOf(
                (current?.lastReservedVersionCode ?: 0).toLong() + 1,
                installedVersionCode?.plus(1) ?: 1,
            )
            check(next <= Int.MAX_VALUE)
            val stored = if (current == null) {
                requested.copy(
                    lastReservedVersionCode = next.toInt(),
                    lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
                    provisionOwnerToken = reservationOwnerToken,
                    crxPublicKeySha256 = crxPublicKeySha256,
                    lastSuccessfulVersionCode = null,
                    lifecycleUpdatedAtEpochMillis = createdAtEpochMillis,
                )
            } else {
                requested.copy(
                    lastReservedVersionCode = next.toInt(),
                    lifecycleState = current.lifecycleState,
                    provisionOwnerToken = current.provisionOwnerToken,
                    crxPublicKeySha256 = current.crxPublicKeySha256,
                    lastSuccessfulVersionCode = current.lastSuccessfulVersionCode,
                    lifecycleUpdatedAtEpochMillis = current.lifecycleUpdatedAtEpochMillis,
                )
            }
            stored.also {
                require(leases[requested.identitySha256] == null)
                rows[requested.identitySha256] = stored
                leases[requested.identitySha256] = ConversionIdentityLeaseEntity(
                    identitySha256 = stored.identitySha256,
                    crxKeyAlias = stored.crxKeyAlias,
                    ownerToken = reservationOwnerToken,
                    state = "RESERVED",
                    createdAtEpochMillis = createdAtEpochMillis,
                    extensionId = stored.extensionId,
                    packageName = stored.packageName,
                    wrapperSignerSha256 = stored.wrapperSignerSha256,
                    crxPublicKeySha256 = crxPublicKeySha256,
                    reservedVersionCode = stored.lastReservedVersionCode,
                )
            }
        }

        override suspend fun claimForPackaging(
            requested: ConversionIdentityEntity,
            versionCode: Int,
            ownerToken: String,
            crxPublicKeySha256: String?,
            packagingState: String,
        ) = synchronized(rows) {
            val current = rows[requested.identitySha256]
            val lease = leases[requested.identitySha256]
            require(current?.lastReservedVersionCode == versionCode)
            require(
                lease?.ownerToken == ownerToken &&
                    lease.state == "RESERVED" &&
                    lease.reservedVersionCode == versionCode &&
                    lease.crxPublicKeySha256 == crxPublicKeySha256,
            )
            leases[requested.identitySha256] = lease.copy(state = packagingState)
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
        const val JOB_ONE = "00000000-0000-4000-8000-000000000001"
        const val JOB_TWO = "00000000-0000-4000-8000-000000000002"
    }
}
