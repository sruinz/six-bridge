package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionPlannerTest {
    @Test
    fun `선택적 adapter는 승인한 경우에만 plan에 포함한다`() {
        val report = reportWith(
            decision("popup-native-autofill-guard", AdapterDisposition.OPTIONAL_APPROVAL),
        )

        val original = ConversionPlanner().plan(report, emptySet())
        val protected = ConversionPlanner().plan(
            report,
            setOf("popup-native-autofill-guard"),
        )

        assertTrue(original.selectedAdapters.isEmpty())
        assertEquals(
            listOf("popup-native-autofill-guard"),
            protected.selectedAdapters.map(SelectedAdapter::id),
        )
    }

    @Test
    fun `필수 승인은 선택적 승인 추가 후에도 누락할 수 없다`() {
        val report = reportWith(
            decision("action-popup-entry", AdapterDisposition.REQUIRES_APPROVAL),
            decision("popup-native-autofill-guard", AdapterDisposition.OPTIONAL_APPROVAL),
        )

        val error = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(report, setOf("popup-native-autofill-guard"))
        }

        assertEquals(ConversionPlanningFailure.MISSING_REQUIRED_APPROVAL, error.failure)
    }

    @Test
    fun `selects automatic and explicitly approved adapters in registry order`() {
        val report = reportWith(
            decision("storage-fallback", AdapterDisposition.AUTOMATIC),
            decision("action-popup-entry", AdapterDisposition.REQUIRES_APPROVAL),
            decision("desktop-selection-event-bridge", AdapterDisposition.AUTOMATIC),
        )

        val plan = ConversionPlanner().plan(
            report,
            approvedAdapterIds = setOf("action-popup-entry"),
        )

        assertEquals(
            listOf(
                "action-popup-entry",
                "desktop-selection-event-bridge",
                "storage-fallback",
            ),
            plan.selectedAdapters.map { it.id },
        )
        val selected: List<SelectedAdapter> = plan.selectedAdapters
        assertFalse(selected.any { it.applied })
    }

    @Test
    fun `workspace transform exposes plan input and no callback or path`() {
        val transform = ConversionWorkspace::class.java.methods
            .single { it.name == "transform" }

        assertEquals(
            listOf(
                ExtensionSnapshot::class.java,
                dev.sixbridge.core.profile.BrowserProfile::class.java,
                ConversionPlan::class.java,
            ),
            transform.parameterTypes.toList(),
        )
        assertFalse(
            transform.parameterTypes.any {
                java.nio.file.Path::class.java.isAssignableFrom(it) ||
                    it.name.startsWith("kotlin.jvm.functions.Function")
            },
        )
    }

    @Test
    fun `workspace exposes no JVM test seam transaction or registry factory`() {
        val exposed = ConversionWorkspace::class.java.methods
            .filter {
                it.declaringClass == ConversionWorkspace::class.java &&
                    !it.isSynthetic
            }

        assertEquals(
            setOf("snapshot", "transform", "close"),
            exposed.mapTo(sortedSetOf()) { it.name },
        )
        assertFailsWith<ClassNotFoundException> {
            Class.forName(
                "dev.sixbridge.core.conversion.ConversionWorkspaceTransaction",
            )
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName(
                "dev.sixbridge.core.conversion.MobileAdapterRegistry",
            )
        }
    }

    @Test
    fun `plan is sealed immutable capability without data class copy surface`() {
        val report = reportWith(
            decision("desktop-selection-event-bridge", AdapterDisposition.AUTOMATIC),
        )
        val plan = ConversionPlanner().plan(report, emptySet())

        assertTrue(ConversionPlan::class.java.isInterface)
        assertEquals(null, plan.forcedCompatibility)
        assertFalse(
            ConversionPlan::class.java.methods.any {
                it.name == "copy" || it.name.startsWith("component")
            },
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.selectedAdapters as MutableList<SelectedAdapter>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.report.adapterDecisions as MutableList<AdapterDecision>)
                .clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (plan.report.declaredPermissions as MutableSet<String>)
                .add("tabs")
        }
    }

    @Test
    fun `analyzer seals canonical source and browser profile digests`() {
        val source = Files.createTempDirectory("six-plan-proof-")
        source.resolve("manifest.json").toFile().writeText(
            """{"manifest_version":3,"name":"Proof","version":"1"}""",
        )

        val report = MobilePatternAnalyzer().analyze(source, profile)

        assertEquals(
            sha256(
                ExtensionZip.createBytes(
                    source,
                    includeProjectMarker = true,
                ),
            ),
            report.sourceSha256,
        )
        assertEquals(profile.canonicalSha256(), report.browserProfileSha256)
    }

    @Test
    fun `external Java cannot construct or implement a selected capability`() {
        listOf(
            "ConstructSelectedAdapter.java",
            "ImplementSelectedAdapter.java",
            "ConstructPlannerImplementation.java",
            "ConstructConversionPlan.java",
            "ImplementConversionPlan.java",
            "ConstructAdapterExecutionContext.java",
            "ConstructExtensionSnapshot.java",
        ).forEach { fixture ->
            val source = requireNotNull(
                javaClass.classLoader.getResource(
                    "dev/sixbridge/core/conversion/$fixture",
                ),
            )
            val output = ByteArrayOutputStream()
            val destination = Files.createTempDirectory("six-selected-capability-javac")
            val exit = ToolProvider.getSystemJavaCompiler().run(
                null,
                output,
                output,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                destination.toString(),
                Path.of(source.toURI()).toString(),
            )

            assertTrue(exit != 0, "$fixture unexpectedly compiled")
            val diagnostics = output.toString()
            if (fixture == "ConstructSelectedAdapter.java") {
                assertTrue(
                    "abstract" in diagnostics && "instantiated" in diagnostics,
                    diagnostics,
                )
            } else if (fixture == "ConstructConversionPlan.java") {
                assertTrue(
                    "abstract" in diagnostics &&
                        "instantiated" in diagnostics,
                    diagnostics,
                )
            } else if (
                fixture == "ConstructPlannerImplementation.java" ||
                fixture == "ConstructExtensionSnapshot.java"
            ) {
                assertTrue("private access" in diagnostics, diagnostics)
            } else {
                assertTrue(
                    "sealed" in diagnostics && "not allowed" in diagnostics,
                    diagnostics,
                )
            }
        }
    }

    @Test
    fun `selected capability exposes no public raw decision constructor in JVM bytecode`() {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap")
        val process = ProcessBuilder(
            javap.toString(),
            "-p",
            "-v",
            "-classpath",
            System.getProperty("java.class.path"),
            SelectedAdapter::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor(), output)
        assertTrue(
            "interface dev.sixbridge.core.conversion.SelectedAdapter" in output,
            output,
        )
        assertFalse("SelectedAdapter(" in output, output)
        assertFalse("AdapterDecision" in output, output)
    }

    @Test
    fun `workspace bytecode has only private nested execution implementations`() {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap")
        val process = ProcessBuilder(
            javap.toString(),
            "-p",
            "-v",
            "-classpath",
            System.getProperty("java.class.path"),
            ConversionWorkspace::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor(), output)
        assertFalse("transformForTesting" in output, output)
        assertFalse("forTesting" in output, output)
        assertFalse("MobileAdapterRegistry" in output, output)
        ConversionWorkspace::class.java.declaredClasses
            .filter {
                it.simpleName in setOf(
                    "Transaction",
                    "TransactionContext",
                    "AdapterRegistry",
                    "Ownership",
                )
            }
            .forEach { nested ->
                assertTrue(
                    java.lang.reflect.Modifier.isPrivate(nested.modifiers),
                    "${nested.name} is not private",
                )
            }
    }

    @Test
    fun `reports missing required approvals separately`() {
        val report = reportWith(
            decision("action-popup-entry", AdapterDisposition.REQUIRES_APPROVAL),
        )

        val error = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(report, approvedAdapterIds = emptySet())
        }

        assertEquals(ConversionPlanningFailure.MISSING_REQUIRED_APPROVAL, error.failure)
        assertEquals(setOf("action-popup-entry"), error.adapterIds)
    }

    @Test
    fun `reports unknown approval ids separately`() {
        val error = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(
                reportWith(),
                approvedAdapterIds = setOf("extension-specific-adapter"),
            )
        }

        assertEquals(ConversionPlanningFailure.UNKNOWN_APPROVAL, error.failure)
        assertEquals(setOf("extension-specific-adapter"), error.adapterIds)
    }

    @Test
    fun `reports known but undetected approval ids separately`() {
        val error = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(
                reportWith(
                    decision("desktop-selection-event-bridge", AdapterDisposition.AUTOMATIC),
                ),
                approvedAdapterIds = setOf("action-popup-entry"),
            )
        }

        assertEquals(ConversionPlanningFailure.UNDETECTED_APPROVAL, error.failure)
        assertEquals(setOf("action-popup-entry"), error.adapterIds)
    }

    @Test
    fun `blocked는 거부하고 승인 없는 UNKNOWN은 강제 승인 오류로 거부한다`() {
        mapOf(
            CompatibilityStatus.BLOCKED to
                ConversionPlanningFailure.INELIGIBLE_COMPATIBILITY_STATUS,
            CompatibilityStatus.UNKNOWN to
                ConversionPlanningFailure.INVALID_COMPATIBILITY_AUTHORIZATION,
        ).forEach { (status, expectedFailure) ->
            val error = assertFailsWith<ConversionPlanningException> {
                ConversionPlanner().plan(
                    reportWith(status = status),
                    approvedAdapterIds = emptySet(),
                )
            }

            assertEquals(
                expectedFailure,
                error.failure,
            )
            assertEquals(emptySet(), error.adapterIds)
        }
    }

    @Test
    fun `명시 승인한 eligible UNKNOWN은 원상태 provenance로 계획한다`() {
        val plan = ConversionPlanner().plan(
            dynamicUnknownReport(),
            emptySet(),
            ConversionAuthorization.forceUnknownApiUsage(),
        )

        assertEquals(CompatibilityStatus.UNKNOWN, plan.report.status)
        assertEquals(
            ForcedCompatibilityProvenance(
                CompatibilityForceScope.UNKNOWN_API_USAGE,
                CompatibilityStatus.UNKNOWN,
                listOf("api_usage_dynamic"),
            ),
            plan.forcedCompatibility,
        )
        assertFalse(
            plan.proofSha256 == ConversionPlanProof.digest(
                plan.report,
                plan.selectedAdapters,
            ),
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(plan.forcedCompatibility).issueCodes as MutableList<String>)
                .clear()
        }
    }

    @Test
    fun `승인 없는 UNKNOWN과 정상 report의 불필요한 강제 승인을 거부한다`() {
        val unknownError = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(dynamicUnknownReport(), emptySet())
        }
        val compatibleError = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(
                reportWith(),
                emptySet(),
                ConversionAuthorization.forceUnknownApiUsage(),
            )
        }

        assertEquals(
            ConversionPlanningFailure.INVALID_COMPATIBILITY_AUTHORIZATION,
            unknownError.failure,
        )
        assertEquals(
            ConversionPlanningFailure.INVALID_COMPATIBILITY_AUTHORIZATION,
            compatibleError.failure,
        )
    }

    @Test
    fun `plan proof is injective for collection boundaries`() {
        val twoValues = reportWith().copy(
            declaredPermissions = setOf("a", "b"),
        )
        val embeddedSeparator = reportWith().copy(
            declaredPermissions = setOf("a\u0000b"),
        )
        val emptyReport = reportWith().copy(
            declaredPermissions = emptySet(),
        )
        val oneEmptyValue = reportWith().copy(
            declaredPermissions = setOf(""),
        )

        assertFalse(
            ConversionPlanner().plan(twoValues, emptySet()).proofSha256 ==
                ConversionPlanner().plan(
                    embeddedSeparator,
                    emptySet(),
                ).proofSha256,
        )
        assertFalse(
            ConversionPlanner().plan(emptyReport, emptySet()).proofSha256 ==
                ConversionPlanner().plan(
                    oneEmptyValue,
                    emptySet(),
                ).proofSha256,
        )
    }

    @Test
    fun `does not issue an empty plan for unavailable decisions`() {
        val error = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(
                reportWith(
                    decision(
                        "storage-fallback",
                        AdapterDisposition.UNAVAILABLE,
                    ),
                ),
                emptySet(),
            )
        }

        assertEquals(
            ConversionPlanningFailure.NO_APPLICABLE_ADAPTER,
            error.failure,
        )
    }

    private fun reportWith(
        vararg decisions: AdapterDecision,
        status: CompatibilityStatus = CompatibilityStatus.COMPATIBLE,
    ) = CompatibilityReport(
        status = status,
        manifestVersion = 3,
        extensionName = "Synthetic",
        extensionVersion = "1",
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = emptyList(),
        adapterDecisions = decisions.toList(),
        sourceSha256 = "a".repeat(64),
        browserProfileSha256 = "b".repeat(64),
    )

    private fun dynamicUnknownReport() = reportWith(
        status = CompatibilityStatus.UNKNOWN,
    ).copy(
        issues = listOf(
            CompatibilityIssue("api_usage_dynamic", "dynamic", true),
        ),
    )

    private fun decision(
        id: String,
        disposition: AdapterDisposition,
    ) = AdapterDecision(
        id = id,
        version = if (id == "action-popup-entry") 2 else 1,
        risk = if (disposition == AdapterDisposition.REQUIRES_APPROVAL) {
            AdapterRisk.BEHAVIOR_CHANGE
        } else {
            AdapterRisk.SAFE
        },
        disposition = disposition,
        applied = false,
        evidence = listOf(SourceEvidence("content.js", 1, "test evidence")),
        changes = emptyList(),
        remainingLimitations = emptyList(),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = 1_300_261_502,
            majorVersion = 30,
            supportedApis = setOf("runtime"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
