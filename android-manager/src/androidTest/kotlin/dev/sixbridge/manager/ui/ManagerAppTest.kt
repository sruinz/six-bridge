package dev.sixbridge.manager.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.ManagerAppContent
import dev.sixbridge.manager.GENERATED_BUNDLE_MIME_TYPE
import dev.sixbridge.manager.SixBridgeManagerApp
import dev.sixbridge.manager.generatedBundleSendIntent
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.ExistingConversionTarget
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallCleanupResult
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.InstallTerminalOwnerResult
import dev.sixbridge.manager.install.SamsungInternetSupportStatus
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                state = ManagerUiState(
                    installed = listOf(installedItem()),
                    startupRecoveryState = StartupRecoveryUiState.READY,
                ),
                onAction = { actions.add(it) },
                onAddSix = { addFileRequests += 1 },
            )
        }

        listOf(
            "home:convert-on-device",
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
                ManagerAction.OpenConversionInput,
                ManagerAction.OpenSamsungAddOns,
                ManagerAction.OpenReport(PACKAGE),
                ManagerAction.Remove(PACKAGE),
            ),
            actions,
        )
        assertEquals(1, addFileRequests)
    }

    @Test
    fun startup_gate_disables_mutations_but_keeps_report_readable() {
        var startupState by mutableStateOf(StartupRecoveryUiState.INITIALIZING)
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    installed = listOf(installedItem()),
                    startupRecoveryState = startupState,
                ),
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            StartupRecoveryUiState.INITIALIZING,
            StartupRecoveryUiState.RECOVERY_BLOCKED,
        ).forEach { expectedState ->
            composeRule.runOnIdle { startupState = expectedState }
            listOf(
                "home:convert-on-device",
                "home:add-six",
                "home:open-addons",
                "extension:$PACKAGE:remove",
            ).forEach { tag ->
                composeRule.onNodeWithTag("home:scroll").performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsNotEnabled()
            }
            composeRule.onNodeWithTag("extension:$PACKAGE:report").assertIsEnabled()
        }
    }

    @Test
    fun conversion_input_exposes_url_file_and_analyze_actions() {
        val actions = mutableListOf<ManagerAction>()
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(route = ManagerRoute.ConversionInput),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        listOf(
            "conversion:url",
            "conversion:pick-file",
            "conversion:analyze",
            "conversion:cancel",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
        composeRule.onNodeWithTag("conversion:pick-file").performClick()
        composeRule.onNodeWithTag("conversion:cancel").performClick()

        assertEquals(
            listOf(
                ManagerAction.OpenConversionFilePicker,
                ManagerAction.CancelConversion,
            ),
            actions,
        )
    }

    @Test
    fun conversion_analysis_requires_adapter_and_partial_confirmation() {
        var state by mutableStateOf(conversionAnalysisState())
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = { action ->
                    val ready = state.conversionState as ConversionUiState.AnalysisReady
                    state = when (action) {
                        is ManagerAction.ToggleConversionAdapter ->
                            state.copy(conversionState = ready.toggleAdapter(action.id))
                        ManagerAction.ConfirmConversionPartial ->
                            state.copy(conversionState = ready.copy(partialConfirmed = true))
                        else -> state
                    }
                },
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("conversion:adapter:action-popup-entry")
            .assertIsNotSelected()
        composeRule.onNodeWithTag("conversion:build").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversion:adapter:action-popup-entry").performClick()
        composeRule.onNodeWithTag("conversion:confirm-partial").performClick()
        composeRule.onNodeWithTag("conversion:build").assertIsEnabled()
    }

    @Test
    fun identity_target_entire_row_is_merged_radio_and_scrolls_at_large_font() {
        val identity = "a".repeat(64)
        val extensionId = "b".repeat(32)
        val packageName = "dev.sixbridge.ext.$extensionId"
        val signer = "c".repeat(64)
        val target = ExistingConversionTarget(
            identitySha256 = identity,
            displayName = "기존 변환 대상",
            extensionId = extensionId,
            packageName = packageName,
            wrapperSignerSha256 = signer,
        )
        val initial = conversionAnalysisState()
        val ready = initial.conversionState as ConversionUiState.AnalysisReady
        val actions = mutableListOf<ManagerAction>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                ManagerAppContent(
                    state = initial.copy(
                        conversionState = ready.copy(
                            identityRequirement =
                                IdentityRequirement.UserSelectionRequired(listOf(target)),
                            identitySelection =
                                InstallationIdentitySelection.UseExisting(identity),
                        ),
                    ),
                    onAction = actions::add,
                    onAddSix = {},
                )
            }
        }

        val tag = "conversion:identity-existing:$identity"
        composeRule.onNodeWithTag("conversion:analysis-screen")
            .performScrollToNode(hasTestTag(tag))
        val row = composeRule.onNodeWithTag(tag)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertIsSelected()
        val semantics = row.fetchSemanticsNode().config
        assertEquals(Role.RadioButton, semantics[SemanticsProperties.Role])
        assertEquals(
            listOf(
                "기존 변환 대상, Extension ID: $extensionId, Package: $packageName, Signer SHA-256: $signer",
            ),
            semantics[SemanticsProperties.ContentDescription],
        )
        row.performClick()

        assertEquals(
            listOf(ManagerAction.SelectExistingConversionIdentity(identity)),
            actions,
        )
        composeRule.onNodeWithText("Signer SHA-256: $signer", substring = true)
            .assertExists()
    }

    @Test
    fun conversion_result_exposes_install_save_share_done_and_cancel() {
        composeRule.setContent {
            ManagerAppContent(
                state = conversionResultState(),
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            "conversion:install",
            "conversion:save",
            "conversion:share",
            "conversion:done",
            "conversion:cancel",
        ).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertExists()
        }
    }

    @Test
    fun generated_cleanup_pending_disables_all_result_actions() {
        var state by mutableStateOf(conversionResultState())
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = {},
                onAddSix = {},
            )
        }
        val ready = state.conversionState as ConversionUiState.ResultReady

        listOf(
            ready.copy(cleanupPending = true),
            ready.copy(cleanupPending = true, cleanupInProgress = true),
        ).forEach { cleanupState ->
            composeRule.runOnIdle {
                state = state.copy(conversionState = cleanupState)
            }
            composeRule.onNodeWithTag("conversion:install").assertIsNotEnabled()
            composeRule.onNodeWithTag("conversion:done").assertIsNotEnabled()
            composeRule.onNodeWithTag("conversion:cancel").assertIsNotEnabled()
            composeRule.onNodeWithTag("conversion:save").assertIsNotEnabled()
            composeRule.onNodeWithTag("conversion:share").assertIsNotEnabled()
        }
    }

    @Test
    fun generated_result_typed_recovery_dispatches_exact_action_by_message_kind() {
        val actions = mutableListOf<ManagerAction>()
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        var message by mutableStateOf(
            UserMessage(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                "정리를 완료하지 못했습니다.",
                RecoveryAction.RETRY,
            ),
        )
        composeRule.setContent {
            ManagerAppContent(
                state = initial.copy(
                    conversionState = ready.copy(cleanupPending = true),
                    message = message,
                ),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        listOf(
            UserMessageKind.CONVERSION_CLEANUP_FAILED to
                ManagerAction.RetryGeneratedCleanup,
            UserMessageKind.CONVERSION_SAVE_FAILED to
                ManagerAction.SaveGeneratedConversion,
            UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED to
                ManagerAction.SaveGeneratedConversion,
        ).forEach { (kind, expected) ->
            composeRule.runOnIdle {
                message = UserMessage(kind, "복구가 필요합니다.", RecoveryAction.RETRY)
            }
            composeRule.onNodeWithText("다시 확인").performClick()
            composeRule.runOnIdle {
                assertEquals(listOf(expected), actions)
                actions.clear()
            }
        }
    }

    @Test
    fun pending_review_restore_exposes_real_retry_and_completes_exact_report() {
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        val review = requireNotNull(ready.review)
        val initializeResult = CompletableDeferred<ManagerRestoreResult>()
        val repository = CleanupRestoreUiRepository(
            initializeResult = initializeResult,
            review = review,
            generated = ready.generated,
        )
        val savedState = cleanupRestoreSavedState()
        val viewModel = ManagerViewModel(repository, savedState)
        composeRule.setContent {
            SixBridgeManagerApp(
                viewModel = viewModel,
                onAddSix = {},
                onInstallReviewed = {},
            )
        }

        composeRule.onNodeWithTag("home:convert-on-device").performClick()
        composeRule.runOnIdle {
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY),
            )
            initializeResult.complete(
                ManagerRestoreResult(
                    review = review,
                    installSessionId = 41,
                ),
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.route == ManagerRoute.ConversionResult(TOKEN)
        }
        composeRule.onNodeWithText("다시 확인").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY),
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertNull(
                savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY),
            )
        }

        composeRule.onNodeWithText("다시 확인").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.route == ManagerRoute.Report(PACKAGE)
        }
        composeRule.runOnIdle {
            assertEquals(listOf(TOKEN), repository.discardedReviews)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }
    }

    @Test
    fun pending_null_session_install_retry_completes_exact_report() {
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        val review = requireNotNull(ready.review)
        val initializeResult = CompletableDeferred<ManagerRestoreResult>()
        val repository = CleanupRestoreUiRepository(
            initializeResult = initializeResult,
            review = review,
            generated = ready.generated,
            installState = InstallState.AwaitingUserApproval(41),
        )
        val viewModel = ManagerViewModel(
            repository,
            cleanupNullSessionInstallSavedState(),
        )
        composeRule.setContent {
            SixBridgeManagerApp(
                viewModel = viewModel,
                onAddSix = {},
                onInstallReviewed = {},
            )
        }

        composeRule.runOnIdle {
            initializeResult.complete(
                ManagerRestoreResult(
                    review = review,
                    installSessionId = null,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.message?.kind ==
                UserMessageKind.CONVERSION_CLEANUP_FAILED
        }
        composeRule.onNodeWithText("다시 확인").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.route == ManagerRoute.Report(PACKAGE)
        }
        composeRule.runOnIdle {
            assertEquals(listOf(TOKEN to null), repository.cancelArguments)
            assertEquals(listOf(TOKEN), repository.discardedReviews)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }
    }

    @Test
    fun pending_review_initialize_failure_blocks_startup_and_preserves_owners() {
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        val review = requireNotNull(ready.review)
        val initializeResult = CompletableDeferred<ManagerRestoreResult>()
        val repository = CleanupRestoreUiRepository(
            initializeResult = initializeResult,
            review = review,
            generated = ready.generated,
        )
        val savedState = cleanupRestoreSavedState()
        val viewModel = ManagerViewModel(repository, savedState)
        composeRule.setContent {
            SixBridgeManagerApp(
                viewModel = viewModel,
                onAddSix = {},
                onInstallReviewed = {},
            )
        }

        composeRule.runOnIdle {
            initializeResult.completeExceptionally(
                IllegalStateException("transient"),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.startupRecoveryState ==
                StartupRecoveryUiState.RECOVERY_BLOCKED
        }
        composeRule.runOnIdle {
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY),
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY),
            )
            assertEquals(emptyList<String>(), repository.discardedReviews)
            assertEquals(emptyList<String>(), repository.discardedConversions)
        }
        composeRule.onNodeWithText("다시 확인").assertDoesNotExist()
    }

    @Test
    fun pending_review_restore_null_home_retry_completes_exact_report() {
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        val review = requireNotNull(ready.review)
        val initializeResult = CompletableDeferred<ManagerRestoreResult>()
        val repository = CleanupRestoreUiRepository(
            initializeResult = initializeResult,
            review = review,
            generated = ready.generated,
            restoredConversion = null,
        )
        val viewModel = ManagerViewModel(repository, cleanupRestoreSavedState())
        composeRule.setContent {
            SixBridgeManagerApp(
                viewModel = viewModel,
                onAddSix = {},
                onInstallReviewed = {},
            )
        }

        composeRule.runOnIdle {
            initializeResult.complete(
                ManagerRestoreResult(
                    review = review,
                    installSessionId = 41,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.message?.kind ==
                UserMessageKind.CONVERSION_CLEANUP_FAILED
        }
        composeRule.onNodeWithText("다시 확인").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.route == ManagerRoute.Report(PACKAGE)
        }
        composeRule.runOnIdle {
            assertEquals(listOf(TOKEN), repository.discardedReviews)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }
    }

    @Test
    fun generated_review_null_failure_has_no_retry_button_and_keeps_save_share() {
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        composeRule.setContent {
            ManagerAppContent(
                state = initial.copy(
                    conversionState = ready.copy(
                        review = null,
                        failureCode = ConversionFailureCode.BUNDLE_IO,
                    ),
                    message = conversionFailureMessage(ConversionFailureCode.BUNDLE_IO).copy(
                        recoveryAction = RecoveryAction.NONE,
                    ),
                ),
                onAction = {},
                onAddSix = {},
            )
        }

        composeRule.onNodeWithText("다시 확인").assertDoesNotExist()
        composeRule.onNodeWithTag("conversion:save").assertIsEnabled()
        composeRule.onNodeWithTag("conversion:share").assertIsEnabled()
    }

    @Test
    fun conversion_build_back_dispatches_cancel() {
        val actions = mutableListOf<ManagerAction>()
        val ready = conversionAnalysisState().conversionState as ConversionUiState.AnalysisReady
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    route = ManagerRoute.ConversionAnalysis(TOKEN),
                    conversionState = ConversionUiState.Building(TOKEN, ready),
                ),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        pressBack()
        composeRule.waitForIdle()

        assertEquals(listOf(ManagerAction.CancelConversion), actions)
    }

    @Test
    fun generated_unknown_sources_owner_disables_done_and_back_dispatches_cancel() {
        assertGeneratedActiveInstallControls(
            dev.sixbridge.manager.install.InstallState.UnknownSourcesPermissionRequired,
        )
    }

    @Test
    fun generated_writing_owner_disables_done_and_back_dispatches_cancel() {
        assertGeneratedActiveInstallControls(
            dev.sixbridge.manager.install.InstallState.WritingSession(40),
        )
    }

    @Test
    fun generated_awaiting_owner_disables_done_and_back_dispatches_cancel() {
        assertGeneratedActiveInstallControls(
            dev.sixbridge.manager.install.InstallState.AwaitingUserApproval(41),
        )
    }

    @Test
    fun generated_cleanup_retryable_failures_disable_install_for_all_four_codes() {
        var installState by mutableStateOf<dev.sixbridge.manager.install.InstallState>(
            dev.sixbridge.manager.install.InstallState.Idle,
        )
        composeRule.setContent {
            ManagerAppContent(
                state = conversionResultState().copy(installState = installState),
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            "CLEANUP_FAILED",
            "ABANDON_FAILED",
            "SESSION_RECOVERY_FAILED",
            "MISSING_OBSERVATION_RESET_FAILED",
        ).forEach { code ->
            composeRule.runOnIdle {
                installState =
                    dev.sixbridge.manager.install.InstallState.Failed(code, "cleanup retry")
            }
            composeRule.onNodeWithTag("conversion:install")
                .assertIsNotEnabled()
        }
    }

    @Test
    fun generated_bundle_share_grants_read_only_and_uses_bundle_mime() {
        val uri = Uri.parse("content://dev.sixbridge.manager.generated/result.six")

        val intent = generatedBundleSendIntent(
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
            uri,
        )

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(GENERATED_BUNDLE_MIME_TYPE, intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(1, requireNotNull(intent.clipData).itemCount)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun home_displays_decoded_extension_icon() {
        setHome(iconPng = pngBytes())

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertIsDisplayed()
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("테스트 확장 아이콘").assertIsDisplayed()
    }

    @Test
    fun home_uses_name_initial_when_icon_is_missing() {
        setHome(iconPng = null)

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("테스트 확장 아이콘").assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("테스트 확장 아이콘"))
            .assertCountEquals(1)
        composeRule.onNodeWithText("테").assertDoesNotExist()
        composeRule.onNodeWithText("테", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun home_uses_fallback_for_malformed_icon() {
        setHome(iconPng = byteArrayOf(0, 31))

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("테스트 확장 아이콘").assertIsDisplayed()
    }

    @Test
    fun home_uses_question_mark_and_package_description_for_blank_name() {
        setHome(iconPng = null, name = "   ")

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()
        composeRule.onNodeWithText("?", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("$PACKAGE 아이콘").assertIsDisplayed()
        composeRule.onAllNodes(hasContentDescription("$PACKAGE 아이콘"))
            .assertCountEquals(1)
    }

    @Test
    fun home_redecodes_same_reference_hash_collision_from_actual_to_fallback() {
        val iconPng = pngBytes()
        val originalHash = iconPng.contentHashCode()
        val revision = setRecomposingHome(iconPng)
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertIsDisplayed()

        composeRule.runOnIdle {
            corruptPngSignatureWithoutChangingContentHash(iconPng)
            assertEquals(originalHash, iconPng.contentHashCode())
            revision.value += 1
        }

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertDoesNotExist()
    }

    @Test
    fun home_redecodes_same_reference_hash_collision_from_fallback_to_actual() {
        val iconPng = pngBytes()
        val validPng = iconPng.copyOf()
        val originalHash = iconPng.contentHashCode()
        corruptPngSignatureWithoutChangingContentHash(iconPng)
        assertEquals(originalHash, iconPng.contentHashCode())
        val revision = setRecomposingHome(iconPng)
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertIsDisplayed()

        composeRule.runOnIdle {
            validPng.copyInto(iconPng)
            revision.value += 1
        }

        composeRule.onNodeWithTag("extension:$PACKAGE:icon:image").assertIsDisplayed()
        composeRule.onNodeWithTag("extension:$PACKAGE:icon:fallback").assertDoesNotExist()
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
    fun awaiting_manual_install_review_shows_file_manager_handoff_actions_only() {
        val actionNames = mutableListOf<String>()
        composeRule.setContent {
            ManagerAppContent(
                state = reviewState(ContractCompatibilityStatus.COMPATIBLE).copy(
                    installState = InstallState.AwaitingManualInstall(TOKEN),
                    message = null,
                ),
                onAction = { actionNames += it.javaClass.simpleName },
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("review:scroll")
            .performScrollToNode(hasTestTag("manual-install:open-file-manager"))
        composeRule.onNodeWithText("APK를 저장했습니다.").assertExists()
        composeRule.onNodeWithText(
            "시스템 파일 앱의 다운로드 폴더에서 저장한 APK를 직접 눌러 설치해 주세요.",
        ).assertExists()
        composeRule.onNodeWithText(
            "일부 서드파티 파일 앱에서 설치하면 삼성 인터넷에서 지원되지 않을 수 있습니다.",
        ).assertExists()
        composeRule.onNodeWithText("파일 앱 열기").assertExists()
        composeRule.onNodeWithText("삼성 내 파일", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("manual-install:open-file-manager").performClick()
        composeRule.onNodeWithTag("review:scroll")
            .performScrollToNode(hasTestTag("manual-install:check-status"))
        composeRule.onNodeWithTag("manual-install:check-status").performClick()

        assertEquals(
            listOf("OpenFileManager", "ConfirmManualInstallStatus"),
            actionNames,
        )
        composeRule.onNodeWithTag("review:install").assertDoesNotExist()
    }

    @Test
    fun awaiting_manual_install_conversion_result_shows_file_manager_handoff_actions_only() {
        val actionNames = mutableListOf<String>()
        composeRule.setContent {
            ManagerAppContent(
                state = conversionResultState().copy(
                    installState = InstallState.AwaitingManualInstall(TOKEN),
                    message = null,
                ),
                onAction = { actionNames += it.javaClass.simpleName },
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("conversion:result-screen")
            .performScrollToNode(hasTestTag("manual-install:open-file-manager"))
        composeRule.onNodeWithText("APK를 저장했습니다.").assertExists()
        composeRule.onNodeWithText(
            "시스템 파일 앱의 다운로드 폴더에서 저장한 APK를 직접 눌러 설치해 주세요.",
        ).assertExists()
        composeRule.onNodeWithText(
            "일부 서드파티 파일 앱에서 설치하면 삼성 인터넷에서 지원되지 않을 수 있습니다.",
        ).assertExists()
        composeRule.onNodeWithText("파일 앱 열기").assertExists()
        composeRule.onNodeWithText("삼성 내 파일", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("manual-install:open-file-manager").performClick()
        composeRule.onNodeWithTag("manual-install:check-status").performClick()

        assertEquals(
            listOf("OpenFileManager", "ConfirmManualInstallStatus"),
            actionNames,
        )
        composeRule.onNodeWithTag("conversion:install").assertDoesNotExist()
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
        composeRule.onNodeWithText("설치됨 · 삼성 인터넷 지원 미확인").assertDoesNotExist()
    }

    @Test
    fun report_route_displays_unverified_file_app_support_warning() {
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    route = ManagerRoute.Report(PACKAGE),
                    installed = listOf(installedItem()),
                    reportInstallSupportStatus =
                        SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                ),
                onAction = {},
                onAddSix = {},
            )
        }

        composeRule.onNodeWithText("설치됨 · 삼성 인터넷 지원 미확인").assertExists()
        composeRule.onNodeWithText(
            "삼성 인터넷 Add-ons에서 확장이 표시되고 활성화되는지 확인해 주세요.",
        ).assertExists()
    }

    @Test
    fun `destination presented ACK는 report 한 frame 뒤 exact token을 한 번 전달한다`() {
        val actions = mutableListOf<ManagerAction>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    route = ManagerRoute.Report(PACKAGE),
                    installed = listOf(installedItem()),
                    destinationCommitToken = TOKEN,
                ),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertEquals(
                listOf(ManagerAction.GeneratedCleanupDestinationPresented(TOKEN)),
                actions.filterIsInstance<
                    ManagerAction.GeneratedCleanupDestinationPresented
                >(),
            )
        }
    }

    @Test
    fun `destination presented ACK는 null을 건너뛰고 token route exact 조합만 전달한다`() {
        val actions = mutableListOf<ManagerAction>()
        var state by mutableStateOf(
            ManagerUiState(
                route = ManagerRoute.Report(PACKAGE),
                installed = listOf(installedItem()),
            ),
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ManagerAppContent(
                state = state,
                onAction = actions::add,
                onAddSix = {},
            )
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertEquals(emptyList<ManagerAction>(), actions)
            state = state.copy(destinationCommitToken = TOKEN)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            state = state.copy(route = ManagerRoute.Home)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            state = state.copy(destinationCommitToken = SECOND_TOKEN)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertEquals(
                listOf(TOKEN, TOKEN, SECOND_TOKEN),
                actions
                    .filterIsInstance<
                        ManagerAction.GeneratedCleanupDestinationPresented
                    >()
                    .map(ManagerAction.GeneratedCleanupDestinationPresented::token),
            )
        }
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

    @Test
    fun review_shows_verified_apk_provenance_and_security_limits() {
        composeRule.setContent {
            ManagerAppContent(
                state = reviewState(ContractCompatibilityStatus.COMPATIBLE),
                onAction = {},
                onAddSix = {},
            )
        }

        listOf(
            ORIGINAL_EXTENSION_ID,
            WRAPPER_SHA256,
            SIGNER_SHA256,
            "이 APK는 기기에서 생성되어 Google Play에서 배포·검사된 앱이 아닙니다.",
            "악성 코드 검사나 보안 보증을 의미하지 않습니다",
        ).forEach { expected ->
            composeRule.onNodeWithTag("review:scroll")
                .performScrollToNode(hasText(expected, substring = true))
            composeRule.onNodeWithText(expected, substring = true).assertIsDisplayed()
        }
    }

    private fun reviewState(status: ContractCompatibilityStatus): ManagerUiState {
        val review = ReviewBundle(
            token = "11111111-1111-1111-1111-111111111111",
            packageName = PACKAGE,
            extensionId = EXTENSION_ID,
            originalExtensionId = ORIGINAL_EXTENSION_ID,
            wrapperSha256 = WRAPPER_SHA256,
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
            signerSha256 = SIGNER_SHA256,
            wrapperVersionCode = 7,
        )
        return ManagerUiState(
            route = ManagerRoute.Review(review.token),
            importState = ImportUiState.ReviewReady(review, partialConfirmed = false),
        )
    }

    private fun conversionAnalysisState() = ManagerUiState(
        route = ManagerRoute.ConversionAnalysis(TOKEN),
        conversionState = ConversionUiState.AnalysisReady(
            token = TOKEN,
            report = CompatibilityReport(
                status = CompatibilityStatus.PARTIAL,
                manifestVersion = 3,
                extensionName = "테스트 확장",
                extensionVersion = "1.2.3",
                declaredPermissions = setOf("storage"),
                usedApis = setOf("tabs"),
                unsupportedApis = emptySet(),
                issues = emptyList(),
                conversionState = ConversionState.REQUIRES_APPROVAL,
                adapterDecisions = listOf(
                    AdapterDecision(
                        id = "action-popup-entry",
                        version = 1,
                        risk = AdapterRisk.BEHAVIOR_CHANGE,
                        disposition = AdapterDisposition.REQUIRES_APPROVAL,
                        applied = false,
                        evidence = emptyList(),
                        changes = listOf("팝업 진입 동작 변경"),
                        remainingLimitations = listOf("사용자 확인 필요"),
                    ),
                ),
            ),
            selectedAutomaticAdapters = emptySet(),
            requiredApprovals = setOf("action-popup-entry"),
            identityRequirement = IdentityRequirement.Stable("0123456789ab"),
        ),
    )

    private fun assertGeneratedActiveInstallControls(
        installState: dev.sixbridge.manager.install.InstallState,
    ) {
        val actions = mutableListOf<ManagerAction>()
        val initial = conversionResultState()
        val ready = initial.conversionState as ConversionUiState.ResultReady
        composeRule.setContent {
            ManagerAppContent(
                state = initial.copy(
                    conversionState = ready.copy(installOwnerActive = true),
                    installState = installState,
                ),
                onAction = actions::add,
                onAddSix = {},
            )
        }

        composeRule.onNodeWithTag("conversion:done").assertIsNotEnabled()
        composeRule.onNodeWithTag("conversion:cancel").assertIsEnabled()
        pressBack()
        composeRule.waitForIdle()

        assertEquals(listOf(ManagerAction.CancelConversion), actions)
    }

    private fun conversionResultState(): ManagerUiState {
        val review = reviewState(ContractCompatibilityStatus.COMPATIBLE)
            .importState
            .let { it as ImportUiState.ReviewReady }
            .review
        return ManagerUiState(
            route = ManagerRoute.ConversionResult(TOKEN),
            conversionState = ConversionUiState.ResultReady(
                token = TOKEN,
                review = review,
                generated = GeneratedBundle(
                    token = TOKEN,
                    file = File("/data/user/0/dev.sixbridge.manager/files/generated-bundles/$TOKEN.six"),
                    sha256 = "d".repeat(64),
                    suggestedFileName = "테스트-확장.six",
                ),
            ),
        )
    }

    private fun installedItem(
        iconPng: ByteArray? = null,
        name: String = "테스트 확장",
        report: ContractCompatibilityReport? = compatibilityReport(),
        reportAvailability: ReportAvailability = ReportAvailability.AVAILABLE,
    ) = ManagedExtensionItem(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = name,
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

    private fun setHome(
        iconPng: ByteArray?,
        name: String = "테스트 확장",
    ) {
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    installed = listOf(installedItem(iconPng = iconPng, name = name)),
                ),
                onAction = {},
                onAddSix = {},
            )
        }
    }

    private fun setRecomposingHome(iconPng: ByteArray): MutableState<Int> {
        val revision = mutableStateOf(0)
        composeRule.setContent {
            ManagerAppContent(
                state = ManagerUiState(
                    installed = listOf(installedItem(iconPng = iconPng)),
                ),
                onAction = {},
                onAddSix = {},
                modifier = Modifier.testTag("home:revision:${revision.value}"),
            )
        }
        return revision
    }

    private fun cleanupRestoreSavedState() = SavedStateHandle(
        mapOf(
            ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
            ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
            ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.GENERATED_CLEANUP_JOURNAL_KEY to
                """
                    {
                      "version": 1,
                      "token": "$TOKEN",
                      "reviewToken": "$TOKEN",
                      "phase": "REVIEW",
                      "destination": {
                        "kind": "REPORT",
                        "packageName": "$PACKAGE"
                      },
                      "installSessionId": null
                    }
                """.trimIndent(),
        ),
    )

    private fun cleanupNullSessionInstallSavedState() = SavedStateHandle(
        mapOf(
            ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
            ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.GENERATED_CLEANUP_JOURNAL_KEY to
                """
                    {
                      "version": 1,
                      "token": "$TOKEN",
                      "reviewToken": "$TOKEN",
                      "phase": "INSTALL",
                      "destination": {
                        "kind": "REPORT",
                        "packageName": "$PACKAGE"
                      },
                      "installSessionId": null
                    }
                """.trimIndent(),
        ),
    )

    private fun corruptPngSignatureWithoutChangingContentHash(iconPng: ByteArray) {
        iconPng[0] = (iconPng[0] + 1).toByte()
        iconPng[1] = (iconPng[1] - 31).toByte()
    }

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXTENSION_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ORIGINAL_EXTENSION_ID = "bcdefghijklmnopabcdefghijklmnopa"
        const val WRAPPER_SHA256 =
            "97bd5a88552319b46e3d5ed350451b18a7558170aa11d7947452c62365c75403"
        const val SIGNER_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private class CleanupRestoreUiRepository(
    private val initializeResult: CompletableDeferred<ManagerRestoreResult>,
    private val review: ReviewBundle,
    private val generated: GeneratedBundle,
    private val restoredConversion: RestoredConversion? =
        RestoredConversion.BundleReady(review, generated),
    installState: InstallState = InstallState.Idle,
) : ManagerRepository {
    override val catalog: Flow<List<CatalogRecord>> =
        MutableStateFlow(emptyList())
    override val installState: StateFlow<InstallState> =
        MutableStateFlow(installState)
    override val launches: Flow<RepositoryLaunch> = emptyFlow()
    val discardedReviews = mutableListOf<String>()
    val discardedConversions = mutableListOf<String>()
    val cancelArguments = mutableListOf<Pair<String, Int?>>()

    override suspend fun initialize(
        reviewToken: String?,
        installSessionId: Int?,
    ): ManagerRestoreResult = initializeResult.await()

    override suspend fun import(uri: Uri): ManagerImportResult =
        ManagerImportResult.Failure(
            ImportFailure(ImportFailureCode.SOURCE_UNREADABLE),
        )

    override suspend fun restoreConversion(
        importReview: Boolean,
    ): RestoredConversion? = restoredConversion

    override fun discardReview(token: String): Boolean {
        discardedReviews += token
        return true
    }

    override suspend fun discardConversion(token: String): Boolean {
        discardedConversions += token
        return true
    }

    override suspend fun install(
        review: ReviewBundle,
        partialConfirmed: Boolean,
    ): InstallState = InstallState.Idle

    override suspend fun cancelInstall(
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): InstallCleanupResult {
        cancelArguments += expectedReviewToken to expectedSessionId
        return InstallCleanupResult.Cleaned
    }

    override suspend fun terminalForOwner(
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): InstallTerminalOwnerResult = InstallTerminalOwnerResult.OwnerMismatch

    override suspend fun acknowledgeTerminal(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean = false

    override suspend fun finalizeTerminal(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean = false

    override fun unknownSourcesSettings(): RepositoryLaunch =
        error("unexpected unknown sources navigation")

    override suspend fun resumeAfterUnknownSourcesSettings() = Unit

    override fun uninstall(packageName: String): RepositoryLaunch =
        error("unexpected uninstall")

    override suspend fun reconcileUninstall(
        packageName: String,
    ): UninstallOutcome = error("unexpected uninstall reconciliation")

    override fun samsungAddOns(): RepositoryNavigationResult =
        error("unexpected add-ons navigation")

    override suspend fun reconcileCatalog() = Unit
}
