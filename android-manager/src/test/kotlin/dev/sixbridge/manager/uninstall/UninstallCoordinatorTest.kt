package dev.sixbridge.manager.uninstall

import android.content.Intent
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UninstallCoordinatorTest {
    @Test
    fun `system uninstall intent uses exact package URI`() {
        val coordinator = DefaultUninstallCoordinator(FakeInstalledWrappers(), FakeCatalog())

        val route = coordinator.uninstallRoute(PACKAGE_NAME)

        assertEquals(Intent.ACTION_DELETE, route.action)
        assertEquals("package:$PACKAGE_NAME", route.data)
    }

    @Test
    fun `return with installed package keeps catalog record`() = runTest {
        val catalog = FakeCatalog()
        val coordinator = DefaultUninstallCoordinator(
            FakeInstalledWrappers(installedPackage = PACKAGE_NAME),
            catalog,
        )

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.CancelledOrStillInstalled, outcome)
        assertNull(catalog.removedPackage)
    }

    @Test
    fun `return after confirmed removal deletes catalog record`() = runTest {
        val catalog = FakeCatalog()
        val coordinator = DefaultUninstallCoordinator(FakeInstalledWrappers(), catalog)

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.Removed, outcome)
        assertEquals(PACKAGE_NAME, catalog.removedPackage)
    }

    @Test
    fun `return while package state is propagating waits for confirmed removal`() = runTest {
        val catalog = FakeCatalog()
        val coordinator = DefaultUninstallCoordinator(
            SequencedInstalledWrappers(listOf(true, false)),
            catalog,
        )

        val outcome = coordinator.reconcileAfterReturn(PACKAGE_NAME)

        assertEquals(UninstallOutcome.Removed, outcome)
        assertEquals(PACKAGE_NAME, catalog.removedPackage)
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

    private class FakeCatalog : ManagerCatalog {
        var removedPackage: String? = null

        override fun observe(): Flow<List<CatalogRecord>> = emptyFlow()
        override suspend fun replaceVerified(record: CatalogRecord) = Unit
        override suspend fun remove(packageName: String) {
            removedPackage = packageName
        }

        override suspend fun removeAll(packageNames: Set<String>) = Unit
        override suspend fun get(packageName: String): CatalogRecord? = null
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
