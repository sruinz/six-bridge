package dev.sixbridge.manager.conversion

import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.PreparedConversion
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.packager.OnDeviceWrapperPathRequest
import dev.sixbridge.manager.packager.OnDeviceWrapperResult
import java.io.File
import java.security.MessageDigest

internal class DefaultConversionWorkPort(
    private val store: ConversionWorkStore,
) : ConversionWorkPort {
    override suspend fun create(artifact: OnDeviceInputArtifact) = store.create(artifact)
    override suspend fun restore() = store.restore()
    override suspend fun restoreBuildAware() = store.restorePreservingBuilding()
    override suspend fun markAwaitingApproval(token: String, profileSha256: String) {
        store.markAwaitingApproval(token, profileSha256)
    }
    override suspend fun refreshAwaitingApproval(
        token: String,
        expectedProfileSha256: String,
        profileSha256: String,
    ) {
        store.refreshAwaitingApproval(token, expectedProfileSha256, profileSha256)
    }
    override suspend fun markBuilding(
        token: String,
        approvedAdapterIds: Set<String>,
        profileSha256: String,
    ) {
        store.markBuilding(token, approvedAdapterIds, profileSha256)
    }
    override suspend fun markBundleReady(token: String, bundle: File, expectedSha256: String) {
        store.markBundleReady(token, bundle, expectedSha256)
    }
    override suspend fun resetAwaitingApproval(token: String, profileSha256: String) {
        store.resetAwaitingApproval(token, profileSha256)
    }
    override suspend fun discard(token: String) = store.discard(token)

    override suspend fun <T> withVerifiedInputFile(
        token: String,
        consume: (File) -> T,
    ): T {
        return store.withVerifiedInputFile(token, consume)
    }
}

internal class DefaultConversionRuntime : ConversionRuntimePort {
    override fun analyze(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
    ): ConversionAnalysisSnapshot {
        if (kind == OnDeviceInputKind.SIX_DRAFT) {
            val draft = SixDraft.read(input.toPath())
            val payload = draft.payload
            val signedApprovals = payload.plan.selectedAdapters.mapTo(sortedSetOf()) { it.id }
            return when (
                val replay = SixDraftReplay().replay(
                    draft,
                    profile,
                    signedApprovals,
                )
            ) {
                is DraftReplayResult.Ready -> snapshot(
                    replay.prepared.compatibilityBefore,
                    replay.prepared.logicalSourceIdentity.toManagerIdentity(),
                    payload.input.inputKind,
                )
                is DraftReplayResult.ProfileChanged -> snapshot(
                    replay.currentAnalysis,
                    logicalIdentity(payload.draft.sourceIdentity),
                    payload.input.inputKind,
                    profileChanged = true,
                )
            }
        }
        val portable = portable(input, kind)
        return ConversionPreparationEngine().use { engine ->
            val report = engine.analyze(portable, profile)
            val prepared = runCatching {
                engine.prepare(
                    portable,
                    profile,
                    report.adapterDecisions
                        .filter { it.disposition == AdapterDisposition.REQUIRES_APPROVAL }
                        .map { it.id },
                )
            }.getOrNull()
            snapshot(
                report,
                prepared?.logicalSourceIdentity.toManagerIdentity(),
                portable.kind,
            )
        }
    }

