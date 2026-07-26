package dev.sixbridge.manager.uninstall

import android.content.Intent
import android.net.Uri
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay

internal data class IntentRoute(
    val action: String,
    val data: String,
)

sealed interface UninstallOutcome {
    data object Removed : UninstallOutcome
    data object CancelledOrStillInstalled : UninstallOutcome
}

interface UninstallCoordinator {
    fun intent(packageName: String): Intent
    suspend fun reconcileAfterReturn(packageName: String): UninstallOutcome
}

class DefaultUninstallCoordinator(
    private val installedWrappers: InstalledWrapperRepository,
) : UninstallCoordinator {
    override fun intent(packageName: String): Intent = uninstallRoute(packageName).toIntent()

    internal fun uninstallRoute(packageName: String): IntentRoute =
        IntentRoute(Intent.ACTION_DELETE, "package:$packageName")

    override suspend fun reconcileAfterReturn(packageName: String): UninstallOutcome {
        repeat(PACKAGE_STATE_CHECK_COUNT) { index ->
            val installed = try {
                installedWrappers.find(packageName)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return UninstallOutcome.CancelledOrStillInstalled
            }
            if (installed == null) {
                return UninstallOutcome.Removed
            }
            if (index < PACKAGE_STATE_CHECK_COUNT - 1) {
                delay(PACKAGE_STATE_CHECK_INTERVAL_MS)
            }
        }

        return UninstallOutcome.CancelledOrStillInstalled
    }

    private fun IntentRoute.toIntent(): Intent = Intent(action, Uri.parse(data))

    private companion object {
        const val PACKAGE_STATE_CHECK_COUNT = 5
        const val PACKAGE_STATE_CHECK_INTERVAL_MS = 200L
    }
}
