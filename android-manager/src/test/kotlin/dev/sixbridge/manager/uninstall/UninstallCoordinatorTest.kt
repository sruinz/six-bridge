package dev.sixbridge.manager.uninstall

import android.content.Intent
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UninstallCoordinatorTest {
    @Test
    fun `system uninstall intent uses exact package URI`() {
        val coordinator = DefaultUninstallCoordinator(FakeInstalledWrappers())

        val route = coordinator.uninstallRoute(PACKAGE_NAME)

        assertEquals(Intent.ACTION_DELETE, route.action)
        assertEquals("package:$PACKAGE_NAME", route.data)
    }

    @Test
    fun `return with installed package reports cancellation without catalog side effect`() = runTest {
        val coordinator = DefaultUninstallCoordinator(
            FakeInstalledWrappers(installedPackage = PACKAGE_NAME),
        )

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.CancelledOrStillInstalled, outcome)
    }

    @Test
    fun `return after confirmed removal only reports Removed`() = runTest {
        val coordinator = DefaultUninstallCoordinator(FakeInstalledWrappers())

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.Removed, outcome)
    }

    @Test
    fun `return while package state is propagating waits for confirmed removal`() = runTest {
        val coordinator = DefaultUninstallCoordinator(
            SequencedInstalledWrappers(listOf(true, false)),
        )

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.Removed, outcome)
    }

    private class FakeInstalledWrappers(
        private val installedPackage: String? = null,
    ) : InstalledWrapperRepository {
        override suspend fun findAll(): List<InstalledWrapper> = emptyList()

        override suspend fun find(packageName: String): InstalledWrapper? =
            installedPackage?.takeIf { it == packageName }?.let {
                InstalledWrapper(it, "extension", "Example", 1, "signer")
            }
    }

    private class SequencedInstalledWrappers(
        private val installedStates: List<Boolean>,
    ) : InstalledWrapperRepository {
        private var findIndex = 0

        override suspend fun findAll(): List<InstalledWrapper> = emptyList()

        override suspend fun find(packageName: String): InstalledWrapper? {
            val installed = installedStates.getOrElse(findIndex) { installedStates.last() }
            findIndex += 1
            return if (installed) {
                InstalledWrapper(packageName, "extension", "Example", 1, "signer")
            } else {
                null
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "dev.sixbridge.ext.aabbccddeeffgghhiijjkkllmmnnoopp"
    }
}