    override fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
    ): PreparedConversionSnapshot {
        var sourceKind = kind.name
        val prepared = if (kind == OnDeviceInputKind.SIX_DRAFT) {
            val draft = SixDraft.read(input.toPath())
            sourceKind = draft.payload.input.inputKind.name
            if (draft.payload.input.browserProfileSha256 == profile.canonicalSha256()) {
                when (
                    val replayed = SixDraftReplay().replay(
                    draft,
                    profile,
                    approvedAdapterIds,
                    )
                ) {
                    is DraftReplayResult.Ready -> replayed.prepared
                    is DraftReplayResult.ProfileChanged -> error("profile proof race")
                }
            } else {
                ConversionPreparationEngine().use {
                    it.prepare(portableDraft(draft), profile, approvedAdapterIds)
                }
            }
        } else {
            ConversionPreparationEngine().use {
                it.prepare(portable(input, kind), profile, approvedAdapterIds)
            }
        }
        val after = prepared.compatibilityAfter
        val inputKind = ExtensionInputKind.valueOf(sourceKind)
        val identityMode = installationIdentityMode(
            inputKind,
            after.conversionState,
        )
        check(
            (prepared.passthroughCrx != null) ==
                (identityMode == InstallationIdentityMode.PASSTHROUGH),
        ) {
            "준비 결과의 passthrough proof와 installation identity mode가 다릅니다."
        }
        return PreparedConversionSnapshot(
            reportBefore = prepared.compatibilityBefore,
            originalSourceSha256 = prepared.originalSourceSha256,
            planSha256 = prepared.planSha256,
            transformedSourceZip = prepared.transformedSourceZip,
            passthroughCrx = prepared.passthroughCrx,
            logicalSourceIdentity = prepared.logicalSourceIdentity.toManagerIdentity(),
            identityMode = identityMode,
            descriptorName = requireNotNull(after.extensionName),
            descriptorVersion = requireNotNull(after.extensionVersion),
            sourceKind = sourceKind,
            opaque = prepared,
        )
    }

    override fun bind(
        prepared: PreparedConversionSnapshot,
        profile: BrowserProfile,
        identity: OnDevicePackageIdentity,
    ): BoundConversionSnapshot {
        val bound = ConversionPreparationEngine().use {
            it.bindIdentity(
                prepared.opaque as PreparedConversion,
                profile,
                identity.crxPublicKeyDer,
            )
        }
        return BoundConversionSnapshot(
            sourceZip = bound.sourceZip,
            passthroughCrx = bound.passthroughCrx,
            compatibility = SixBundle.toContractV4(
                bound.compatibility,
                prepared.originalSourceSha256,
                prepared.planSha256,
            ),
            extensionId = bound.extensionId,
            sourceKind = prepared.sourceKind,
        )
    }

    private fun portable(file: File, kind: OnDeviceInputKind): PortableExtensionInput =
        when (kind) {
            OnDeviceInputKind.ZIP -> PortableExtensionInput.fromZip(
                ExtensionInputKind.ZIP,
                file.readBytes(),
            )
            OnDeviceInputKind.CRX3 -> PortableExtensionInput.fromCrx(
                ExtensionInputKind.CRX3,
                file.readBytes(),
            )
            OnDeviceInputKind.CHROME_WEB_STORE -> PortableExtensionInput.fromCrx(
                ExtensionInputKind.CHROME_WEB_STORE,
                file.readBytes(),
            )
            OnDeviceInputKind.SIX_DRAFT -> error("draft는 replay 경로를 사용해야 합니다.")
        }

    private fun portableDraft(
        draft: dev.sixbridge.core.draft.VerifiedSixDraft,
    ): PortableExtensionInput {
        val payload = draft.payload
        val signedIdentity = payload.draft.sourceIdentity
            .takeIf { payload.draft.schemaVersion == 2 }
        return when (payload.sourceKind) {
            dev.sixbridge.core.draft.DraftSourceKind.ZIP ->
                PortableExtensionInput.fromZip(
                    payload.input.inputKind,
                    payload.source,
                    signedIdentity,
                )
            dev.sixbridge.core.draft.DraftSourceKind.CRX ->
                PortableExtensionInput.fromCrx(
                    payload.input.inputKind,
                    payload.source,
                    payload.input.originalExtensionId,
                    signedIdentity,
                )
        }
    }

    private fun snapshot(
        report: dev.sixbridge.core.compat.CompatibilityReport,
        logicalIdentity: LogicalSourceIdentity?,
        inputKind: ExtensionInputKind,
        profileChanged: Boolean = false,
    ) = ConversionAnalysisSnapshot(
        report = report,
        selectedAutomaticAdapters = report.adapterDecisions
            .filter { it.disposition == AdapterDisposition.AUTOMATIC }
            .mapTo(sortedSetOf()) { it.id },
        requiredApprovals = report.adapterDecisions
            .filter { it.disposition == AdapterDisposition.REQUIRES_APPROVAL }
            .mapTo(sortedSetOf()) { it.id },
        logicalSourceIdentity = logicalIdentity,
        identityMode = installationIdentityMode(inputKind, report.conversionState),
        profileChanged = profileChanged,
    )

    private fun logicalIdentity(value: String?): LogicalSourceIdentity? =
        value?.let(LogicalSourceIdentity::signedDraft)

    private fun LogicalSourceIdentityResult?.toManagerIdentity(): LogicalSourceIdentity? =
        when (this) {
            is LogicalSourceIdentityResult.Stable -> when (evidence) {
                LogicalIdentityEvidence.VERIFIED_STORE_EXTENSION_ID ->
                    LogicalSourceIdentity.storeExtensionId(value.removePrefix("store:"))
                LogicalIdentityEvidence.SIGNED_DRAFT_METADATA ->
                    LogicalSourceIdentity.signedDraft(value)
                LogicalIdentityEvidence.PROJECT_MARKER ->
                    LogicalSourceIdentity.projectMarker(value.removePrefix("project:"))
                LogicalIdentityEvidence.MANIFEST_KEY ->
                    LogicalSourceIdentity.manifestKeyExtensionId(value.removePrefix("manifest:"))
            }
            else -> null
        }
}

