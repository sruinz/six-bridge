package dev.sixbridge.manager.conversion

import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisArchiveKind
import dev.sixbridge.core.compat.AnalysisBudgetExceededException
import dev.sixbridge.core.compat.AnalysisInputMaterializer
import dev.sixbridge.core.compat.analysisBudgetExceededReport
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.PreparedConversion
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.ControlledSixDraft
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.draft.VerifiedPreparedSixDraft
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
    override suspend fun restoreForStartup() = store.restoreForStartup()
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

internal class DefaultConversionRuntime(
    private val archivePayloadReader: (File) -> ByteArray = File::readBytes,
) : ConversionRuntimePort {
    override fun analyze(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        control: AnalysisControl,
    ): ConversionAnalysisSnapshot {
        if (kind == OnDeviceInputKind.SIX_DRAFT) {
            return analyzeDraft(input, profile, control)
        }
        val portable = try {
            portable(input, kind, control)
        } catch (error: AnalysisBudgetExceededException) {
            return snapshot(
                report = analysisBudgetExceededReport(error.exceeded),
                logicalIdentity = null,
                inputKind = inputKind(kind),
            )
        }
        val analyzed = ConversionPreparationEngine().use {
            it.analyzeWithIdentity(portable, profile, control)
        }
        return snapshot(
            report = analyzed.report,
            logicalIdentity = analyzed.logicalSourceIdentity.toManagerIdentity(),
            inputKind = portable.kind,
        )
    }

    private fun analyzeDraft(
        input: File,
        profile: BrowserProfile,
        control: AnalysisControl,
    ): ConversionAnalysisSnapshot =
        when (val controlled = SixDraft.readControlled(input.toPath(), control)) {
            is ControlledSixDraft.BudgetExceeded -> snapshot(
                report = analysisBudgetExceededReport(controlled.exceeded),
                logicalIdentity = null,
                inputKind = ExtensionInputKind.ZIP,
            )
            is ControlledSixDraft.Prepared -> {
                val prepared = controlled.verified
                val sameProfile =
                    prepared.input.browserProfileSha256 == profile.canonicalSha256()
                val report = if (sameProfile) {
                    prepared.compatibilityBefore
                } else {
                    prepared.compatibilityBefore.copy(
                        status = CompatibilityStatus.UNKNOWN,
                        issues = prepared.compatibilityBefore.issues + CompatibilityIssue(
                            code = "draft_profile_changed",
                            message = "prepared draft의 browser profile이 현재 기기와 다릅니다.",
                            blocking = true,
                        ),
                    )
                }
                snapshot(
                    report = report,
                    logicalIdentity = logicalIdentity(prepared.draft.sourceIdentity),
                    inputKind = prepared.input.inputKind,
                    profileChanged = !sameProfile,
                    identityMode = InstallationIdentityMode.PREPARED,
                )
            }
            is ControlledSixDraft.Legacy -> {
                val draft = controlled.verified
                val payload = draft.payload
                val signedApprovals = payload.plan.selectedAdapters
                    .mapTo(sortedSetOf()) { it.id }
                when (
                    val replay = SixDraftReplay().replay(
                        draft,
                        profile,
                        signedApprovals,
                        control,
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
        }

    override fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
    ): PreparedConversionSnapshot = prepare(
        input,
        kind,
        profile,
        approvedAdapterIds,
        AnalysisControl(),
    )

    override fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
        control: AnalysisControl,
    ): PreparedConversionSnapshot {
        var sourceKind = kind.name
        val prepared = if (kind == OnDeviceInputKind.SIX_DRAFT) {
            val controlled = SixDraft.readControlled(input.toPath(), control)
            if (controlled is ControlledSixDraft.BudgetExceeded) {
                throw AnalysisBudgetExceededException(controlled.exceeded)
            }
            if (controlled is ControlledSixDraft.Prepared) {
                return preparedSnapshot(controlled.verified, profile, approvedAdapterIds, control)
            }
            val draft = (controlled as ControlledSixDraft.Legacy).verified
            sourceKind = draft.payload.input.inputKind.name
            if (draft.payload.input.browserProfileSha256 == profile.canonicalSha256()) {
                when (
                    val replayed = SixDraftReplay().replay(
                        draft,
                        profile,
                        approvedAdapterIds,
                        control,
                    )
                ) {
                    is DraftReplayResult.Ready -> replayed.prepared
                    is DraftReplayResult.ProfileChanged -> error("profile proof race")
                }
            } else {
                ConversionPreparationEngine().use {
                    it.prepare(
                        portableDraft(draft),
                        profile,
                        approvedAdapterIds,
                        control,
                    )
                }
            }
        } else {
            ConversionPreparationEngine().use {
                it.prepare(
                    portable(input, kind, control),
                    profile,
                    approvedAdapterIds,
                    control,
                )
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
        if (prepared.identityMode == InstallationIdentityMode.PREPARED) {
            val verified = prepared.opaque as VerifiedPreparedSixDraft
            require(
                verified.input.browserProfileSha256 == profile.canonicalSha256(),
            ) {
                "prepared draft profile proof가 현재 profile과 다릅니다."
            }
            require(
                identity.extensionId == verified.preparedCrx.proof.extensionId &&
                    identity.crxKeyAlias == null &&
                    identity.crxPublicKeyDer == null,
            ) {
                "prepared CRX proof와 package identity가 다릅니다."
            }
            return BoundConversionSnapshot(
                sourceZip = ByteArray(0),
                passthroughCrx = null,
                compatibility = SixBundle.toContractV4(
                    verified.compatibilityAfter,
                    verified.expected.originalSourceSha256,
                    verified.expected.planSha256,
                ),
                extensionId = identity.extensionId,
                sourceKind = verified.input.inputKind.name,
                preparedCrx = verified.preparedCrx,
                control = prepared.control,
            )
        }
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

    private fun preparedSnapshot(
        verified: VerifiedPreparedSixDraft,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
        control: AnalysisControl,
    ): PreparedConversionSnapshot {
        require(verified.input.browserProfileSha256 == profile.canonicalSha256()) {
            "prepared draft profile proof가 현재 profile과 다릅니다."
        }
        require(
            approvedAdapterIds ==
                verified.plan.selectedAdapters.mapTo(sortedSetOf()) { it.id },
        ) {
            "prepared draft approvals가 signed plan과 다릅니다."
        }
        return PreparedConversionSnapshot(
            reportBefore = verified.compatibilityBefore,
            originalSourceSha256 = verified.expected.originalSourceSha256,
            planSha256 = verified.expected.planSha256,
            transformedSourceZip = ByteArray(0),
            passthroughCrx = null,
            logicalSourceIdentity = logicalIdentity(verified.draft.sourceIdentity),
            identityMode = InstallationIdentityMode.PREPARED,
            descriptorName = requireNotNull(verified.compatibilityAfter.extensionName),
            descriptorVersion = requireNotNull(
                verified.compatibilityAfter.extensionVersion,
            ),
            sourceKind = verified.input.inputKind.name,
            opaque = verified,
            preparedCrx = verified.preparedCrx,
            control = control,
        )
    }

    private fun portable(
        file: File,
        kind: OnDeviceInputKind,
        control: AnalysisControl,
    ): PortableExtensionInput {
        val archiveKind = when (kind) {
            OnDeviceInputKind.ZIP -> AnalysisArchiveKind.ZIP
            OnDeviceInputKind.CRX3,
            OnDeviceInputKind.CHROME_WEB_STORE,
            -> AnalysisArchiveKind.CRX3
            OnDeviceInputKind.SIX_DRAFT ->
                error("draft는 replay 경로를 사용해야 합니다.")
        }
        val bytes = AnalysisInputMaterializer.readArchive(
            file.toPath(),
            archiveKind,
            control,
        ) {
            archivePayloadReader(it.toFile())
        }
        return when (kind) {
            OnDeviceInputKind.ZIP -> PortableExtensionInput.fromZip(
                ExtensionInputKind.ZIP,
                bytes,
            )
            OnDeviceInputKind.CRX3 -> PortableExtensionInput.fromCrx(
                ExtensionInputKind.CRX3,
                bytes,
            )
            OnDeviceInputKind.CHROME_WEB_STORE -> PortableExtensionInput.fromCrx(
                ExtensionInputKind.CHROME_WEB_STORE,
                bytes,
            )
            OnDeviceInputKind.SIX_DRAFT -> error("draft는 replay 경로를 사용해야 합니다.")
        }
    }

    private fun inputKind(kind: OnDeviceInputKind): ExtensionInputKind = when (kind) {
        OnDeviceInputKind.ZIP -> ExtensionInputKind.ZIP
        OnDeviceInputKind.CRX3 -> ExtensionInputKind.CRX3
        OnDeviceInputKind.CHROME_WEB_STORE -> ExtensionInputKind.CHROME_WEB_STORE
        OnDeviceInputKind.SIX_DRAFT -> error("draft input kind는 signed metadata가 필요합니다.")
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
            dev.sixbridge.core.draft.DraftSourceKind.PREPARED_CRX ->
                error("prepared CRX는 replay portable source가 아닙니다.")
        }
    }

    private fun snapshot(
        report: dev.sixbridge.core.compat.CompatibilityReport,
        logicalIdentity: LogicalSourceIdentity?,
        inputKind: ExtensionInputKind,
        profileChanged: Boolean = false,
        identityMode: InstallationIdentityMode? = null,
    ) = ConversionAnalysisSnapshot(
        report = report,
        selectedAutomaticAdapters = report.adapterDecisions
            .filter { it.disposition == AdapterDisposition.AUTOMATIC }
            .mapTo(sortedSetOf()) { it.id },
        requiredApprovals = report.adapterDecisions
            .filter { it.disposition == AdapterDisposition.REQUIRES_APPROVAL }
            .mapTo(sortedSetOf()) { it.id },
        logicalSourceIdentity = logicalIdentity,
        identityMode = identityMode
            ?: installationIdentityMode(inputKind, report.conversionState),
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
        when (prepared.identityMode) {
            InstallationIdentityMode.PASSTHROUGH ->
                require(prepared.passthroughCrx != null && prepared.preparedCrx == null)
            InstallationIdentityMode.PREPARED ->
                require(prepared.passthroughCrx == null && prepared.preparedCrx != null)
            InstallationIdentityMode.CONVERTED ->
                require(prepared.passthroughCrx == null && prepared.preparedCrx == null)
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
        if (prepared.identityMode == InstallationIdentityMode.PREPARED) {
            return ResolvedPackageIdentity(
                key,
                packager.resolveIdentityFromProof(
                    key,
                    requireNotNull(prepared.preparedCrx).proof,
                ),
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
        val source = bound.preparedCrx?.let { artifact ->
            packager.stageSource(token, artifact.length) {
                artifact.copyTo(it, requireNotNull(bound.control))
            }
        } ?: packager.stageSource(
            token,
            bound.passthroughCrx ?: bound.sourceZip,
        )
        val isCrxSource =
            bound.passthroughCrx != null || bound.preparedCrx != null
        try {
            val result = packager.packageWrapper(
                ledger,
                OnDeviceWrapperPathRequest(
                    reservation = reservation.opaque as ConversionIdentityLedger.Reservation,
                    canonicalSourceZip = source.takeIf { !isCrxSource },
                    passthroughCrx = source.takeIf { isCrxSource },
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
    override suspend fun createShareSnapshot(token: String): File? =
        store.createShareSnapshot(token)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
