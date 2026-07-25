package dev.sixbridge.manager.wrapper

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledWrapperRepositoryTest {
    @Test
    fun `returns only query candidates satisfying the complete wrapper contract`() = runBlocking {
        val valid = facts(extensionId = VALID_ID, name = "Installed extension")
        val repository = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway {
                listOf(
                    valid,
                    facts(extensionId = id('b'), packageName = "dev.example.browser"),
                    facts(extensionId = id('c'), metadataExtensionId = id('d')),
                    facts(extensionId = "bad-id", packageName = "dev.sixbridge.ext.bad-id"),
                    facts(
                        extensionId = id('e'),
                        activities = listOf(InstalledWrapperActivity("dev.example.DecoyActivity", exported = true)),
                    ),
                    facts(
                        extensionId = id('f'),
                        activities = listOf(InstalledWrapperActivity(DISCOVERY_ACTIVITY, exported = false)),
                    ),
                    facts(extensionId = id('g'), providers = emptyList()),
                    facts(
                        extensionId = id('h'),
                        providers = listOf(
                            InstalledWrapperProvider(
                                name = "dev.example.DecoyProvider",
                                authority = "dev.sixbridge.ext.${id('h')}.contentprovider",
                                exported = true,
                            ),
                        ),
                    ),
                    facts(
                        extensionId = id('i'),
                        providers = listOf(
                            InstalledWrapperProvider(
                                name = EXTENSION_PROVIDER,
                                authority = "dev.sixbridge.ext.${id('i')}.contentprovider",
                                exported = false,
                            ),
                        ),
                    ),
                    facts(
                        extensionId = id('j'),
                        providers = listOf(
                            InstalledWrapperProvider(
                                name = EXTENSION_PROVIDER,
                                authority = "dev.example.provider",
                                exported = true,
                            ),
                        ),
                    ),
                    facts(extensionId = id('k'), signerSha256 = listOf(SIGNER, "2".repeat(64))),
                )
            },
        )

        assertEquals(
            listOf(
                InstalledWrapper(
                    packageName = "dev.sixbridge.ext.$VALID_ID",
                    extensionId = VALID_ID,
                    name = "Installed extension",
                    versionCode = 7,
                    signerSha256 = SIGNER,
                ),
            ),
            repository.findAll(),
        )
    }

    @Test
    fun `falls back to extension ID when the application label is blank`() = runBlocking {
        val repository = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway { listOf(facts(extensionId = VALID_ID, name = "  ")) },
        )

        assertEquals(VALID_ID, repository.findAll().single().name)
    }

    @Test
    fun `drops duplicate package query results deterministically`() = runBlocking {
        val repository = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway {
                listOf(
                    facts(extensionId = VALID_ID, name = "first"),
                    facts(extensionId = VALID_ID, name = "second"),
                )
            },
        )

        assertEquals(listOf(VALID_ID), repository.findAll().map(InstalledWrapper::extensionId))
    }

    @Test
    fun `find returns the validated package and rejects other package names`() = runBlocking {
        val repository = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway { listOf(facts(extensionId = VALID_ID)) },
        )

        assertEquals("dev.sixbridge.ext.$VALID_ID", repository.find("dev.sixbridge.ext.$VALID_ID")?.packageName)
        assertNull(repository.find("dev.sixbridge.ext.other"))
    }

    private fun facts(
        extensionId: String,
        packageName: String = "dev.sixbridge.ext.$extensionId",
        metadataExtensionId: String = extensionId,
        name: String = "Example",
        signerSha256: List<String> = listOf(SIGNER),
        activities: List<InstalledWrapperActivity> = listOf(
            InstalledWrapperActivity(DISCOVERY_ACTIVITY, exported = true),
        ),
        providers: List<InstalledWrapperProvider> = listOf(
            InstalledWrapperProvider(
                name = EXTENSION_PROVIDER,
                authority = "$packageName.contentprovider",
                exported = true,
            ),
        ),
    ) = InstalledWrapperPackageFacts(
        packageName = packageName,
        applicationLabel = name,
        versionCode = 7,
        signerSha256 = signerSha256,
        metadata = mapOf("extension-id" to metadataExtensionId),
        activities = activities,
        providers = providers,
    )

    private fun id(character: Char) = character.toString().repeat(32)

    private companion object {
        const val VALID_ID = "aabbccddeeffgghhiijjkkllmmnnoopp"
        val SIGNER = "1".repeat(64)
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val EXTENSION_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
    }
}