internal class DefaultConversionIdentityPort(
    private val ledger: ConversionIdentityLedger,
    private val packager: OnDeviceWrapperPackager,
) : ConversionIdentityPort {
    override suspend fun existingTargets(profileMajorVersion: Int) =
        ledger.listExistingTargets(profileMajorVersion)

    override suspend fun resolve(
        prepared: PreparedConversionSnapshot,
        profile: BrowserProfile,
        selection: InstallationIdentitySelection?,
        ownerToken: String,
    ): ResolvedPackageIdentity {
        require(
            (prepared.identityMode == InstallationIdentityMode.PASSTHROUGH) ==
                (prepared.passthroughCrx != null),
        ) {
            "prepared identity mode와 passthrough CRX proof가 일치하지 않습니다."
        }
        val key = ledger.resolveSelection(
            prepared.logicalSourceIdentity,
            profile.majorVersion,
            prepared.identityMode,
            selection,
        )
        if (prepared.identityMode == InstallationIdentityMode.PASSTHROUGH) {
            return ResolvedPackageIdentity(
                key,
                packager.resolveIdentity(key, prepared.passthroughCrx),
                null,
            )
        }
        val lease = if (ledger.hasIdentity(key)) {
            null
        } else {
            ledger.resolveUnreservedIdentity(key, ownerToken, packager)
        }
        val identity = lease?.identity ?: packager.resolveIdentity(key, null)
        return ResolvedPackageIdentity(key, identity, lease)
    }

    override suspend fun reserve(
        resolved: ResolvedPackageIdentity,
        displayName: String,
        installedVersionCode: Long?,
        ownerToken: String,
    ): ReservedPackageIdentity {
        val publicKeySha = resolved.identity.crxPublicKeyDer?.let(::sha256)
        val reservation = ledger.reserve(
            ConversionIdentityReservation(
                identityKey = resolved.key,
                displayName = displayName,
                packageBinding = ConversionPackageBinding(
                    resolved.identity.extensionId,
                    resolved.identity.packageName,
                    resolved.identity.wrapperSignerSha256,
                    resolved.identity.crxKeyAlias,
                    publicKeySha,
                ),
                installedVersionCode = installedVersionCode,
            ),
            ownerToken,
            resolved.unreservedLease as? dev.sixbridge.manager.packager.UnreservedIdentityLease,
        )
        return ReservedPackageIdentity(resolved, reservation.versionCode, reservation)
    }

    override suspend fun abort(ownerToken: String) {
        ledger.abortByOwnerToken(ownerToken, packager)
    }
}

internal class DefaultConversionPackagePort(
    private val ledger: ConversionIdentityLedger,
    private val packager: OnDeviceWrapperPackager,
) : ConversionPackagePort {
    override suspend fun packageWrapper(
        reservation: ReservedPackageIdentity,
        bound: BoundConversionSnapshot,
        token: String,
    ): PackagedConversion {
        val output = packager.outputFile(token)
        val source = packager.stageSource(
            token,
            bound.passthroughCrx ?: bound.sourceZip,
        )
        try {
            val result = packager.packageWrapper(
                ledger,
                OnDeviceWrapperPathRequest(
                    reservation = reservation.opaque as ConversionIdentityLedger.Reservation,
                    canonicalSourceZip = source.takeIf { bound.passthroughCrx == null },
                    passthroughCrx = source.takeIf { bound.passthroughCrx != null },
                    descriptorName = bound.compatibility.extensionName.orEmpty(),
                    descriptorVersion = bound.compatibility.extensionVersion.orEmpty(),
                    sourceKind = bound.sourceKind,
                    compatibility = bound.compatibility,
                    outputApk = output,
                ),
            )
            return result.toPackaged()
        } finally {
            source.setWritable(true)
            source.delete()
        }
    }

    private fun OnDeviceWrapperResult.toPackaged() = PackagedConversion(
        extensionId,
        packageName,
        versionCode,
        wrapperSha256,
        outputApk,
        this,
    )
}

internal class DefaultConversionBundlePort(
    private val store: GeneratedBundleStore,
) : ConversionBundlePort {
    override fun publish(token: String, packaged: PackagedConversion) =
        store.publish(token, packaged.opaque as OnDeviceWrapperResult)
    override fun recover(token: String): GeneratedBundle? =
        runCatching { store.recover(token) }.getOrNull()
    override fun discard(token: String): Boolean = store.discard(token)
    override fun copyTo(token: String, output: java.io.OutputStream): String =
        store.copyTo(token, output)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
