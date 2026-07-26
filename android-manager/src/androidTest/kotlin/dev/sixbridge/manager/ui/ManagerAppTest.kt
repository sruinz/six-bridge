package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.ManagerAppContent
import dev.sixbridge.manager.app.ReviewBundle
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun home_exposes_add_ons_file_report_and_remove_actions() {
        val actions = mutableListOf<ManagerAction>()
        var addFileRequests = 0
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(installed = listOf(installedItem())),
                onAction = { actions.add(it) },
                onAddSix = { addFileRequests += 1 },
            )
        }

        listOf(
            "home:open-addons",
            "extension:$PACKAGE:report",
            "extension:$PACKAGE:remove",
            "home:add-six",
        ).forEach { tag ->
            composeRule.onNodeWithTag("home:scroll")
                .performScrollToNode(hasTestTag(tag))
            composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
        }

        assertEquals(
            listOf(
                ManagerAction.OpenSamsungAddOns,
                ManagerAction.OpenReport(PACKAGE),
                ManagerAction.Remove(PACKAGE),
            ),
            actions,
        )
        assertEquals(1, addFileRequests)
    }

    @Test
    fun review_exposes_required_report_sections() {
        composeRule.setContent {
            ManagerAppContent(
                state = reviewState(ContractCompatibilityStatus.COMPATIBLE),
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            "review:status",
            "review:permissions",
            "review:issues",
            "review:adapters",
            "review:signer",
            "review:install",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
        composeRule.onNodeWithTag("review:install").assertIsEnabled()
    }

    @Test
    fun blocked_and_unknown_reviews_cannot_be_installed() {
        var state by mutableStateOf(reviewState(ContractCompatibilityStatus.BLOCKED))
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            ContractCompatibilityStatus.BLOCKED,
            ContractCompatibilityStatus.UNKNOWN,
        ).forEach { status ->
            state = reviewState(status)
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("review:install").assertIsNotEnabled()
        }
    }

    @Test
    fun partial_review_requires_explicit_confirmation_before_install() {
        var state by mutableStateOf(reviewState(ContractCompatibilityStatus.PARTIAL))
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = { action ->
                    if (action == ManagerAction.ConfirmPartial) {
                        val ready = state.importState as ImportUiState.ReviewReady
                        state = state.copy(
                            importState = ready.copy(partialConfirmed = true),
                        )
                    }
                },
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("review:scroll")
            .performScrollToNode(hasTestTag("review:partial-confirm"))
        composeRule.onNodeWithTag("review:partial-confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("review:install").assertIsNotEnabled()
        composeRule.onNodeWithTag("review:partial-confirm").performClick()
        composeRule.onNodeWithTag("review:install").assertIsEnabled()
    }

    @Test
    fun report_route_displays_the_installed_extension_report() {
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    route = ManagerRoute.Report(PACKAGE),
                    installed = listOf(installedItem()),
                ),
                onAction = {},
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("report:screen").assertIsDisplayed()
        composeRule.onNodeWithTag("report:status").assertIsDisplayed()
        composeRule.onNodeWithTag("report:scroll")
            .performScrollToNode(hasTestTag("report:signer"))
        composeRule.onNodeWithTag("report:signer").assertIsDisplayed()
    }

    @Test
    fun narrow_review_can_scroll_to_install_action() {
        composeRule.setContent {
            ManagerAppContent(
                state = reviewState(ContractCompatibilityStatus.COMPATIBLE),
                onAction = {},
                onAddSix = {},
                modifier = Modifier.width(320.dp).height(280.dp),
            )
        }

        composeRule.onNodeWithTag("review:scroll")
            .performScrollToNode(hasTestTag("review:install"))
        composeRule.onNodeWithTag("review:install").assertIsDisplayed()
    }

    private fun reviewState(status: ContractCompatibilityStatus): ManagerUiState {
        val review = ReviewBundle(
            token = "11111111-1111-1111-1111-111111111111",
            packageName = PACKAGE,
            extensionId = EXTENSION_ID,
            name = "테스트 확장",
            version = "1.2.3",
            status = status,
            declaredPermissions = listOf("storage"),
            issues = listOf(ContractIssue("API", "일부 API가 제한됩니다.", status == ContractCompatibilityStatus.BLOCKED)),
            adapterDecisions = listOf(
                ContractAdapterDecision(
                    id = "tabs",
                    version = 1,
                    risk = "medium",
                    disposition = "adapted",
                    applied = true,
                    evidence = emptyList(),
                    changes = listOf("tabs API 변환"),
                    remainingLimitations = listOf("백그라운드 동작 제한"),
                ),
            ),
            signerSha256 = "a".repeat(64),
            wrapperVersionCode = 7,
        )
        return ManagerUiState(
            route = ManagerRoute.Review(review.token),
            importState = ImportUiState.ReviewReady(review, partialConfirmed = false),
        )
    }

    private fun installedItem() = ManagedExtensionItem(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "테스트 확장",
        installedVersionCode = 7,
        compatibilityStatus = "COMPATIBLE",
        signerSha256 = "a".repeat(64),
        report = null,
    )

    private companion object {
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXTENSION_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
