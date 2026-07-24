package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

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
    fun `mobile adapters accept only planner selected capabilities`() {
        val applyParameter = MobileAdapter::class.java.methods
            .single { it.name == "apply" }
            .parameterTypes
            .last()

        assertEquals(SelectedAdapter::class.java, applyParameter)
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
