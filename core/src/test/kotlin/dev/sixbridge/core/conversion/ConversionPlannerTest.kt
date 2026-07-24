package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
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
    fun `does not change unknown compatibility to compatible`() {
        val report = reportWith(
            decision("desktop-selection-event-bridge", AdapterDisposition.AUTOMATIC),
            status = CompatibilityStatus.UNKNOWN,
        )

        val plan = ConversionPlanner().plan(report, approvedAdapterIds = emptySet())

        assertEquals(CompatibilityStatus.UNKNOWN, plan.report.status)
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

    private fun decision(
        id: String,
        disposition: AdapterDisposition,
    ) = AdapterDecision(
        id = id,
        version = 1,
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
