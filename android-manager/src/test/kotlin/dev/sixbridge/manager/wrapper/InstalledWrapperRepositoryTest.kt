package dev.sixbridge.manager.wrapper

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledWrapperRepositoryTest {
    @Test
    fun `returns only query candidates satisfying the complete wrapper contract`() = runBlocking {
        val valid = facts(
            extensionId = VALID_ID,
            name = "Installed extension",
            apkSha256 = "9".repeat(64),
            installSourceEvidence = InstallSourceEvidence.Known(
                installingPackageName = "com.google.android.packageinstaller",
                originatingPackageName = "com.sec.android.app.myfiles",
            ),
        )
        assertEquals(
            setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
            valid.resolvedIntentFilter.categories,
        )
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
                    facts(
                        extensionId = id('l'),
                        matchedActivity = ResolvedDiscoveryActivity(
                            packageName = "dev.sixbridge.ext.${id('l')}",
                            name = "dev.example.DecoyActivity",
                            exported = true,
                        ),
                    ),
                    facts(
                        extensionId = pair('a', 'b'),
                        matchedActivity = ResolvedDiscoveryActivity(
                            packageName = "dev.sixbridge.ext.${pair('c', 'd')}",
                            name = DISCOVERY_ACTIVITY,
                            exported = true,
                        ),
                    ),
                    facts(
                        extensionId = pair('e', 'f'),
                        matchedActivity = ResolvedDiscoveryActivity(
                            packageName = "dev.sixbridge.ext.${pair('e', 'f')}",
                            name = DISCOVERY_ACTIVITY,
                            exported = false,
                        ),
                    ),
                    facts(extensionId = pair('a', 'c'), filter = strictFilter().copy(actions = setOf(VIEW_ACTION, "extra"), actionCount = 2)),
                    facts(extensionId = pair('a', 'd'), filter = strictFilter().copy(actions = setOf("extra"), actionCount = 1)),
                    facts(extensionId = pair('a', 'e'), filter = strictFilter().copy(categories = setOf(BROWSABLE_CATEGORY), categoryCount = 1)),
                    facts(extensionId = pair('a', 'f'), filter = strictFilter().copy(categories = setOf(DEFAULT_CATEGORY), categoryCount = 1)),
                    facts(extensionId = pair('a', 'n'), filter = strictFilter().copy(categories = setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY, "extra"), categoryCount = 3)),
                    facts(extensionId = pair('a', 'g'), filter = strictFilter().copy(schemes = setOf("six", "extra"), schemeCount = 2)),
                    facts(extensionId = pair('a', 'h'), filter = strictFilter().copy(schemes = setOf("extra"), schemeCount = 1)),
                    facts(extensionId = pair('a', 'i'), filter = strictFilter().copy(authorityHosts = setOf("ex", "other"), authorityCount = 2)),
                    facts(extensionId = pair('a', 'j'), filter = strictFilter().copy(authorityHosts = setOf("other"), authorityCount = 1)),
                    facts(extensionId = pair('a', 'k'), filter = strictFilter().copy(authorityPorts = setOf(443))),
                    facts(extensionId = pair('a', 'l'), filter = strictFilter().copy(dataTypes = setOf("text/plain"), dataTypeCount = 1)),
                    facts(extensionId = pair('a', 'm'), filter = strictFilter().copy(dataPaths = setOf("/path"), dataPathCount = 1)),
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
                    apkSha256 = "9".repeat(64),
                    installSourceEvidence = InstallSourceEvidence.Known(
                        installingPackageName = "com.google.android.packageinstaller",
                        originatingPackageName = "com.sec.android.app.myfiles",
                    ),
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
    fun `rejects all duplicate package query facts independent of input order`() = runBlocking {
        val first = facts(extensionId = VALID_ID)
        val signerConflict = facts(extensionId = VALID_ID, signerSha256 = listOf("2".repeat(64)))

        val inForwardOrder = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway { listOf(first, signerConflict) },
        ).findAll()
        val inReverseOrder = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway { listOf(signerConflict, first) },
        ).findAll()

        assertEquals(emptyList(), inForwardOrder)
        assertEquals(inForwardOrder, inReverseOrder)
    }

    @Test
    fun `find returns the validated package and rejects other package names`() = runBlocking {
        val repository = PackageManagerInstalledWrapperRepository(
            InstalledWrapperPackageGateway { listOf(facts(extensionId = VALID_ID)) },
        )

        assertEquals("dev.sixbridge.ext.$VALID_ID", repository.find("dev.sixbridge.ext.$VALID_ID")?.packageName)
        assertNull(repository.find("dev.sixbridge.ext.other"))
    }

    @Test
    fun `legacy promotion proof용 installed fact는 양수 Int version과 canonical signer만 반환한다`() =
        runBlocking {
            val repository = PackageManagerInstalledWrapperRepository(
                InstalledWrapperPackageGateway {
                    listOf(
                        facts(extensionId = VALID_ID, versionCode = 7),
                        facts(extensionId = id('b'), versionCode = 0),
                        facts(extensionId = id('c'), versionCode = Int.MAX_VALUE.toLong() + 1),
                        facts(extensionId = id('d'), signerSha256 = listOf("NOT-A-SHA256")),
                    )
                },
            )

            assertEquals(listOf("dev.sixbridge.ext.$VALID_ID"), repository.findAll().map { it.packageName })
        }

    private fun facts(
        extensionId: String,
        packageName: String = "dev.sixbridge.ext.$extensionId",
        metadataExtensionId: String = extensionId,
        name: String = "Example",
        versionCode: Long = 7,
        signerSha256: List<String> = listOf(SIGNER),
        apkSha256: String? = null,
        installSourceEvidence: InstallSourceEvidence = InstallSourceEvidence.Unavailable,
        matchedActivity: ResolvedDiscoveryActivity = ResolvedDiscoveryActivity(
            packageName = packageName,
            name = DISCOVERY_ACTIVITY,
            exported = true,
        ),
        filter: ResolvedDiscoveryIntentFilter = strictFilter(),
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
        versionCode = versionCode,
        signerSha256 = signerSha256,
        matchedActivity = matchedActivity,
        resolvedIntentFilter = filter,
        metadata = mapOf("extension-id" to metadataExtensionId),
        activities = activities,
        providers = providers,
        apkSha256 = apkSha256,
        installSourceEvidence = installSourceEvidence,
    )

    private fun id(character: Char) = character.toString().repeat(32)

    private fun pair(first: Char, second: Char) = "$first$second".repeat(16)

    private fun strictFilter() = ResolvedDiscoveryIntentFilter(
        actions = setOf(VIEW_ACTION),
        actionCount = 1,
        categories = setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY),
        categoryCount = 2,
        schemes = setOf("six"),
        schemeCount = 1,
        authorityHosts = setOf("ex"),
        authorityPorts = setOf(null),
        authorityCount = 1,
        dataTypes = emptySet(),
        dataTypeCount = 0,
        dataPaths = emptySet(),
        dataPathCount = 0,
        schemeSpecificPartCount = 0,
    )

    private companion object {
        const val VALID_ID = "aabbccddeeffgghhiijjkkllmmnnoopp"
        val SIGNER = "1".repeat(64)
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val EXTENSION_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
        const val VIEW_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
    }
}
