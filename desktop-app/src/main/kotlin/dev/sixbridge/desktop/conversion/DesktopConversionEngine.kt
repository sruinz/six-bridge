package dev.sixbridge.desktop.conversion

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.draft.DraftSignature
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.desktop.DesktopApplicationPaths
import dev.sixbridge.desktop.DesktopHostOs
import dev.sixbridge.desktop.input.DesktopChromeCrxDownloader
import dev.sixbridge.desktop.input.DesktopInputSealer
import dev.sixbridge.desktop.security.DesktopDraftSigningIdentityStore
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

class DesktopConversionEngine private constructor(
    private val workspaceManager: DesktopWorkspaceManager,
    private val inputSealer: DesktopInputSealer,
    private val signingIdentityProvider: DraftSigningIdentityProvider,
) : AutoCloseable {
    constructor() : this(
        workspaceManager = DesktopWorkspaceManager(),
        inputSealer = DesktopInputSealer(DesktopChromeCrxDownloader()),
        signingIdentityProvider = desktopSigningIdentityProvider(),
    )

    private data class DesktopSessionContext(
        val lease: DesktopWorkspaceLease,
        val resolvedInput: ResolvedExtensionInput,
        val service: SixBridgeService,
    )

    private val contexts = ConcurrentHashMap<String, DesktopSessionContext>()
    private val sessions = ConcurrentHashMap<String, DesktopAnalysisSession>()

    fun analyze(
        input: DesktopExtensionInput,
        profile: BrowserProfile,
        control: AnalysisControl,
    ): DesktopAnalysisSession {
        val lease = workspaceManager.create()
        try {
            val resolvedInput = inputSealer.seal(input, lease.workspace, control)
            val service = SixBridgeService(
                workspaceRoot = lease.workspace.root,
                secureConversionRoot = lease.secureRoot,
                draftSigningIdentityProvider = signingIdentityProvider,
            )
            val analysis = service.analyzeResolvedDraft(resolvedInput, profile, control)
            val token = canonicalToken(lease.workspace.token)
            val session = DesktopAnalysisSession(
                token = token,
                input = input,
                profile = profile,
                report = analysis.report,
                logicalSourceIdentity = analysis.logicalSourceIdentity,
                selectedAutomaticAdapters = analysis.report.adapterDecisions
                    .filter { it.disposition == AdapterDisposition.AUTOMATIC }
                    .mapTo(sortedSetOf()) { it.id },
                requiredApprovals = analysis.report.adapterDecisions
                    .filter { it.disposition == AdapterDisposition.REQUIRES_APPROVAL }
                    .mapTo(sortedSetOf()) { it.id },
            )
            val context = DesktopSessionContext(lease, resolvedInput, service)
            check(contexts.putIfAbsent(token, context) == null) {
                "desktop analysis token이 중복되었습니다."
            }
            check(sessions.putIfAbsent(token, session) == null) {
                "desktop analysis session이 중복되었습니다."
            }
            return session
        } catch (error: Throwable) {
            closeUnregistered(lease, error)
        }
    }

    fun buildDraft(
        session: DesktopAnalysisSession,
        approvedAdapterIds: Set<String>,
        projectIdentity: String?,
        control: AnalysisControl,
    ): DesktopDraftArtifact {
        val token = try {
            canonicalToken(session.token)
        } catch (error: IllegalArgumentException) {
            throw DesktopConversionException("desktop analysis token이 올바르지 않습니다.", error)
        }
        val context = contexts[token]
            ?: throw DesktopConversionException("활성 desktop analysis session이 없습니다.")
        try {
            validateSession(token, session)
            validateBuildRequest(session, approvedAdapterIds)
            val suggestedFileName = suggestedFileName(session.report.extensionName)
            val output = context.lease.workspace.artifactRoot.resolve(suggestedFileName)
            val result = context.service.draftPortableResolved(
                resolved = context.resolvedInput,
                profile = session.profile,
                outputDraft = output,
                projectIdentity = projectIdentity,
                approvedAdapterIds = approvedAdapterIds,
                protectedInputs = emptyList(),
                control = control,
            )
            val verified = SixDraft.read(result.draft)
            val signerFingerprint = DraftSignature.fingerprint(
                signingIdentityProvider.getOrCreate().public.encoded,
            )
            verifyArtifact(session, result.sourceSha256, result.draft, verified, signerFingerprint)
            return DesktopDraftArtifact(
                token = token,
                privateFile = result.draft,
                suggestedFileName = suggestedFileName,
                sha256 = DraftSignature.sha256(Files.readAllBytes(result.draft)),
                signerFingerprint = signerFingerprint,
            )
        } catch (error: Throwable) {
            closeRegistered(token, context, error)
            throw error
        }
    }

    fun release(token: String): Boolean {
        val canonical = try {
            canonicalToken(token)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val context = contexts[canonical] ?: return false
        closeRegistered(canonical, context, null)
        return true
    }

    internal fun resolvedInputForTesting(
        session: DesktopAnalysisSession,
    ): ResolvedExtensionInput {
        val token = canonicalToken(session.token)
        validateSession(token, session)
        return requireNotNull(contexts[token]).resolvedInput
    }

    override fun close() {
        var failure: Throwable? = null
        contexts.entries.forEach { (token, context) ->
            try {
                closeRegistered(token, context, null)
            } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw DesktopConversionException("desktop session 정리에 실패했습니다.", it) }
    }

    private fun validateSession(token: String, session: DesktopAnalysisSession) {
        if (sessions[token] !== session) {
            throw DesktopConversionException(
                "desktop analysis session이 위조되었거나 이미 해제되었습니다.",
            )
        }
    }

    private fun validateBuildRequest(
        session: DesktopAnalysisSession,
        approvedAdapterIds: Set<String>,
    ) {
        if (session.report.status in setOf(CompatibilityStatus.BLOCKED, CompatibilityStatus.UNKNOWN)) {
            throw DesktopConversionException("차단되었거나 알 수 없는 분석 결과는 draft로 만들 수 없습니다.")
        }
        if (!approvedAdapterIds.containsAll(session.requiredApprovals)) {
            throw DesktopConversionException("필수 승인 adapter가 누락되었습니다.")
        }
        if (!session.requiredApprovals.containsAll(approvedAdapterIds)) {
            throw DesktopConversionException("분석 결과에 없는 adapter 승인이 포함되었습니다.")
        }
    }

    private fun verifyArtifact(
        session: DesktopAnalysisSession,
        resultSourceSha256: String,
        artifact: java.nio.file.Path,
        verified: dev.sixbridge.core.draft.VerifiedSixDraft,
        signerFingerprint: String,
    ) {
        val payload = verified.payload
        if (
            resultSourceSha256 != session.report.sourceSha256 ||
            payload.draft.schemaVersion != 2 ||
            payload.sourceKind !in setOf(DraftSourceKind.ZIP, DraftSourceKind.CRX) ||
            verified.signerFingerprint != signerFingerprint ||
            !Files.isRegularFile(artifact)
        ) {
            throw DesktopConversionException("생성된 portable SixDraft 검증에 실패했습니다.")
        }
    }

    private fun closeUnregistered(lease: DesktopWorkspaceLease, error: Throwable): Nothing {
        try {
            workspaceManager.cleanup(lease.workspace.token)
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }

    private fun closeRegistered(
        token: String,
        context: DesktopSessionContext,
        primary: Throwable?,
    ) {
        if (!contexts.remove(token, context)) return
        sessions.remove(token)
        try {
            check(workspaceManager.cleanup(context.lease.workspace.token)) {
                "desktop workspace lease가 등록 중 사라졌습니다."
            }
        } catch (cleanupError: Throwable) {
            if (primary != null) {
                primary.addSuppressed(cleanupError)
            } else {
                throw cleanupError
            }
        }
        if (primary != null) throw primary
    }

    private fun canonicalToken(token: String): String {
        val uuid = UUID.fromString(token)
        require(uuid.toString() == token) { "token은 canonical UUID여야 합니다." }
        return token
    }

    private fun suggestedFileName(extensionName: String?): String {
        val stem = (extensionName?.ifBlank { null } ?: "sixbridge-draft")
            .map { character ->
                if (character.code < 0x20 || character in INVALID_FILE_NAME_CHARACTERS) '_' else character
            }
            .joinToString("")
            .take(MAX_STEM_CODE_UNITS)
        return "$stem.sixdraft"
    }

    companion object {
        const val MAX_FILE_NAME_CODE_UNITS = 120
        const val DRAFT_EXTENSION = ".sixdraft"
        const val MAX_STEM_CODE_UNITS = MAX_FILE_NAME_CODE_UNITS - DRAFT_EXTENSION.length
        val INVALID_FILE_NAME_CHARACTERS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun desktopSigningIdentityProvider(): DraftSigningIdentityProvider =
            DraftSigningIdentityProvider {
                val hostOs = when {
                    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> DesktopHostOs.MAC
                    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> DesktopHostOs.WINDOWS
                    else -> throw IllegalStateException("지원하지 않는 데스크톱 운영체제입니다.")
                }
                DesktopDraftSigningIdentityStore(
                    DesktopApplicationPaths.resolve(),
                    hostOs,
                ).getOrCreate()
            }

        internal fun testing(
            workspaceManager: DesktopWorkspaceManager,
            inputSealer: DesktopInputSealer,
            signingIdentityProvider: DraftSigningIdentityProvider,
        ): DesktopConversionEngine = DesktopConversionEngine(
            workspaceManager,
            inputSealer,
            signingIdentityProvider,
        )
    }
}
