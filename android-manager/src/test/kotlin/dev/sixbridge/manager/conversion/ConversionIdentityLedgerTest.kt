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
    fun `RESERVED와 PACKAGING 실패 release는 owner token으로 lease만 결정적으로 제거한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val firstKey = passthroughKey()
            ledger.reserve(
                reservation(firstKey, binding = bindingFor(firstKey)),
                ownerToken = JOB_ONE,
            )

            assertTrue(!ledger.releaseFailedAttempt(jobToken(99)))
            assertEquals("RESERVED", dao.getLease(firstKey.identitySha256)?.state)
            assertTrue(ledger.releaseFailedAttempt(JOB_ONE))
            assertNull(dao.getLease(firstKey.identitySha256))
            assertTrue(dao.get(firstKey.identitySha256) != null)
            assertTrue(!ledger.releaseFailedAttempt(JOB_ONE))

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

            assertTrue(ledger.releaseFailedAttempt(JOB_TWO))
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
            assertTrue(ledger.releaseFailedAttempt(JOB_ONE))
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
    fun `미예약 실패 release는 packager 없이 lease를 버리지 않는다`() = runTest {
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
            ledger.releaseFailedAttempt(JOB_ONE)
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
    fun `PACKAGED proof는 exact BUNDLE_READY에서 active와 CLEANING_COMMITTED로 함께 commit한다`() =
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
                    dao.claimBundleReadyCommit(
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
                dao.claimBundleReadyCommit(JOB_ONE, 7, "d".repeat(64))
            }
            dao.finalizationJob = bundleReadyJob(JOB_ONE, 7, "d".repeat(64))
            val committed = requireNotNull(
                dao.claimBundleReadyCommit(JOB_ONE, 7, "d".repeat(64)),
            )
            assertTrue(committed.state.startsWith("CLEANING_COMMITTED:"))
            val active = requireNotNull(dao.get(key.identitySha256))
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
            assertNull(active.provisionOwnerToken)
            assertEquals(reserved.versionCode, active.lastSuccessfulVersionCode)
            assertEquals(
                committed,
                dao.claimBundleReadyCommit(JOB_ONE, 7, "d".repeat(64)),
            )
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
                    dao.claimBundleReadyCommit(ownerToken, 7, "d".repeat(64))
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
    fun `같은 job의 연속 실패 release는 versionCode를 중복하거나 재사용하지 않는다`() =
        runTest {
            val ledger = ledger()
            val key = passthroughKey()

            val values = (1..8).map {
                reserveAndRelease(
                    ledger,
                    reservation(key, binding = bindingFor(key)),
                    JOB_ONE,
                ).versionCode
            }

            assertEquals((1..8).toSet(), values.toSet())
            assertEquals(
                9,
                reserveAndRelease(
                    ledger,
                    reservation(key, binding = bindingFor(key)),
                    JOB_ONE,
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
                ownerToken = JOB_ONE,
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
                ownerToken = JOB_ONE,
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
    fun `같은 job의 CreateNew 재시도는 실패 version을 넘기고 PROVISIONAL key를 재사용한다`() =
        runTest {
            val localIdentities = ArrayDeque(
                listOf(
                    UUID.fromString("11111111-2222-4333-8444-555555555555"),
                    UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"),
                ),
            )
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(
                dao = dao,
                localIdentityFactory = { localIdentities.removeFirst() },
            )

            val firstKey = resolveSelectionForOwner(
                ledger = ledger,
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.CreateNew,
                ownerToken = JOB_ONE,
            )
            val firstBinding = bindingFor(firstKey)
            val first = ledger.reserve(
                reservation(firstKey, binding = firstBinding),
                ownerToken = JOB_ONE,
            )
            dao.claimForPackaging(
                requested = entity(firstKey, firstBinding),
                versionCode = first.versionCode,
                ownerToken = JOB_ONE,
                crxPublicKeySha256 = firstBinding.crxPublicKeySha256,
                packagingState = "PACKAGING:11111111-1111-4111-8111-111111111111",
            )
            assertTrue(releaseFailedAttemptForTests(ledger, JOB_ONE))

            val secondKey = resolveSelectionForOwner(
                ledger = ledger,
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.CreateNew,
                ownerToken = JOB_ONE,
            )
            val secondBinding = bindingFor(secondKey)
            val second = ledger.reserve(
                reservation(secondKey, binding = secondBinding),
                ownerToken = JOB_ONE,
            )

            assertEquals(first.identityKey, second.identityKey)
            assertEquals(first.extensionId, second.extensionId)
            assertEquals(first.crxKeyAlias, second.crxKeyAlias)
            assertEquals(1, first.versionCode)
            assertEquals(2, second.versionCode)
            assertEquals(1, dao.rowCount())
            assertTrue(releaseFailedAttemptForTests(ledger, JOB_ONE))
        }

    @Test
    fun `다른 job의 CreateNew는 다른 PROVISIONAL identity를 소유한다`() = runTest {
        val localIdentities = ArrayDeque(
            listOf(
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"),
            ),
        )
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(
            dao = dao,
            localIdentityFactory = { localIdentities.removeFirst() },
        )

        val first = resolveSelectionForOwner(
            ledger,
            null,
            30,
            InstallationIdentityMode.CONVERTED,
            InstallationIdentitySelection.CreateNew,
            JOB_ONE,
        )
        ledger.reserve(reservation(first), JOB_ONE)
        assertTrue(releaseFailedAttemptForTests(ledger, JOB_ONE))
        val second = resolveSelectionForOwner(
            ledger,
            null,
            30,
            InstallationIdentityMode.CONVERTED,
            InstallationIdentitySelection.CreateNew,
            JOB_TWO,
        )
        ledger.reserve(reservation(second), JOB_TWO)

        assertNotEquals(first, second)
        assertEquals(2, dao.rowCount())
        assertEquals(JOB_ONE, dao.get(first.identitySha256)?.provisionOwnerToken)
        assertEquals(JOB_TWO, dao.get(second.identitySha256)?.provisionOwnerToken)
        assertTrue(releaseFailedAttemptForTests(ledger, JOB_TWO))
    }

    @Test
    fun `PROVISIONAL owner는 UseExisting과 다른 source 또는 profile로 선택을 바꿀 수 없다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(
                dao = dao,
                localIdentityFactory = {
                    UUID.fromString("11111111-2222-4333-8444-555555555555")
                },
            )
            val provisional = resolveSelectionForOwner(
                ledger,
                null,
                30,
                InstallationIdentityMode.CONVERTED,
                InstallationIdentitySelection.CreateNew,
                JOB_ONE,
            )
            ledger.reserve(reservation(provisional), JOB_ONE)
            assertTrue(releaseFailedAttemptForTests(ledger, JOB_ONE))
            val active = InstallationIdentityKey.create(
                LogicalSourceIdentity.projectMarker("2".repeat(32)),
                30,
                InstallationIdentityMode.CONVERTED,
            )
            dao.insert(entity(active, bindingFor(active)))

            assertFailsWith<IllegalArgumentException> {
                resolveSelectionForOwner(
                    ledger,
                    null,
                    30,
                    InstallationIdentityMode.CONVERTED,
                    InstallationIdentitySelection.UseExisting(active.identitySha256),
                    JOB_ONE,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                resolveSelectionForOwner(
                    ledger,
                    LogicalSourceIdentity.projectMarker("3".repeat(32)),
                    30,
                    InstallationIdentityMode.CONVERTED,
                    null,
                    JOB_ONE,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                resolveSelectionForOwner(
                    ledger,
                    null,
                    31,
                    InstallationIdentityMode.CONVERTED,
                    InstallationIdentitySelection.CreateNew,
                    JOB_ONE,
                )
            }
            assertEquals(
                ConversionIdentityLifecycleState.PROVISIONAL.name,
                dao.get(provisional.identitySha256)?.lifecycleState,
            )
        }

    @Test
    fun `UseExisting은 canonical hash를 재검증하고 같은 converted key를 package 단계에 돌려준다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val original = stableKey()
            val binding = bindingFor(original)
            dao.insert(
                entity(original, binding).copy(
                    lastReservedVersionCode = 1,
                    lastSuccessfulVersionCode = 1,
                ),
            )

            val selected = ledger.resolveSelection(
                logicalSourceIdentity = null,
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED,
                selection = InstallationIdentitySelection.UseExisting(original.identitySha256),
                ownerToken = JOB_TWO,
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
    fun `UseExisting은 손상 hash와 passthrough를 거부하고 이전 메이저 profile의 ACTIVE 대상은 재사용한다`() = runTest {
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
                JOB_ONE,
            )
        }
        val reusedCrossMajor = ledger.resolveSelection(
            null,
            31,
            InstallationIdentityMode.CONVERTED,
            InstallationIdentitySelection.UseExisting(converted.identitySha256),
            JOB_ONE,
        )
        assertEquals(converted, reusedCrossMajor)
        assertFailsWith<IllegalArgumentException> {
            ledger.resolveSelection(
                null,
                30,
                InstallationIdentityMode.CONVERTED,
                InstallationIdentitySelection.UseExisting(passthrough.identitySha256),
                JOB_ONE,
            )
        }
    }

    @Test
    fun `안정 신원은 브라우저 메이저 업데이트 후에도 기존 ACTIVE 신원을 재사용해 덮어쓰기 업데이트한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val storeSource = LogicalSourceIdentity.storeExtensionId("c".repeat(32))
        val v30Passthrough = InstallationIdentityKey.create(
            storeSource,
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        dao.insert(entity(v30Passthrough, bindingFor(v30Passthrough)))

        val v30Converted = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("d".repeat(32)),
            30,
            InstallationIdentityMode.CONVERTED,
        )
        dao.insert(entity(v30Converted, bindingFor(v30Converted)))

        val resolvedPassthrough = ledger.resolveSelection(
            logicalSourceIdentity = storeSource,
            profileMajorVersion = 31,
            mode = InstallationIdentityMode.PASSTHROUGH,
            selection = null,
            ownerToken = JOB_ONE,
        )
        val resolvedConverted = ledger.resolveSelection(
            logicalSourceIdentity = LogicalSourceIdentity.storeExtensionId("d".repeat(32)),
            profileMajorVersion = 31,
            mode = InstallationIdentityMode.CONVERTED,
            selection = null,
            ownerToken = JOB_TWO,
        )
        val brandNew = ledger.resolveSelection(
            logicalSourceIdentity = LogicalSourceIdentity.storeExtensionId("e".repeat(32)),
            profileMajorVersion = 31,
            mode = InstallationIdentityMode.PASSTHROUGH,
            selection = null,
            ownerToken = JOB_ONE,
        )

        assertEquals(v30Passthrough, resolvedPassthrough)
        assertEquals(v30Converted, resolvedConverted)
        assertEquals(31, brandNew.profileMajorVersion)
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
    fun `기존 후보는 같은 profile의 ACTIVE converted만 노출한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val selected = stableKey()
        dao.insert(entity(selected, bindingFor(selected)))
        val provisional = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("2".repeat(32)),
            30,
            InstallationIdentityMode.CONVERTED,
        )
        dao.insert(
            entity(provisional, bindingFor(provisional)).copy(
                lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
                provisionOwnerToken = JOB_ONE,
            ),
        )
        val legacy = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("3".repeat(32)),
            30,
            InstallationIdentityMode.CONVERTED,
        )
        dao.insert(
            entity(legacy, bindingFor(legacy)).copy(
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
            ),
        )
        val passthrough = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("b".repeat(32)),
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        dao.insert(entity(passthrough, bindingFor(passthrough)))
        val other = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("4".repeat(32)),
            31,
            InstallationIdentityMode.CONVERTED,
        )
        dao.insert(entity(other, bindingFor(other)))

        val targets = ledger.listExistingTargets(31)

        assertEquals(
            setOf(selected.identitySha256, other.identitySha256),
            targets.map { it.identitySha256 }.toSet(),
        )
        assertTrue(targets.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `UseExisting은 PROVISIONAL과 LEGACY_UNKNOWN 대상을 거부한다`() = runTest {
        val dao = FakeConversionIdentityDao()
        val ledger = ConversionIdentityLedger(dao)
        val provisional = stableKey()
        dao.insert(
            entity(provisional, bindingFor(provisional)).copy(
                lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
                provisionOwnerToken = JOB_ONE,
            ),
        )
        val legacy = InstallationIdentityKey.create(
            LogicalSourceIdentity.projectMarker("2".repeat(32)),
            30,
            InstallationIdentityMode.CONVERTED,
        )
        dao.insert(
            entity(legacy, bindingFor(legacy)).copy(
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
            ),
        )

        listOf(provisional, legacy).forEach { inactive ->
            assertFailsWith<IllegalArgumentException> {
                resolveSelectionForOwner(
                    ledger,
                    null,
                    30,
                    InstallationIdentityMode.CONVERTED,
                    InstallationIdentitySelection.UseExisting(inactive.identitySha256),
                    JOB_TWO,
                )
            }
        }
    }

    @Test
    fun `reserve는 다른 owner의 PROVISIONAL과 inactive lifecycle을 거부한다`() = runTest {
        listOf(
            ConversionIdentityLifecycleState.PROVISIONAL to JOB_ONE,
            ConversionIdentityLifecycleState.LEGACY_UNKNOWN to null,
            ConversionIdentityLifecycleState.CLEANING to null,
        ).forEachIndexed { index, (state, provisionOwnerToken) ->
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val key = InstallationIdentityKey.create(
                LogicalSourceIdentity.projectMarker((index + 5).toString().repeat(32)),
                30,
                InstallationIdentityMode.CONVERTED,
            )
            dao.insert(
                entity(key, bindingFor(key)).copy(
                    lifecycleState = state.name,
                    provisionOwnerToken = provisionOwnerToken,
                ),
            )

            assertFailsWith<IllegalArgumentException> {
                ledger.reserve(reservation(key), JOB_TWO)
            }
            assertNull(dao.getLease(key.identitySha256))
        }
    }

    @Test
    fun `ACTIVE 업데이트 실패는 key와 성공 version을 보존하고 reserved version만 증가시킨다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val ledger = ConversionIdentityLedger(dao)
            val key = stableKey()
            val active = entity(key, bindingFor(key)).copy(
                lastReservedVersionCode = 7,
                lifecycleState = ConversionIdentityLifecycleState.ACTIVE.name,
                provisionOwnerToken = null,
                lastSuccessfulVersionCode = 6,
                lifecycleUpdatedAtEpochMillis = 1234,
            )
            dao.insert(active)

            val reserved = ledger.reserve(reservation(key), JOB_TWO)
            assertTrue(releaseFailedAttemptForTests(ledger, JOB_TWO))

            val stored = requireNotNull(dao.get(key.identitySha256))
            assertEquals(key.identitySha256, stored.identitySha256)
            assertEquals(8, reserved.versionCode)
            assertEquals(8, stored.lastReservedVersionCode)
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, stored.lifecycleState)
            assertNull(stored.provisionOwnerToken)
            assertEquals(6, stored.lastSuccessfulVersionCode)
            assertEquals(1234, stored.lifecycleUpdatedAtEpochMillis)
        }

    @Test
    fun `legacy promotion transaction은 exact snapshot에 success version과 high watermark를 함께 commit한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val key = passthroughKey()
            val legacy = entity(key, bindingFor(key)).copy(
                lastReservedVersionCode = 19,
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                crxPublicKeySha256 = null,
            )
            val proof = VerifiedIdentityProof(
                packageName = legacy.packageName,
                extensionId = legacy.extensionId,
                wrapperSignerSha256 = legacy.wrapperSignerSha256,
                versionCode = 17,
            )
            dao.force(legacy)

            assertTrue(
                dao.promoteLegacyIdentity(
                    expected = legacy,
                    proof = proof,
                    verifiedPublicKeySha256 = null,
                    lifecycleUpdatedAtEpochMillis = 1234,
                    observedCurrentBundle = null,
                ),
            )

            val active = requireNotNull(dao.get(legacy.identitySha256))
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
            assertEquals(17, active.lastSuccessfulVersionCode)
            assertEquals(19, active.lastReservedVersionCode)
            assertEquals(1234, active.lifecycleUpdatedAtEpochMillis)
            assertTrue(
                !dao.promoteLegacyIdentity(
                    expected = legacy,
                    proof = proof,
                    verifiedPublicKeySha256 = null,
                    lifecycleUpdatedAtEpochMillis = 1235,
                    observedCurrentBundle = null,
                ),
            )
        }

    @Test
    fun `legacy BUNDLE_READY promotion CAS는 reader 후 job snapshot이 바뀌면 거부한다`() =
        runTest {
            val dao = FakeConversionIdentityDao()
            val key = passthroughKey()
            val legacy = entity(key, bindingFor(key)).copy(
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                crxPublicKeySha256 = null,
            )
            val proof = VerifiedIdentityProof(
                packageName = legacy.packageName,
                extensionId = legacy.extensionId,
                wrapperSignerSha256 = legacy.wrapperSignerSha256,
                versionCode = 3,
            )
            val observed = CurrentBundleReadyProof.Verified(
                ownerToken = JOB_ONE,
                generation = 4,
                bundleSha256 = "d".repeat(64),
                proof = proof,
            )
            dao.force(legacy)
            dao.finalizationJob = bundleReadyJob(
                token = JOB_ONE,
                generation = 5,
                bundleSha256 = observed.bundleSha256,
            )

            assertTrue(
                !dao.promoteLegacyIdentity(
                    expected = legacy,
                    proof = proof,
                    verifiedPublicKeySha256 = null,
                    lifecycleUpdatedAtEpochMillis = 1234,
                    observedCurrentBundle = observed,
                ),
            )
            assertEquals(legacy, dao.get(legacy.identitySha256))
        }

    private fun ledger() = ConversionIdentityLedger(FakeConversionIdentityDao())

    private suspend fun resolveSelectionForOwner(
        ledger: ConversionIdentityLedger,
        logicalSourceIdentity: LogicalSourceIdentity?,
        profileMajorVersion: Int,
        mode: InstallationIdentityMode,
        selection: InstallationIdentitySelection?,
        ownerToken: String,
    ): InstallationIdentityKey = ledger.resolveSelection(
        logicalSourceIdentity,
        profileMajorVersion,
        mode,
        selection,
        ownerToken,
    )

    private suspend fun releaseFailedAttemptForTests(
        ledger: ConversionIdentityLedger,
        ownerToken: String,
    ): Boolean = ledger.releaseFailedAttempt(ownerToken)

    private suspend fun reserveAndRelease(
        ledger: ConversionIdentityLedger,
        request: ConversionIdentityReservation,
        ownerToken: String,
    ): ConversionIdentityLedger.Reservation {
        val reservation = ledger.reserve(request, ownerToken)
        assertTrue(ledger.releaseFailedAttempt(ownerToken))
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

        override suspend fun findByProvisionOwnerToken(ownerToken: String): ConversionIdentityEntity? =
            synchronized(rows) {
                rows.values.singleOrNull { it.provisionOwnerToken == ownerToken }
            }

        override suspend fun listByLifecycle(
            lifecycleState: String,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter { it.lifecycleState == lifecycleState }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                .sortedBy { it.identitySha256 }
        }

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

        override suspend fun transitionIdentityToCleaning(
            identitySha256: String,
            expectedLifecycleState: String,
            expectedProvisionOwnerToken: String?,
            expectedCrxPublicKeySha256: String?,
            expectedCrxKeyAlias: String?,
            expectedMode: String,
            expectedExtensionId: String,
            expectedPackageName: String,
            expectedWrapperSignerSha256: String,
            expectedLastReservedVersionCode: Int,
            cleanupCrxPublicKeySha256: String?,
            lifecycleUpdatedAtEpochMillis: Long,
        ): Int = synchronized(rows) {
            val current = rows[identitySha256]
            if (current?.lifecycleState != expectedLifecycleState ||
                current.provisionOwnerToken != expectedProvisionOwnerToken ||
                current.crxPublicKeySha256 != expectedCrxPublicKeySha256 ||
                current.crxKeyAlias != expectedCrxKeyAlias ||
                current.mode != expectedMode ||
                current.extensionId != expectedExtensionId ||
                current.packageName != expectedPackageName ||
                current.wrapperSignerSha256 != expectedWrapperSignerSha256 ||
                current.lastReservedVersionCode != expectedLastReservedVersionCode
            ) {
                0
            } else {
                rows[identitySha256] = current.copy(
                    lifecycleState = ConversionIdentityLifecycleState.CLEANING.name,
                    crxPublicKeySha256 = cleanupCrxPublicKeySha256,
                    lifecycleUpdatedAtEpochMillis = lifecycleUpdatedAtEpochMillis,
                )
                1
            }
        }

        override suspend fun retireCleaningIdentity(
            identitySha256: String,
            provisionOwnerToken: String?,
            crxKeyAlias: String?,
            crxPublicKeySha256: String?,
            mode: String,
            extensionId: String,
            packageName: String,
            wrapperSignerSha256: String,
            lifecycleUpdatedAtEpochMillis: Long,
        ): Int = synchronized(rows) {
            val current = rows[identitySha256]
            if (current?.lifecycleState != ConversionIdentityLifecycleState.CLEANING.name ||
                current.provisionOwnerToken != provisionOwnerToken ||
                current.crxKeyAlias != crxKeyAlias ||
                current.crxPublicKeySha256 != crxPublicKeySha256 ||
                current.mode != mode ||
                current.extensionId != extensionId ||
                current.packageName != packageName ||
                current.wrapperSignerSha256 != wrapperSignerSha256
            ) {
                0
            } else {
                rows[identitySha256] = current.copy(
                    lifecycleState = ConversionIdentityLifecycleState.RETIRED.name,
                    provisionOwnerToken = null,
                    lifecycleUpdatedAtEpochMillis = lifecycleUpdatedAtEpochMillis,
                )
                1
            }
        }

        override suspend fun updateForReservation(
            identitySha256: String,
            displayName: String,
            lastReservedVersionCode: Int,
            expectedLifecycleState: String,
            expectedProvisionOwnerToken: String?,
        ): Int = synchronized(rows) {
            val current = rows[identitySha256]
            if (
                current?.lifecycleState != expectedLifecycleState ||
                current.provisionOwnerToken != expectedProvisionOwnerToken
            ) {
                0
            } else {
                rows[identitySha256] = current.copy(
                    displayName = displayName,
                    lastReservedVersionCode = lastReservedVersionCode,
                )
                1
            }
        }

        override suspend fun reactivateRetiredForReservation(
            identitySha256: String,
            displayName: String,
            expectedLastReservedVersionCode: Int,
            lastReservedVersionCode: Int,
            reservationOwnerToken: String,
            expectedMode: String,
            expectedExtensionId: String,
            expectedPackageName: String,
            expectedWrapperSignerSha256: String,
            expectedCrxKeyAlias: String?,
            expectedCrxPublicKeySha256: String?,
            lifecycleUpdatedAtEpochMillis: Long,
        ): Int = synchronized(rows) {
            val current = rows[identitySha256]
            if (current?.lifecycleState != ConversionIdentityLifecycleState.RETIRED.name ||
                current.provisionOwnerToken != null ||
                current.lastReservedVersionCode != expectedLastReservedVersionCode ||
                current.mode != expectedMode ||
                current.extensionId != expectedExtensionId ||
                current.packageName != expectedPackageName ||
                current.wrapperSignerSha256 != expectedWrapperSignerSha256 ||
                current.crxKeyAlias != expectedCrxKeyAlias ||
                current.crxPublicKeySha256 != expectedCrxPublicKeySha256
            ) {
                0
            } else {
                rows[identitySha256] = current.copy(
                    displayName = displayName,
                    lastReservedVersionCode = lastReservedVersionCode,
                    lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
                    provisionOwnerToken = reservationOwnerToken,
                    lifecycleUpdatedAtEpochMillis = lifecycleUpdatedAtEpochMillis,
                )
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
                require(current.crxPublicKeySha256 == crxPublicKeySha256)
                when (current.lifecycleState) {
                    ConversionIdentityLifecycleState.PROVISIONAL.name ->
                        require(current.provisionOwnerToken == reservationOwnerToken)
                    ConversionIdentityLifecycleState.ACTIVE.name ->
                        require(current.provisionOwnerToken == null)
                    else -> throw IllegalArgumentException("inactive lifecycle")
                }
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
                require(rows.values.none {
                    it.provisionOwnerToken != null &&
                        it.provisionOwnerToken == reservationOwnerToken
                })
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

        override suspend fun listByModeProfileAndLifecycle(
            mode: String,
            profileMajorVersion: Int,
            lifecycleState: String,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter {
                it.mode == mode &&
                    it.profileMajorVersion == profileMajorVersion &&
                    it.lifecycleState == lifecycleState
            }.sortedBy(ConversionIdentityEntity::identitySha256)
        }

        override suspend fun listByModeAndProfile(
            mode: String,
            profileMajorVersion: Int,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter {
                it.mode == mode && it.profileMajorVersion == profileMajorVersion
            }.sortedBy(ConversionIdentityEntity::identitySha256)
        }

        override suspend fun listByModeAndLifecycle(
            mode: String,
            lifecycleState: String,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter {
                it.mode == mode && it.lifecycleState == lifecycleState
            }.sortedWith(
                compareBy<ConversionIdentityEntity>({ it.displayName.lowercase() }, { it.identitySha256 }),
            )
        }

        override suspend fun listByLogicalSourceModeAndLifecycle(
            logicalSourceIdentity: String,
            mode: String,
            lifecycleState: String,
        ): List<ConversionIdentityEntity> = synchronized(rows) {
            rows.values.filter {
                it.logicalSourceIdentity == logicalSourceIdentity &&
                    it.mode == mode &&
                    it.lifecycleState == lifecycleState
            }.sortedWith(
                compareByDescending<ConversionIdentityEntity> { it.profileMajorVersion }
                    .thenBy { it.identitySha256 },
            )
        }

        fun force(entity: ConversionIdentityEntity) {
            synchronized(rows) { rows[entity.identitySha256] = entity }
        }

        fun rowCount(): Int = synchronized(rows) { rows.size }
    }

    private companion object {
        const val SIGNER =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val JOB_ONE = "00000000-0000-4000-8000-000000000001"
        const val JOB_TWO = "00000000-0000-4000-8000-000000000002"
    }
}
