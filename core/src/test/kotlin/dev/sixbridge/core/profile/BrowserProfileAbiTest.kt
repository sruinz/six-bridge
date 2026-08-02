package dev.sixbridge.core.profile

import java.lang.reflect.Modifier
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserProfileAbiTest {
    @Test
    fun `base Kotlin client가 head runtime에서 모든 legacy data class 호출을 link한다`() {
        val encoded = requireNotNull(
            javaClass.getResourceAsStream(
                "/abi/browser-profile-base-769b86b-client.b64",
            ),
        ).bufferedReader().use { it.readText() }
        val client = OldBrowserProfileClientLoader(javaClass.classLoader)
            .define(Base64.getMimeDecoder().decode(encoded))

        val (defaulted, components, directCopy, defaultCopy) =
            client.getMethod("run").invoke(null) as List<*>

        assertEquals(
            BrowserProfile(
                browserPackage = "default-package",
                versionName = "default-version",
                versionCode = 1,
                majorVersion = 31,
                supportedApis = setOf("default-supported"),
                wrapperContract = WrapperContract.complete(),
                sourceApkSha256 = "default-source",
            ),
            defaulted,
        )
        assertEquals(
            listOf(
                "direct-package",
                "direct-version",
                2L,
                32,
                setOf("direct-supported"),
                setOf("direct-detected"),
                WrapperContract(false, true, false, true),
                "direct-source",
                "direct-installation",
                "direct-signer",
                listOf("direct-difference"),
                setOf("direct-feature"),
                setOf("direct-unavailable"),
            ),
            components,
        )
        assertEquals(
            BrowserProfile(
                "copy-package",
                "copy-version",
                3,
                33,
                setOf("copy-supported"),
                setOf("copy-detected"),
                WrapperContract(true, false, true, false),
                "copy-source",
                "copy-installation",
                "copy-signer",
                listOf("copy-difference"),
                setOf("copy-feature"),
                setOf("copy-unavailable"),
            ),
            directCopy,
        )
        assertEquals(
            BrowserProfile(
                "direct-package",
                "default-copy-version",
                2,
                32,
                setOf("direct-supported"),
                setOf("direct-detected"),
                WrapperContract(false, true, false, true),
                "direct-source",
                "direct-installation",
                "direct-signer",
                listOf("direct-difference"),
                setOf("direct-feature"),
                setOf("direct-unavailable"),
            ),
            defaultCopy,
        )
        listOf(defaulted, directCopy, defaultCopy).forEach {
            assertEquals(emptyList(), (it as BrowserProfile).apiCapabilities)
        }
    }

    @Test
    fun `head는 base BrowserProfile constructor component copy descriptor를 보존한다`() {
        val legacyParameters = arrayOf(
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Set::class.java,
            Set::class.java,
            WrapperContract::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            Set::class.java,
            Set::class.java,
        )
        val legacyConstructor =
            BrowserProfile::class.java.getDeclaredConstructor(*legacyParameters)
        assertFalse(legacyConstructor.isSynthetic)
        val defaultConstructorMarker =
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val defaultConstructor = BrowserProfile::class.java.getDeclaredConstructor(
            *legacyParameters,
            Int::class.javaPrimitiveType,
            defaultConstructorMarker,
        )
        assertTrue(defaultConstructor.isSynthetic)
        val newParameters = legacyParameters + List::class.java
        val expectedConstructorParameters = setOf(
            legacyParameters.map { requireNotNull(it).name },
            (legacyParameters + Int::class.javaPrimitiveType + defaultConstructorMarker)
                .map { requireNotNull(it).name },
            newParameters.map { requireNotNull(it).name },
        )
        assertEquals(
            expectedConstructorParameters,
            BrowserProfile::class.java.declaredConstructors
                .map { constructor -> constructor.parameterTypes.map { it.name } }
                .toSet(),
        )
        assertFalse(
            BrowserProfile::class.java.declaredConstructors.any {
                it.parameterCount == 0
            },
        )

        (1..13).forEach { index ->
            BrowserProfile::class.java.getDeclaredMethod("component$index")
        }
        val legacyCopy =
            BrowserProfile::class.java.getDeclaredMethod("copy", *legacyParameters)
        assertFalse(legacyCopy.isSynthetic)
        val copyDefault = BrowserProfile::class.java.getDeclaredMethod(
            "copy\$default",
            BrowserProfile::class.java,
            *legacyParameters,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        assertTrue(Modifier.isStatic(copyDefault.modifiers))
    }

    @Test
    fun `Java source는 legacy 13 parameter constructor와 copy를 호출한다`() {
        val profile = BrowserProfileJavaAbiClient.run()

        assertEquals("java-copy-package", profile.browserPackage)
        assertEquals("java-copy-version", profile.versionName)
        assertEquals(14L, profile.versionCode)
        assertEquals(emptyList(), profile.apiCapabilities)
    }

    @Test
    fun `Kotlin exact 13 arity callable reference는 constructor와 copy를 호출한다`() {
        val constructor: (
            String,
            String,
            Long,
            Int,
            Set<String>,
            Set<String>,
            WrapperContract,
            String,
            String,
            String,
            List<String>,
            Set<String>,
            Set<String>,
        ) -> BrowserProfile = ::BrowserProfile
        val copy: (
            BrowserProfile,
            String,
            String,
            Long,
            Int,
            Set<String>,
            Set<String>,
            WrapperContract,
            String,
            String,
            String,
            List<String>,
            Set<String>,
            Set<String>,
        ) -> BrowserProfile = BrowserProfile::copy
        val profile = constructor(
            "kotlin-package",
            "kotlin-version",
            13,
            30,
            setOf("kotlin-supported"),
            setOf("kotlin-detected"),
            WrapperContract.complete(),
            "kotlin-source",
            "kotlin-installation",
            "kotlin-signer",
            listOf("kotlin-difference"),
            setOf("kotlin-feature"),
            setOf("kotlin-unavailable"),
        )

        val copied = copy(
            profile,
            "kotlin-copy-package",
            profile.versionName,
            profile.versionCode,
            profile.majorVersion,
            profile.supportedApis,
            profile.detectedApis,
            profile.wrapperContract,
            profile.sourceApkSha256,
            profile.installationApkSetSha256,
            profile.sourceApkSignerSha256,
            profile.profileDifferences,
            profile.supportedFeatures,
            profile.unavailableFeatures,
        )

        assertEquals("kotlin-copy-package", copied.browserPackage)
        assertEquals(emptyList(), copied.apiCapabilities)
    }

    @Test
    fun `새 capability copy는 immutable value semantics를 유지한다`() {
        val evidence = ApiCapabilityEvidence(
            key = ApiCapabilityKey(
                "tabs",
                "query",
                ApiExecutionContext.SERVICE_WORKER,
            ),
            status = ApiCapabilityStatus.VERIFIED,
            permissions = setOf("tabs"),
            manifestKeys = setOf("permissions"),
            detail = "성공",
            probeId = "tabs-query",
            probeCatalogSha256 = "a".repeat(64),
        )
        val profile = profile().copy(apiCapabilities = listOf(evidence))
        val constructed = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = 1_300_261_502,
            majorVersion = 30,
            supportedApis = setOf("tabs"),
            detectedApis = setOf("tabs"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = "source",
            installationApkSetSha256 = "installation",
            sourceApkSignerSha256 = "signer",
            profileDifferences = emptyList(),
            supportedFeatures = emptySet(),
            unavailableFeatures = emptySet(),
            apiCapabilities = listOf(evidence),
        )

        val changed = profile.copy(versionName = "30.0.2.62")

        assertEquals(listOf(evidence), constructed.apiCapabilities)
        assertEquals(profile.apiCapabilities, changed.apiCapabilities)
        assertEquals(profile, BrowserProfileJson.decode(BrowserProfileJson.encode(profile)))
        assertEquals(profile.hashCode(), profile.copy().hashCode())
    }

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("tabs"),
        wrapperContract = WrapperContract.complete(),
    )

    private class OldBrowserProfileClientLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(bytes: ByteArray): Class<*> = defineClass(null, bytes, 0, bytes.size)
    }
}
