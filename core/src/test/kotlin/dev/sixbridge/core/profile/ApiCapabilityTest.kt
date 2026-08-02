package dev.sixbridge.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiCapabilityTest {
    @Test
    fun `capability key는 유효한 dot separated API 이름만 허용한다`() {
        listOf(
            "",
            "tabs.",
            ".tabs",
            "tabs..query",
            "tabs-query",
            "tabs/query",
            "a".repeat(129),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                ApiCapabilityKey(invalid, "query", ApiExecutionContext.SERVICE_WORKER)
            }
            assertFailsWith<IllegalArgumentException>(invalid) {
                ApiCapabilityKey("tabs", invalid, ApiExecutionContext.SERVICE_WORKER)
            }
        }

        assertEquals(
            "app.runtime",
            ApiCapabilityKey(
                namespace = "app.runtime",
                member = "onLaunched.addListener",
                context = ApiExecutionContext.SERVICE_WORKER,
            ).namespace,
        )
    }

    @Test
    fun `capability evidence는 probe ID와 lowercase SHA256을 엄격히 검증한다`() {
        listOf("", "probe/id", " probe", "a".repeat(129)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                evidence(probeId = invalid)
            }
        }
        listOf(
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>(invalid) {
                evidence(probeCatalogSha256 = invalid)
            }
        }
    }

    @Test
    fun `capability evidence는 caller collection을 immutable snapshot으로 보관한다`() {
        val permissions = linkedSetOf("tabs")
        val manifestKeys = linkedSetOf("permissions")
        val evidence = evidence(permissions = permissions, manifestKeys = manifestKeys)

        permissions += "cookies"
        manifestKeys.clear()

        assertEquals(setOf("tabs"), evidence.permissions)
        assertEquals(setOf("permissions"), evidence.manifestKeys)
        assertFailsWith<UnsupportedOperationException> {
            (evidence.permissions as MutableSet<String>) += "cookies"
        }
    }

    @Test
    fun `browser profile은 capability를 canonical immutable snapshot으로 보관한다`() {
        val tabs = evidence()
        val alarms = evidence(namespace = "alarms", member = "getAll")
        val capabilities = mutableListOf(tabs, alarms)
        val profile = profile().copy(apiCapabilities = capabilities)

        capabilities.clear()

        assertEquals(listOf(alarms, tabs), profile.apiCapabilities)
        assertFailsWith<UnsupportedOperationException> {
            (profile.apiCapabilities as MutableList<ApiCapabilityEvidence>).clear()
        }
        assertEquals(profile, BrowserProfileJson.decode(BrowserProfileJson.encode(profile)))
    }

    @Test
    fun `capability key는 namespace member context 조합으로 유일하다`() {
        val duplicate = evidence()

        assertFailsWith<IllegalArgumentException> {
            profile().copy(apiCapabilities = listOf(duplicate, duplicate)).validate()
        }
    }

    @Test
    fun `INTERNAL capability namespace는 supported API가 될 수 없다`() {
        val internal = evidence(status = ApiCapabilityStatus.INTERNAL)

        assertFailsWith<IllegalArgumentException> {
            profile().copy(
                supportedApis = setOf(internal.key.namespace),
                apiCapabilities = listOf(internal),
            ).validate()
        }
    }

    @Test
    fun `INTERNAL 검증은 supported API의 namespace 의미를 보존한다`() {
        val internal = evidence(status = ApiCapabilityStatus.INTERNAL)
        val legacyMemberLikeValue = "${internal.key.namespace}.${internal.key.member}"

        profile().copy(
            supportedApis = setOf(legacyMemberLikeValue),
            apiCapabilities = listOf(internal),
        ).validate()
    }

    private fun evidence(
        namespace: String = "tabs",
        member: String = "query",
        status: ApiCapabilityStatus = ApiCapabilityStatus.VERIFIED,
        probeId: String = "tabs-query-service-worker",
        probeCatalogSha256: String = "a".repeat(64),
        permissions: Set<String> = setOf("tabs"),
        manifestKeys: Set<String> = setOf("permissions"),
    ) = ApiCapabilityEvidence(
        key = ApiCapabilityKey(
            namespace = namespace,
            member = member,
            context = ApiExecutionContext.SERVICE_WORKER,
        ),
        status = status,
        permissions = permissions,
        manifestKeys = manifestKeys,
        detail = "빈 query 결과를 확인함",
        probeId = probeId,
        probeCatalogSha256 = probeCatalogSha256,
    )

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("storage"),
        wrapperContract = WrapperContract.complete(),
    )
}
