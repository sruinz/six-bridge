package dev.sixbridge.manager.ui

import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.ManagerAppContent
import dev.sixbridge.manager.app.ReviewBundle
import java.io.ByteArrayOutputStream
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
    fun home_displays_decoded_extension_icon() {
        setHome(iconPng = pngBytes())

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertIsDisplayed()
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertDoesNotExist()
    }

    @Test
    fun home_uses_name_initial_when_icon_is_missing() {
        setHome(iconPng = null)

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()
        composeRule.onNodeWithText("테").assertIsDisplayed()
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
        composeRule.onNodeWithText("storage", substring = true).assertExists()
        composeRule.onNodeWithText(
            "API_LIMIT: 일부 API가 제한됩니다.",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("tabs v1 · requires-approval", substring = true).assertExists()
        composeRule.onNodeWithText(
            "이 보고서는 호환성·식별자 검증 결과이며 악성 코드 검사나 보안 보증을 의미하지 않습니다.",
        ).assertExists()
    }

    @Test
    fun missing_and_unreadable_reports_show_unknown_without_verified_empty_sections() {
        var item by mutableStateOf(
            installedItem(
                report = null,
                reportAvailability = ReportAvailability.MISSING,
            ),
        )
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    route = ManagerRoute.Report(item.packageName),
                    installed = listOf(item),
                ),
                onAction = {},
                onAddSix = {},
            )
        }

        composeRule.onNodeWithText("보고서 없음").assertExists()
        composeRule.onNodeWithText("알 수 없음").assertExists()
        composeRule.onNodeWithText("없음").assertDoesNotExist()

        item = installedItem(
            report = null,
            reportAvailability = ReportAvailability.UNREADABLE,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText("보고서를 읽을 수 없음").assertExists()
        composeRule.onNodeWithText("알 수 없음").assertExists()
        composeRule.onNodeWithText("없음").assertDoesNotExist()
    }

    @Test
    fun retry_card_dispatches_only_view_model_owned_retry_action() {
        val actions = mutableListOf<ManagerAction>()
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    message = UserMessage(
                        UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE,
                        "Add-ons를 열 수 없습니다.",
                        RecoveryAction.RETRY,
                    ),
                ),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        composeRule.onNodeWithText("다시 확인").performClick()

        assertEquals(listOf(ManagerAction.RetryRecovery), actions)
    }

    @Test
    fun review_and_report_back_dispatch_route_owned_actions() {
        var state by mutableStateOf(reviewState(ContractCompatibilityStatus.COMPATIBLE))
        val actions = mutableListOf<ManagerAction>()
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = actions::add,
                onAddSix = {},
            )
        }

        pressBack()
        composeRule.waitForIdle()
        assertEquals(listOf(ManagerAction.CancelReview), actions)

        actions.clear()
        state = ManagerUiState(
            route = ManagerRoute.Report(PACKAGE),
            installed = listOf(installedItem()),
        )
        composeRule.waitForIdle()
        pressBack()
        composeRule.waitForIdle()
        assertEquals(listOf(ManagerAction.ReturnHome), actions)
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

    private fun installedItem(
        iconPng: ByteArray? = null,
        report: ContractCompatibilityReport? = compatibilityReport(),
        reportAvailability: ReportAvailability = ReportAvailability.AVAILABLE,
    ) = ManagedExtensionItem(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "테스트 확장",
        iconPng = iconPng,
        installedVersionCode = 7,
        compatibilityStatus = report?.status ?: ContractCompatibilityStatus.UNKNOWN,
        signerSha256 = "a".repeat(64),
        report = report,
        reportAvailability = reportAvailability,
    )

    private fun compatibilityReport() = ContractCompatibilityReport(
        schemaVersion = 3,
        status = ContractCompatibilityStatus.PARTIAL,
        manifestVersion = 3,
        extensionName = "테스트 확장",
        extensionVersion = "1.2.3",
        manifestKeyPresent = false,
        manifestKeyExtensionId = null,
        declaredPermissions = listOf("storage"),
        usedApis = listOf("tabs"),
        unsupportedApis = emptyList(),
        issues = listOf(ContractIssue("API_LIMIT", "일부 API가 제한됩니다.", false)),
        conversionState = "converted",
        adapterDecisions = listOf(
            ContractAdapterDecision(
                id = "tabs",
                version = 1,
                risk = "behavior-change",
                disposition = "requires-approval",
                applied = true,
                evidence = emptyList(),
                changes = listOf("tabs API 변환"),
                remainingLimitations = emptyList(),
            ),
        ),
        originalExtensionId = EXTENSION_ID,
        convertedExtensionId = EXTENSION_ID,
        sourceSha256 = "b".repeat(64),
        browserProfileSha256 = "c".repeat(64),
    )

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }.compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    private fun setHome(iconPng: ByteArray?) {
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    installed = listOf(installedItem(iconPng = iconPng)),
                ),
                onAction = {},
                onAddSix = {},
            )
        }
    }

    private companion object {
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXTENSION_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
