package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
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
    fun `external Java cannot construct or implement a selected capability`() {
        listOf(
            "ConstructSelectedAdapter.java",
            "ImplementSelectedAdapter.java",
            "ConstructPlannerImplementation.java",
            "ConstructAdapterExecutionContext.java",
            "ActivateTransactionContext.java",
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
            } else if (
                fixture == "ConstructPlannerImplementation.java" ||
                fixture == "ActivateTransactionContext.java" ||
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
    fun `execution context implementation is private and has no callable factory`() {
        val javap = Path.of(System.getProperty("java.home"), "bin", "javap")
        val process = ProcessBuilder(
            javap.toString(),
            "-p",
            "-v",
            "-classpath",
            System.getProperty("java.class.path"),
            "dev.sixbridge.core.conversion.ConversionWorkspaceTransaction" +
                "\$TransactionContext",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.waitFor(), output)
        assertTrue(
            "private dev.sixbridge.core.conversion." +
                "ConversionWorkspaceTransaction\$TransactionContext();" in output,
            output,
        )
        assertTrue(
            "java/util/concurrent/atomic/AtomicBoolean" in output,
            output,
        )
        val activateBlock = output
            .substringAfter("public final void activate")
            .substringBefore("public final void deactivate")
        val deactivateBlock = output
            .substringAfter("public final void deactivate")
            .substringBefore("private final")
        assertTrue("ACC_SYNTHETIC" in activateBlock, activateBlock)
        assertTrue("ACC_SYNTHETIC" in deactivateBlock, deactivateBlock)
        assertFalse("Companion" in output, output)
        assertFalse("create(" in output, output)
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
}
