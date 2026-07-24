package dev.sixbridge.core.conversion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmSynthetic

internal data class LocalSourcePublication(
    val source: Path,
    val destination: Path,
    val trustedRoot: Path = requireNotNull(destination.toAbsolutePath().normalize().parent),
)

internal enum class BuildCrashPoint {
    AFTER_OUTPUT_BACKUP,
    AFTER_PARTIAL_OUTPUT,
    AFTER_OUTPUTS_BEFORE_LOCAL,
    AFTER_LOCAL_BEFORE_COMMIT,
}

internal enum class LocalStageCrashPoint {
    AFTER_DIRECTORY_CREATE,
    AFTER_OWNER_MARKER,
    AFTER_CREATED_JOURNAL,
    AFTER_COPY,
    AFTER_OWNER_MARKER_REMOVAL,
    AFTER_TREE_FSYNC,
    AFTER_PROVENANCE_VERIFY,
}

internal enum class OutputStageCrashPoint {
    AFTER_DIRECTORY_CREATE,
    AFTER_OWNER_MARKER,
}

internal object SecureBuildPublisher {
    @JvmSynthetic
    fun copyExistingOutput(
        source: Path,
        destination: Path,
    ): Boolean {
        val normalized = source.toAbsolutePath().normalize()
        val parent = normalized.parent ?: return false
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parent)
        ) {
            return false
        }
        val bytes = secureDirectory(parent).use { handle ->
            validateDirectoryHandle(parent, handle)
            val name = singleName(normalized.fileName.toString())
            val before = targetState(handle, name, normalized) ?: return false
            val copied = handle.newByteChannel(
                name,
                setOf(READ, NOFOLLOW_LINKS),
            ).use { channel -> channel.readAllBytes() }
            requireSameState(
                before,
                targetState(handle, name, normalized),
                "기존 출력이 읽는 동안 변경되었습니다: $normalized",
            )
            copied
        }
        Files.write(destination, bytes)
        return true
    }

    @JvmSynthetic
    fun publish(
        outputs: Map<Path, ByteArray>,
        localSource: LocalSourcePublication? = null,
    ) {
        require(outputs.isNotEmpty()) {
            "publish할 산출물이 없습니다."
        }
        val normalized = outputs.entries.map { (path, bytes) ->
            path.toAbsolutePath().normalize() to bytes.copyOf()
        }
        require(normalized.map(Pair<Path, ByteArray>::first).toSet().size == normalized.size) {
            "정규화된 출력 경로가 서로 겹칩니다."
        }
        normalized.forEach { (path, _) ->
            requireAllowedOutputName(path)
        }
        val parent = normalized.map { requireNotNull(it.first.parent) }.distinct()
            .singleOrNull()
            ?: throw IllegalArgumentException("모든 출력은 같은 디렉터리에 있어야 합니다.")
        Files.createDirectories(parent)
        withPublicationLock(parent) {
            recoverPendingLocked(parent)
        val outputTransaction = OutputTransaction(parent, normalized)
        val localTransaction = localSource?.let(::LocalSourceTransaction)
        val journal = PublicationJournal(parent)
        try {
            journal.write(JournalRecord.intent(outputTransaction, localTransaction))
            BuildPublicationFaultInjection.afterIntentBeforeStage()
            outputTransaction.stage {
                journal.write(
                    JournalRecord.outputStageCreated(
                        outputTransaction,
                        localTransaction,
                    ),
                )
            }
            journal.write(
                JournalRecord.outputStageReady(
                    outputTransaction,
                    localTransaction,
                ),
            )
            localTransaction?.stage {
                journal.write(
                    JournalRecord.create(
                        JournalPhase.LOCAL_STAGE_CREATED,
                        0,
                        outputTransaction,
                        localTransaction,
                    ),
                )
            }
            journal.write(
                JournalRecord.create(
                    JournalPhase.STAGES_READY,
                    0,
                    outputTransaction,
                    localTransaction,
                ),
            )
            BuildPublicationFaultInjection.afterStageBeforeStagedJournal()
            journal.write(
                JournalRecord.create(
                    JournalPhase.STAGED,
                    0,
                    outputTransaction,
                    localTransaction,
                ),
            )
            BuildPublicationFaultInjection.afterStagedJournal()
            outputTransaction.removeIntentMarker()
            localTransaction?.removeIntentMarker()
            outputTransaction.publish(
                afterBackup = {
                    journal.write(
                        JournalRecord.create(
                            JournalPhase.OUTPUT_BACKED_UP,
                            0,
                            outputTransaction,
                            localTransaction,
                        ),
                    )
                    BuildPublicationFaultInjection.crashAt(
                        BuildCrashPoint.AFTER_OUTPUT_BACKUP,
                    )
                },
                afterPublished = { count ->
                    journal.write(
                        JournalRecord.create(
                            JournalPhase.OUTPUT_PARTIAL,
                            count,
                            outputTransaction,
                            localTransaction,
                        ),
                    )
                    if (count == 1) {
                        BuildPublicationFaultInjection.crashAt(
                            BuildCrashPoint.AFTER_PARTIAL_OUTPUT,
                        )
                    }
                },
            )
            journal.write(
                JournalRecord.create(
                    JournalPhase.OUTPUTS_PUBLISHED,
                    outputTransaction.targetCount,
                    outputTransaction,
                    localTransaction,
                ),
            )
            BuildPublicationFaultInjection.crashAt(
                BuildCrashPoint.AFTER_OUTPUTS_BEFORE_LOCAL,
            )
            localTransaction?.publish()
            journal.write(
                JournalRecord.create(
                    JournalPhase.LOCAL_PUBLISHED,
                    outputTransaction.targetCount,
                    outputTransaction,
                    localTransaction,
                ),
            )
            BuildPublicationFaultInjection.afterLocalPublish()
            BuildPublicationFaultInjection.crashAt(
                BuildCrashPoint.AFTER_LOCAL_BEFORE_COMMIT,
            )
            journal.write(
                JournalRecord.create(
                    JournalPhase.COMMIT_READY,
                    outputTransaction.targetCount,
                    outputTransaction,
                    localTransaction,
                ),
            )
            try {
                BuildPublicationFaultInjection.beforeCommitCleanup()
                localTransaction?.commit()
                outputTransaction.commit()
                journal.delete()
            } catch (_: Exception) {
                outputTransaction.abandonForCrash()
                localTransaction?.abandonForCrash()
                return@withPublicationLock
            }
        } catch (error: Throwable) {
            if (error is ForcedBuildTermination) {
                localTransaction?.abandonForCrash()
                outputTransaction.abandonForCrash()
            } else {
                val localRolledBack = localTransaction?.rollback(error) ?: true
                val outputsRolledBack = outputTransaction.rollback(error)
                if (localRolledBack && outputsRolledBack) {
                    outputTransaction.removeIntentMarkerIfPresent()
                    localTransaction?.removeIntentMarkerIfPresent()
                    runCatching { journal.delete() }.exceptionOrNull()
                        ?.let(error::addSuppressed)
                } else {
                    localTransaction?.abandonForCrash()
                    outputTransaction.abandonForCrash()
                }
            }
            throw error
        } finally {
            localTransaction?.finish()
            outputTransaction.finish()
        }
        }
    }

    @JvmSynthetic
    internal fun recoverPending(
        outputs: Collection<Path>,
        localSource: LocalSourcePublication?,
    ) {
        if (outputs.isEmpty()) return
        val normalized = outputs.map { it.toAbsolutePath().normalize() }
        val parent = normalized.map { requireNotNull(it.parent) }.distinct().single()
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return
        withPublicationLock(parent) {
            recoverPendingLocked(parent)
        }
    }

    private fun recoverPendingLocked(parent: Path) {
        val journal = PublicationJournal(parent)
        val record = journal.read()
        if (record == null) {
            journal.delete()
            return
        }
        when (record.phase) {
            JournalPhase.INTENT,
            JournalPhase.OUTPUT_STAGE_CREATED,
            -> recoverIntent(record, parent)

            JournalPhase.OUTPUT_STAGE_READY -> {
                recoverOld(record, parent)
                recoverLocalIntent(record.localIntent, null)
            }

            JournalPhase.LOCAL_STAGE_CREATED -> {
                recoverOld(record.copy(local = null), parent)
                recoverLocalIntent(
                    requireNotNull(record.localIntent),
                    requireNotNull(record.local).stageIdentity,
                )
            }

            JournalPhase.COMMIT_READY -> recoverCommitted(record, parent)
            else -> recoverOld(record, parent)
        }
        removeIntentMarkerIfPresent(parent, UUID.fromString(record.outputIntentToken))
        if (record.localIntent != null) {
            val intent = record.localIntent
            val localParent = Path.of(intent.parent).toAbsolutePath().normalize()
            validateTrustedLocalPath(Path.of(intent.trustedRoot), localParent)
            removeIntentMarkerIfPresent(localParent, UUID.fromString(intent.token))
        }
        journal.delete()
    }

    private fun recoverIntent(record: JournalRecord, outputParent: Path) {
        deleteIntentOwnedStage(
            outputParent,
            record.outputStageName,
            record.outputIntentToken,
            record.outputStageIdentity,
        )
    }

    private fun recoverLocalIntent(
        intent: JournalLocalIntent?,
        stageIdentity: String?,
    ) {
        intent ?: return
        val parent = Path.of(intent.parent).toAbsolutePath().normalize()
        validateTrustedLocalPath(Path.of(intent.trustedRoot), parent)
        deleteIntentOwnedStage(
            parent,
            intent.stageName,
            intent.token,
            stageIdentity,
        )
    }

    private class OutputTransaction(
        private val parent: Path,
        outputs: List<Pair<Path, ByteArray>>,
    ) {
        private val targets = outputs.mapIndexed { index, (path, bytes) ->
            OutputTarget(
                index = index,
                path = path,
                name = singleName(path.fileName.toString()),
                bytes = bytes,
            )
        }
        private var owner: OwnedTempDirectory? = null
        private val stagePath: Path =
            parent.resolve(".sixbridge-publish-${UUID.randomUUID()}")
        private val intentToken: UUID = UUID.randomUUID()
        private var intentMarkerIdentity: StableFileIdentity? = null
        private var intentMarkerOwnershipUncertain = false
        private var stageRootKey: String? = null
        private var stageIdentity: StableFileIdentity? = null
        private var published = false
        private var committed = false
        val targetCount: Int
            get() = targets.size

        fun stage(afterCreated: () -> Unit) {
            secureDirectory(parent).use { parentHandle ->
                validateDirectoryHandle(parent, parentHandle)
                targets.forEach { target ->
                    target.original = targetState(
                        parentHandle,
                        target.name,
                        target.path,
                    )
                    target.originalSha256 = if (target.original == null) {
                        null
                    } else {
                        sha256(
                            parentHandle.newByteChannel(
                                target.name,
                                setOf(READ, NOFOLLOW_LINKS),
                            ).use { it.readAllBytes() },
                        )
                    }
                }
            }
            val path = stagePath
            secureDirectory(parent).use { parentHandle ->
                try {
                    writeIntentMarker(parent, parentHandle, intentToken) {
                        intentMarkerIdentity = it
                    }
                } catch (failure: Throwable) {
                    if (intentMarkerIdentity == null) {
                        intentMarkerOwnershipUncertain = true
                    }
                    throw failure
                }
            }
            val created = OwnedTempTree.createAt(path)
            owner = created
            BuildPublicationFaultInjection.crashOutputStageAt(
                OutputStageCrashPoint.AFTER_DIRECTORY_CREATE,
            )
            secureDirectory(path).use { stageHandle ->
                validateDirectoryHandle(path, stageHandle)
                writeStageOwnerMarker(path, stageHandle, intentToken)
                stageRootKey = requireNotNull(
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        NOFOLLOW_LINKS,
                    ).fileKey(),
                ).toString()
                stageIdentity = stableFileIdentity(
                    path,
                    requireNotNull(
                        Files.readAttributes(
                            path,
                            BasicFileAttributes::class.java,
                            NOFOLLOW_LINKS,
                        ).fileKey(),
                    ),
                )
                forceDirectory(parent)
                BuildPublicationFaultInjection.crashOutputStageAt(
                    OutputStageCrashPoint.AFTER_OWNER_MARKER,
                )
                afterCreated()
                targets.forEach { target ->
                    val name = singleName("new-${target.index}")
                    writeNew(stageHandle, name, target.bytes)
                    target.stagedName = name
                    target.staged = requireNotNull(
                        targetState(
                            stageHandle,
                            name,
                            path.resolve(name.toString()),
                        ),
                    )
                }
            }
            forceDirectory(path)
        }

        fun publish(
            afterBackup: () -> Unit,
            afterPublished: (Int) -> Unit,
        ) {
            val path = stagePath
            secureDirectory(parent).use { parentHandle ->
                secureDirectory(path).use { stageHandle ->
                    validateDirectoryHandle(parent, parentHandle)
                    validateDirectoryHandle(path, stageHandle)
                    targets.forEach { target ->
                        BuildPublicationFaultInjection.beforeOutputPublish(target.path)
                        requireSameState(
                            target.original,
                            targetState(parentHandle, target.name, target.path),
                            "출력 경로가 publish 직전에 변경되었습니다: ${target.path}",
                        )
                    }
                    targets.filter { it.original != null }.forEach { target ->
                        val backup = singleName("backup-${target.index}")
                        parentHandle.move(target.name, stageHandle, backup)
                        target.backupName = backup
                        requireSameState(
                            target.original,
                            targetState(
                                stageHandle,
                                backup,
                                path.resolve(backup.toString()),
                            ),
                            "출력 backup identity가 바뀌었습니다.",
                        )
                    }
                    forceDirectory(parent)
                    forceDirectory(path)
                    afterBackup()
                    var publishedCount = 0
                    targets.forEach { target ->
                        val stagedName = requireNotNull(target.stagedName)
                        requireSameState(
                            target.staged,
                            targetState(
                                stageHandle,
                                stagedName,
                                path.resolve(stagedName.toString()),
                            ),
                            "staged output이 publish 전에 변경되었습니다.",
                        )
                        stageHandle.move(stagedName, parentHandle, target.name)
                        target.wasPublished = true
                        requireSameState(
                            target.staged,
                            targetState(parentHandle, target.name, target.path),
                            "published output identity가 staged output과 다릅니다.",
                        )
                        forceDirectory(parent)
                        forceDirectory(path)
                        publishedCount += 1
                        afterPublished(publishedCount)
                    }
                    published = true
                }
            }
        }

        fun rollback(failure: Throwable): Boolean {
            if (intentMarkerOwnershipUncertain) {
                failure.addSuppressed(
                    IllegalStateException(
                        "output intent marker 소유권을 증명할 수 없어 journal을 보존합니다.",
                    ),
                )
                return false
            }
            if (committed || owner == null) return true
            if (BuildPublicationFaultInjection.shouldFailOutputRollback()) {
                failure.addSuppressed(
                    IllegalStateException("injected output rollback failure"),
                )
                return false
            }
            val path = stagePath
            val rollbackFailure = runCatching {
                secureDirectory(parent).use { parentHandle ->
                    secureDirectory(path).use { stageHandle ->
                        targets.asReversed().filter(OutputTarget::wasPublished)
                            .forEach { target ->
                                requireSameState(
                                    target.staged,
                                    targetState(parentHandle, target.name, target.path),
                                    "rollback 대상 output이 publish 후 변경되었습니다.",
                                )
                                parentHandle.deleteFile(target.name)
                                target.wasPublished = false
                            }
                        targets.asReversed().forEach { target ->
                            target.backupName?.let { backup ->
                                requireSameState(
                                    target.original,
                                    targetState(
                                        stageHandle,
                                        backup,
                                        path.resolve(backup.toString()),
                                    ),
                                    "rollback backup이 변경되었습니다.",
                                )
                                stageHandle.move(backup, parentHandle, target.name)
                                target.backupName = null
                            }
                        }
                    }
                }
                forceDirectory(parent)
                forceDirectory(path)
                published = false
            }.exceptionOrNull()
            rollbackFailure?.let(failure::addSuppressed)
            return rollbackFailure == null
        }

        fun commit() {
            check(published)
            val path = stagePath
            secureDirectory(path).use { stageHandle ->
                targets.forEach { target ->
                    target.backupName?.let { backup ->
                        stageHandle.deleteFile(backup)
                        target.backupName = null
                    }
                }
            }
            committed = true
            val retainedOwner = requireNotNull(owner)
            OwnedTempTree.delete(retainedOwner)
            owner = null
            forceDirectory(parent)
        }

        fun finish() {
            val retainedOwner = owner ?: return
            runCatching { OwnedTempTree.delete(retainedOwner) }
            owner = null
        }

        fun abandonForCrash() {
            val retainedOwner = owner ?: return
            OwnedTempTree.abandonNoDelete(retainedOwner)
            owner = null
        }

        fun journalStageName(): String =
            stagePath.fileName.toString()

        fun journalStageRootKey(): String = requireNotNull(stageRootKey)

        fun journalStageIdentity(): String =
            requireNotNull(stageIdentity).encode()

        fun journalIntentToken(): String = intentToken.toString()

        fun removeIntentMarker() {
            removeIntentMarker(
                parent,
                intentToken,
                requireNotNull(intentMarkerIdentity),
            )
            intentMarkerIdentity = null
        }

        fun removeIntentMarkerIfPresent() {
            intentMarkerIdentity?.let { expected ->
                removeIntentMarkerIfPresent(parent, intentToken, expected)
                intentMarkerIdentity = null
            }
        }

        fun journalTargets(): List<JournalOutput> = targets.map { target ->
            JournalOutput(
                name = target.name.toString(),
                oldExists = target.original != null,
                oldSha256 = target.originalSha256,
                newSha256 = target.newSha256,
                oldIdentity = target.original?.identity?.encode(),
                newIdentity = requireNotNull(target.staged).identity.encode(),
            )
        }

        fun journalIntentTargets(): List<JournalOutput> = targets.map { target ->
            JournalOutput(
                name = target.name.toString(),
                oldExists = false,
                oldSha256 = null,
                newSha256 = target.newSha256,
                oldIdentity = null,
                newIdentity = null,
            )
        }
    }

    private class LocalSourceTransaction(
        request: LocalSourcePublication,
    ) {
        private val source = request.source.toAbsolutePath().normalize()
        private val destination = request.destination.toAbsolutePath().normalize()
        private val trustedRoot = request.trustedRoot.toAbsolutePath().normalize()
        private val parent = requireNotNull(destination.parent)
        private val destinationName = singleName(destination.fileName.toString())
        private val stageOriginal = parent.resolve(
            ".sixbridge-local-stage-${UUID.randomUUID()}",
        )
        private val intentToken: UUID = UUID.randomUUID()
        private val backupName = singleName(
            ".sixbridge-local-backup-${UUID.randomUUID()}",
        )
        private var owner: OwnedTempDirectory? = null
        private var sourceState: WorkspaceTreeDigest.State? = null
        private var originalState: WorkspaceTreeDigest.State? = null
        private var originalRootIdentity: Any? = null
        private var backupExists = false
        private var published = false
        private var committed = false
        private var stageRootKey: String? = null
        private var stageIdentity: StableFileIdentity? = null
        private var durabilityFailed = false
        private var intentMarkerIdentity: StableFileIdentity? = null
        private var intentMarkerOwnershipUncertain = false

        fun stage(afterCreated: () -> Unit) {
            require(source != destination) {
                "local source와 저장 대상이 같을 수 없습니다."
            }
            validateTreeNoLinks(source)
            sourceState = WorkspaceTreeDigest.capture(source)
            validateTrustedLocalPath(trustedRoot, parent)
            Files.createDirectories(parent)
            secureDirectory(parent).use { parentHandle ->
                validateDirectoryHandle(parent, parentHandle)
                attributesOrNull(parentHandle, destinationName)?.let { attributes ->
                    check(attributes.isDirectory && !attributes.isSymbolicLink) {
                        "기존 local source 대상은 일반 디렉터리여야 합니다."
                    }
                    validateTreeNoLinks(destination)
                    originalState = WorkspaceTreeDigest.capture(destination)
                    originalRootIdentity = requireNotNull(attributes.fileKey())
                }
            }
            secureDirectory(parent).use { parentHandle ->
                try {
                    writeIntentMarker(parent, parentHandle, intentToken) {
                        intentMarkerIdentity = it
                    }
                } catch (failure: Throwable) {
                    if (intentMarkerIdentity == null) {
                        intentMarkerOwnershipUncertain = true
                    }
                    throw failure
                }
            }
            val created = OwnedTempTree.createAt(stageOriginal)
            owner = created
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_DIRECTORY_CREATE,
            )
            secureDirectory(stageOriginal).use { stageHandle ->
                writeStageOwnerMarker(stageOriginal, stageHandle, intentToken)
            }
            val stageAttributes = Files.readAttributes(
                stageOriginal,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            stageRootKey = requireNotNull(stageAttributes.fileKey()).toString()
            stageIdentity = stableFileIdentity(
                stageOriginal,
                requireNotNull(stageAttributes.fileKey()),
            )
            forceDirectory(parent)
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_OWNER_MARKER,
            )
            afterCreated()
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_CREATED_JOURNAL,
            )
            removeStageOwnerMarker(stageOriginal, intentToken)
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_OWNER_MARKER_REMOVAL,
            )
            NativeWorkspaceTree.copyInto(source, stageOriginal)
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_COPY,
            )
            val stageStateBeforeFsync = WorkspaceTreeDigest.capture(stageOriginal)
            check(
                stageStateBeforeFsync.provenanceSha256 ==
                    requireNotNull(sourceState).provenanceSha256,
            ) {
                "local source stage가 fsync 전에 원본과 다릅니다."
            }
            try {
                forceTree(stageOriginal)
            } catch (failure: Throwable) {
                durabilityFailed = true
                throw failure
            }
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_TREE_FSYNC,
            )
            check(WorkspaceTreeDigest.capture(stageOriginal) == stageStateBeforeFsync) {
                "local source stage가 fsync 검증 중 변경되었습니다."
            }
            BuildPublicationFaultInjection.crashLocalStageAt(
                LocalStageCrashPoint.AFTER_PROVENANCE_VERIFY,
            )
        }

        fun publish() {
            secureDirectory(parent).use { parentHandle ->
                validateDirectoryHandle(parent, parentHandle)
                if (originalState != null) {
                    val current = requireNotNull(
                        attributesOrNull(parentHandle, destinationName),
                    )
                    check(
                        current.isDirectory &&
                            !current.isSymbolicLink &&
                            current.fileKey() == originalRootIdentity &&
                            WorkspaceTreeDigest.capture(destination) == originalState,
                    ) {
                        "local source 대상이 publish 전에 변경되었습니다."
                    }
                    parentHandle.move(destinationName, parentHandle, backupName)
                    backupExists = true
                    forceDirectory(parent)
                    val backupPath = parent.resolve(backupName.toString())
                    check(
                        WorkspaceTreeDigest.capture(backupPath) == originalState,
                    ) {
                        "local source backup이 원본과 다릅니다."
                    }
                } else {
                    check(attributesOrNull(parentHandle, destinationName) == null) {
                        "local source 대상이 publish 전에 생성되었습니다."
                    }
                }
            }
            PendingTempCleanup.move(stageOriginal, destination)
            forceDirectory(parent)
            published = true
            validateTreeNoLinks(destination)
            check(
                WorkspaceTreeDigest.capture(destination).provenanceSha256 ==
                    requireNotNull(sourceState).provenanceSha256,
            ) {
                "published local source가 검증된 stage와 다릅니다."
            }
        }

        fun rollback(failure: Throwable): Boolean {
            if (intentMarkerOwnershipUncertain) {
                failure.addSuppressed(
                    IllegalStateException(
                        "local intent marker 소유권을 증명할 수 없어 journal을 보존합니다.",
                    ),
                )
                return false
            }
            if (committed || owner == null) return true
            if (durabilityFailed) {
                failure.addSuppressed(
                    IllegalStateException(
                        "local stage durability 실패로 journal 복구가 필요합니다.",
                    ),
                )
                return false
            }
            val rollbackFailure = runCatching {
                if (published) {
                    PendingTempCleanup.move(destination, stageOriginal)
                    published = false
                }
                if (backupExists) {
                    secureDirectory(parent).use { parentHandle ->
                        check(attributesOrNull(parentHandle, destinationName) == null)
                        parentHandle.move(backupName, parentHandle, destinationName)
                    }
                    backupExists = false
                    check(WorkspaceTreeDigest.capture(destination) == originalState) {
                        "local source rollback 결과가 원본과 다릅니다."
                    }
                }
                forceDirectory(parent)
            }.exceptionOrNull()
            rollbackFailure?.let(failure::addSuppressed)
            return rollbackFailure == null
        }

        fun commit() {
            check(published)
            if (backupExists) {
                secureDirectory(parent).use { parentHandle ->
                    deleteTree(parentHandle, backupName)
                }
                backupExists = false
                forceDirectory(parent)
            }
            val retainedOwner = requireNotNull(owner)
            OwnedTempTree.abandonNoDelete(
                OwnedTempDirectory.create(destination, retainedOwner.token),
            )
            owner = null
            committed = true
        }

        fun finish() {
            val retainedOwner = owner ?: return
            runCatching { OwnedTempTree.delete(retainedOwner) }
            owner = null
        }

        fun abandonForCrash() {
            val retainedOwner = owner ?: return
            val current = if (published) destination else stageOriginal
            OwnedTempTree.abandonNoDelete(
                OwnedTempDirectory.create(current, retainedOwner.token),
            )
            owner = null
        }

        fun journalLocal(): JournalLocal = JournalLocal(
            trustedRoot = trustedRoot.toString(),
            parent = parent.toString(),
            destinationName = destinationName.toString(),
            stageName = stageOriginal.fileName.toString(),
            backupName = backupName.toString(),
            oldExists = originalState != null,
            oldIdentitySha256 = originalState?.identitySha256,
            newProvenanceSha256 = requireNotNull(sourceState).provenanceSha256,
            stageRootKey = requireNotNull(stageRootKey),
            stageIdentity = requireNotNull(stageIdentity).encode(),
            oldRootKey = originalRootIdentity?.toString(),
        )

        fun journalIntent(): JournalLocalIntent = JournalLocalIntent(
            trustedRoot = trustedRoot.toString(),
            parent = parent.toString(),
            stageName = stageOriginal.fileName.toString(),
            token = intentToken.toString(),
        )

        fun removeIntentMarker() {
            removeIntentMarker(
                parent,
                intentToken,
                requireNotNull(intentMarkerIdentity),
            )
            intentMarkerIdentity = null
        }

        fun removeIntentMarkerIfPresent() {
            intentMarkerIdentity?.let { expected ->
                removeIntentMarkerIfPresent(parent, intentToken, expected)
                intentMarkerIdentity = null
            }
        }
    }

    private fun recoverOld(record: JournalRecord, outputParent: Path) {
        val stagePath = outputParent.resolve(record.outputStageName)
        secureDirectory(outputParent).use { outputHandle ->
            val stageAttributes = attributesOrNull(
                outputHandle,
                singleName(record.outputStageName),
            )
            if (stageAttributes != null) {
                require(
                    stageAttributes.isDirectory &&
                        !stageAttributes.isSymbolicLink &&
                        stageAttributes.fileKey().toString() ==
                        requireNotNull(record.outputStageRootKey) &&
                        stableFileIdentity(
                            stagePath,
                            requireNotNull(stageAttributes.fileKey()),
                        ) == decodeStableIdentity(
                            requireNotNull(record.outputStageIdentity),
                        ),
                ) {
                    "journal output stage identity가 변경되었습니다."
                }
                secureDirectory(stagePath).use { stageHandle ->
                    record.outputs.forEachIndexed { index, output ->
                        val targetName = singleName(output.name)
                        val backupName = singleName("backup-$index")
                        val targetProof = fileProofOrNull(
                            outputHandle,
                            targetName,
                            outputParent.resolve(output.name),
                        )
                        val backupProof = fileProofOrNull(
                            stageHandle,
                            backupName,
                            stagePath.resolve(backupName.toString()),
                        )
                        if (targetProof?.sha256 == output.newSha256) {
                            require(targetProof.identity == output.newIdentity) {
                                "journal published output identity가 변경되었습니다."
                            }
                            outputHandle.deleteFile(targetName)
                        } else if (targetProof != null &&
                            !(output.oldExists &&
                                targetProof.sha256 == output.oldSha256 &&
                                targetProof.identity == output.oldIdentity)
                        ) {
                            throw IllegalStateException(
                                "journal recovery 대상 output이 외부에서 변경되었습니다.",
                            )
                        }
                        if (output.oldExists) {
                            if (backupProof != null) {
                                require(
                                    backupProof.sha256 == output.oldSha256 &&
                                        backupProof.identity == output.oldIdentity,
                                ) {
                                    "journal output backup이 변경되었습니다."
                                }
                                require(attributesOrNull(outputHandle, targetName) == null) {
                                    "journal output 복구 대상이 비어 있지 않습니다."
                                }
                                stageHandle.move(backupName, outputHandle, targetName)
                            } else {
                                require(
                                    fileProofOrNull(
                                        outputHandle,
                                        targetName,
                                        outputParent.resolve(output.name),
                                    ) == FileProof(
                                        requireNotNull(output.oldSha256),
                                        requireNotNull(output.oldIdentity),
                                    ),
                                ) {
                                    "journal output 원본 또는 backup이 없습니다."
                                }
                            }
                        } else {
                            require(attributesOrNull(outputHandle, targetName) == null) {
                                "원래 없던 output이 journal rollback 뒤 남았습니다."
                            }
                        }
                    }
                }
                secureDirectory(outputParent).use { parent ->
                    deleteTree(parent, singleName(record.outputStageName))
                    require(
                        attributesOrNull(
                            parent,
                            singleName(record.outputStageName),
                        ) == null,
                    )
                }
            } else {
                record.outputs.forEach { output ->
                    val proof = fileProofOrNull(
                        outputHandle,
                        singleName(output.name),
                        outputParent.resolve(output.name),
                    )
                    require(
                        (output.oldExists &&
                            proof == FileProof(
                                requireNotNull(output.oldSha256),
                                requireNotNull(output.oldIdentity),
                            )) ||
                            (!output.oldExists && proof == null),
                    ) {
                        "journal stage 없이 output 원본 상태를 확인할 수 없습니다."
                    }
                }
            }
        }
        record.local?.let(::recoverLocalOld)
        forceDirectory(outputParent)
    }

    private fun recoverCommitted(record: JournalRecord, outputParent: Path) {
        secureDirectory(outputParent).use { outputHandle ->
            record.outputs.forEach { output ->
                require(
                    fileProofOrNull(
                        outputHandle,
                        singleName(output.name),
                        outputParent.resolve(output.name),
                    ) == FileProof(
                        output.newSha256,
                        requireNotNull(output.newIdentity),
                    ),
                ) {
                    "commit-ready journal의 published output이 변경되었습니다."
                }
            }
            val stageName = singleName(record.outputStageName)
            attributesOrNull(outputHandle, stageName)?.let { attributes ->
                require(
                    attributes.isDirectory &&
                        !attributes.isSymbolicLink &&
                        attributes.fileKey().toString() ==
                        requireNotNull(record.outputStageRootKey) &&
                        stableFileIdentity(
                            outputParent.resolve(record.outputStageName),
                            requireNotNull(attributes.fileKey()),
                        ) == decodeStableIdentity(
                            requireNotNull(record.outputStageIdentity),
                        ),
                ) {
                    "commit-ready output stage identity가 변경되었습니다."
                }
                deleteTree(outputHandle, stageName)
                require(attributesOrNull(outputHandle, stageName) == null)
            }
        }
        record.local?.let(::recoverLocalCommitted)
        forceDirectory(outputParent)
    }

    private fun recoverLocalOld(local: JournalLocal) {
        val parent = Path.of(local.parent)
        validateTrustedLocalPath(Path.of(local.trustedRoot), parent)
        secureDirectory(parent).use { handle ->
            val destination = singleName(local.destinationName)
            val stage = singleName(local.stageName)
            val backup = singleName(local.backupName)
            val destinationAttributes = attributesOrNull(handle, destination)
            if (destinationAttributes?.fileKey()?.toString() == local.stageRootKey) {
                val destinationPath = parent.resolve(local.destinationName)
                require(
                    stableFileIdentity(
                        destinationPath,
                        requireNotNull(destinationAttributes.fileKey()),
                    ) == decodeStableIdentity(local.stageIdentity) &&
                    WorkspaceTreeDigest.capture(destinationPath).provenanceSha256 ==
                        local.newProvenanceSha256,
                ) {
                    "published local source가 journal 기록과 다릅니다."
                }
                deleteTree(handle, destination)
            } else if (destinationAttributes != null) {
                require(
                    local.oldExists &&
                        destinationAttributes.fileKey().toString() == local.oldRootKey &&
                        WorkspaceTreeDigest.capture(
                            parent.resolve(local.destinationName),
                        ).identitySha256 == local.oldIdentitySha256,
                ) {
                    "local source 대상이 journal recovery 전에 변경되었습니다."
                }
            }
            val backupAttributes = attributesOrNull(handle, backup)
            if (backupAttributes != null) {
                require(
                    local.oldExists &&
                        backupAttributes.fileKey().toString() == local.oldRootKey &&
                        WorkspaceTreeDigest.capture(
                            parent.resolve(local.backupName),
                        ).identitySha256 == local.oldIdentitySha256,
                ) {
                    "local source backup이 journal 기록과 다릅니다."
                }
                require(attributesOrNull(handle, destination) == null)
                handle.move(backup, handle, destination)
            }
            attributesOrNull(handle, stage)?.let { attributes ->
                require(
                    attributes.fileKey().toString() == local.stageRootKey &&
                        stableFileIdentity(
                            parent.resolve(local.stageName),
                            requireNotNull(attributes.fileKey()),
                        ) == decodeStableIdentity(local.stageIdentity) &&
                        WorkspaceTreeDigest.capture(
                            parent.resolve(local.stageName),
                        ).provenanceSha256 == local.newProvenanceSha256,
                ) {
                    "local source stage가 journal 기록과 다릅니다."
                }
                deleteTree(handle, stage)
                require(attributesOrNull(handle, stage) == null)
            }
            if (local.oldExists) {
                require(
                    WorkspaceTreeDigest.capture(
                        parent.resolve(local.destinationName),
                    ).identitySha256 == local.oldIdentitySha256,
                )
            } else {
                require(attributesOrNull(handle, destination) == null)
            }
            forceDirectory(parent)
        }
    }

    private fun recoverLocalCommitted(local: JournalLocal) {
        val parent = Path.of(local.parent)
        validateTrustedLocalPath(Path.of(local.trustedRoot), parent)
        secureDirectory(parent).use { handle ->
            val destination = singleName(local.destinationName)
            val attributes = requireNotNull(attributesOrNull(handle, destination))
            require(
                attributes.fileKey().toString() == local.stageRootKey &&
                    stableFileIdentity(
                        parent.resolve(local.destinationName),
                        requireNotNull(attributes.fileKey()),
                    ) == decodeStableIdentity(local.stageIdentity) &&
                    WorkspaceTreeDigest.capture(
                        parent.resolve(local.destinationName),
                    ).provenanceSha256 == local.newProvenanceSha256,
            ) {
                "commit-ready local source가 journal 기록과 다릅니다."
            }
            listOf(local.backupName, local.stageName).forEach { value ->
                val name = singleName(value)
                attributesOrNull(handle, name)?.let { candidate ->
                    val expectedKey = if (value == local.backupName) {
                        local.oldRootKey
                    } else {
                        local.stageRootKey
                    }
                    require(candidate.fileKey().toString() == expectedKey) {
                        "commit cleanup 대상 local tree identity가 변경되었습니다."
                    }
                    deleteTree(handle, name)
                    require(attributesOrNull(handle, name) == null)
                }
            }
            forceDirectory(parent)
        }
    }

    private data class JournalOutput(
        val name: String,
        val oldExists: Boolean,
        val oldSha256: String?,
        val newSha256: String,
        val oldIdentity: String?,
        val newIdentity: String?,
    )

    private data class JournalLocalIntent(
        val trustedRoot: String,
        val parent: String,
        val stageName: String,
        val token: String,
    )

    private data class JournalLocal(
        val trustedRoot: String,
        val parent: String,
        val destinationName: String,
        val stageName: String,
        val backupName: String,
        val oldExists: Boolean,
        val oldIdentitySha256: String?,
        val newProvenanceSha256: String,
        val stageRootKey: String,
        val stageIdentity: String,
        val oldRootKey: String?,
    )

    private enum class JournalPhase {
        INTENT,
        OUTPUT_STAGE_CREATED,
        OUTPUT_STAGE_READY,
        LOCAL_STAGE_CREATED,
        STAGES_READY,
        STAGED,
        OUTPUT_BACKED_UP,
        OUTPUT_PARTIAL,
        OUTPUTS_PUBLISHED,
        LOCAL_PUBLISHED,
        COMMIT_READY,
    }

    private data class JournalRecord(
        val phase: JournalPhase,
        val publishedCount: Int,
        val outputStageName: String,
        val outputStageRootKey: String?,
        val outputStageIdentity: String?,
        val outputIntentToken: String,
        val outputs: List<JournalOutput>,
        val local: JournalLocal?,
        val localIntent: JournalLocalIntent?,
    ) {
        companion object {
            fun intent(
                output: OutputTransaction,
                local: LocalSourceTransaction?,
            ): JournalRecord = JournalRecord(
                phase = JournalPhase.INTENT,
                publishedCount = 0,
                outputStageName = output.journalStageName(),
                outputStageRootKey = null,
                outputStageIdentity = null,
                outputIntentToken = output.journalIntentToken(),
                outputs = output.journalIntentTargets(),
                local = null,
                localIntent = local?.journalIntent(),
            )

            fun outputStageCreated(
                output: OutputTransaction,
                local: LocalSourceTransaction?,
            ): JournalRecord = JournalRecord(
                phase = JournalPhase.OUTPUT_STAGE_CREATED,
                publishedCount = 0,
                outputStageName = output.journalStageName(),
                outputStageRootKey = output.journalStageRootKey(),
                outputStageIdentity = output.journalStageIdentity(),
                outputIntentToken = output.journalIntentToken(),
                outputs = output.journalIntentTargets(),
                local = null,
                localIntent = local?.journalIntent(),
            )

            fun outputStageReady(
                output: OutputTransaction,
                local: LocalSourceTransaction?,
            ): JournalRecord = JournalRecord(
                phase = JournalPhase.OUTPUT_STAGE_READY,
                publishedCount = 0,
                outputStageName = output.journalStageName(),
                outputStageRootKey = output.journalStageRootKey(),
                outputStageIdentity = output.journalStageIdentity(),
                outputIntentToken = output.journalIntentToken(),
                outputs = output.journalTargets(),
                local = null,
                localIntent = local?.journalIntent(),
            )

            fun create(
                phase: JournalPhase,
                publishedCount: Int,
                output: OutputTransaction,
                local: LocalSourceTransaction?,
            ): JournalRecord = JournalRecord(
                phase,
                publishedCount,
                output.journalStageName(),
                output.journalStageRootKey(),
                output.journalStageIdentity(),
                output.journalIntentToken(),
                output.journalTargets(),
                local?.journalLocal(),
                local?.journalIntent(),
            )
        }
    }

    private class PublicationJournal(
        private val parent: Path,
    ) {
        private val path = parent.resolve(JOURNAL_FILE)

        fun read(): JournalRecord? {
            secureDirectory(parent).use { handle ->
                validateDirectoryHandle(parent, handle)
                val name = singleName(JOURNAL_FILE)
                val attributes = attributesOrNull(handle, name) ?: return null
                require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "publish journal은 일반 파일이어야 합니다."
                }
                require(strictUnixLinks(path) == 1L) {
                    "publish journal hard link는 허용되지 않습니다."
                }
                val permissions = requireNotNull(
                    handle.getFileAttributeView(
                        name,
                        PosixFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    ),
                ).readAttributes().permissions()
                require(permissions == JOURNAL_PERMISSIONS) {
                    "publish journal은 owner-only여야 합니다."
                }
                return decode(
                    decodeUtf8Strict(
                        handle.newByteChannel(
                            name,
                            setOf(READ, NOFOLLOW_LINKS),
                        ).use { it.readAllBytes() },
                    ),
                )
            }
        }

        fun write(record: JournalRecord) {
            val bytes = encode(record).toByteArray(Charsets.UTF_8)
            val tempName = ".sixbridge-publish-journal.tmp-${UUID.randomUUID()}"
            val tempPath = parent.resolve(tempName)
            secureDirectory(parent).use { handle ->
                validateDirectoryHandle(parent, handle)
                val relative = singleName(tempName)
                handle.newByteChannel(
                    relative,
                    setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    (channel as? FileChannel)?.force(true)
                        ?: throw IllegalStateException("publish journal을 fsync할 수 없습니다.")
                }
                val view = requireNotNull(
                    handle.getFileAttributeView(
                        relative,
                        PosixFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    ),
                )
                view.setPermissions(JOURNAL_PERMISSIONS)
                require(view.readAttributes().permissions() == JOURNAL_PERMISSIONS)
                Files.move(tempPath, path, ATOMIC_MOVE, REPLACE_EXISTING)
                validateDirectoryHandle(parent, handle)
                forceDirectory(parent)
            }
        }

        fun delete() {
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return
            secureDirectory(parent).use { handle ->
                validateDirectoryHandle(parent, handle)
                var removedTemp = false
                handle.newDirectoryStream(Path.of("."), NOFOLLOW_LINKS).use { scan ->
                    scan.map { it.fileName.toString() }
                        .filter { it.startsWith(".sixbridge-publish-journal.tmp-") }
                        .forEach { temp ->
                            val tempName = singleName(temp)
                            val attributes = requireNotNull(
                                attributesOrNull(handle, tempName),
                            )
                            require(attributes.isRegularFile && !attributes.isSymbolicLink)
                            require(
                                strictUnixLinks(parent.resolve(temp)) == 1L,
                            ) {
                                "publish journal 임시 파일 hard link는 허용되지 않습니다."
                            }
                            val permissions = requireNotNull(
                                handle.getFileAttributeView(
                                    tempName,
                                    PosixFileAttributeView::class.java,
                                    NOFOLLOW_LINKS,
                                ),
                            ).readAttributes().permissions()
                            require(permissions == JOURNAL_PERMISSIONS) {
                                "publish journal 임시 파일은 owner-only여야 합니다."
                            }
                            handle.deleteFile(tempName)
                            removedTemp = true
                        }
                }
                if (removedTemp) forceDirectory(parent)
                val name = singleName(JOURNAL_FILE)
                if (attributesOrNull(handle, name) != null) {
                    require(strictUnixLinks(path) == 1L)
                    handle.deleteFile(name)
                    forceDirectory(parent)
                }
            }
        }

        private fun encode(record: JournalRecord): String {
            validateRecord(record)
            val payloadLines = buildList {
                add("SIXBRIDGE-PUBLISH-JOURNAL-V2")
                add(
                    listOf(
                        "H",
                        record.phase.name,
                        record.publishedCount.toString(),
                        encodeField(record.outputStageName),
                        encodeNullable(record.outputStageRootKey),
                        encodeNullable(record.outputStageIdentity),
                        encodeField(record.outputIntentToken),
                    ).joinToString("|"),
                )
                record.outputs.forEach { output ->
                    add(
                        listOf(
                            "O",
                            encodeField(output.name),
                            output.oldExists.toString(),
                            encodeNullable(output.oldSha256),
                            output.newSha256,
                            encodeNullable(output.oldIdentity),
                            encodeNullable(output.newIdentity),
                        ).joinToString("|"),
                    )
                }
                record.localIntent?.let { intent ->
                    add(
                        listOf(
                            "T",
                            encodeField(intent.trustedRoot),
                            encodeField(intent.parent),
                            encodeField(intent.stageName),
                            encodeField(intent.token),
                        ).joinToString("|"),
                    )
                }
                record.local?.let { local ->
                    add(
                        listOf(
                            "L",
                            encodeField(local.trustedRoot),
                            encodeField(local.parent),
                            encodeField(local.destinationName),
                            encodeField(local.stageName),
                            encodeField(local.backupName),
                            local.oldExists.toString(),
                            encodeNullable(local.oldIdentitySha256),
                            local.newProvenanceSha256,
                            encodeField(local.stageRootKey),
                            encodeField(local.stageIdentity),
                            encodeNullable(local.oldRootKey),
                        ).joinToString("|"),
                    )
                }
            }
            val metadataPrefix = listOf(
                "M",
                "2",
                record.outputs.size.toString(),
                (record.localIntent != null).toString(),
                (record.local != null).toString(),
            ).joinToString("|")
            val checksumInput = (
                listOf(payloadLines.first(), metadataPrefix) +
                    payloadLines.drop(1)
                ).joinToString("\n", postfix = "\n")
            val metadata = "$metadataPrefix|" +
                sha256(checksumInput.toByteArray(Charsets.UTF_8))
            return buildString {
                appendLine(payloadLines.first())
                appendLine(metadata)
                payloadLines.drop(1).forEach(::appendLine)
            }
        }

        private fun decode(text: String): JournalRecord {
            require(text.endsWith('\n')) { "journal은 완전한 줄바꿈으로 끝나야 합니다." }
            val lines = text.dropLast(1).split('\n')
            require(lines.none(String::isEmpty))
            require(lines.firstOrNull() == "SIXBRIDGE-PUBLISH-JOURNAL-V2")
            val metadata = lines.getOrNull(1)?.split('|')
                ?: error("journal metadata 없음")
            require(metadata.size == 6 && metadata[0] == "M" && metadata[1] == "2")
            val outputCount = metadata[2].toInt()
            require(outputCount > 0)
            val localIntentPresent = metadata[3].toBooleanStrict()
            val localPresent = metadata[4].toBooleanStrict()
            require(metadata[5].matches(SHA256_PATTERN))
            val metadataPrefix = metadata.take(5).joinToString("|")
            val checksumInput = (
                listOf(lines.first(), metadataPrefix) +
                    lines.drop(2)
                )
                .joinToString("\n", postfix = "\n")
            require(
                sha256(checksumInput.toByteArray(Charsets.UTF_8)) == metadata[5],
            ) {
                "journal checksum이 일치하지 않습니다."
            }
            val header = lines.getOrNull(2)?.split('|') ?: error("journal header 없음")
            require(header.size == 7 && header[0] == "H")
            val body = lines.drop(3)
            require(body.none { !it.startsWith("O|") &&
                !it.startsWith("T|") &&
                !it.startsWith("L|")
            })
            val outputLines = body.takeWhile { it.startsWith("O|") }
            require(outputLines.size == outputCount)
            val outputs = outputLines.map { line ->
                val fields = line.split('|')
                require(fields.size == 7)
                JournalOutput(
                    decodeField(fields[1]),
                    fields[2].toBooleanStrict(),
                    decodeNullable(fields[3]),
                    fields[4],
                    decodeNullable(fields[5]),
                    decodeNullable(fields[6]),
                )
            }
            require(outputs.map(JournalOutput::name).toSet().size == outputs.size)
            outputs.forEach { singleName(it.name) }
            val tail = body.drop(outputLines.size)
            require(tail.all { it.startsWith("T|") || it.startsWith("L|") })
            require(tail.count { it.startsWith("T|") } <= 1)
            require(tail.count { it.startsWith("L|") } <= 1)
            require(tail == tail.sortedBy {
                when {
                    it.startsWith("T|") -> 0
                    else -> 1
                }
            })
            val localIntent = tail
                .singleOrNull { it.startsWith("T|") }
                ?.let { line ->
                    val fields = line.split('|')
                    require(fields.size == 5)
                    JournalLocalIntent(
                        trustedRoot = decodeField(fields[1]),
                        parent = decodeField(fields[2]),
                        stageName = decodeField(fields[3]),
                        token = decodeField(fields[4]),
                    )
                }
            val local = tail.singleOrNull { it.startsWith("L|") }?.let { line ->
                val fields = line.split('|')
                require(fields.size == 12)
                JournalLocal(
                    trustedRoot = decodeField(fields[1]),
                    parent = decodeField(fields[2]),
                    destinationName = decodeField(fields[3]),
                    stageName = decodeField(fields[4]),
                    backupName = decodeField(fields[5]),
                    oldExists = fields[6].toBooleanStrict(),
                    oldIdentitySha256 = decodeNullable(fields[7]),
                    newProvenanceSha256 = fields[8],
                    stageRootKey = decodeField(fields[9]),
                    stageIdentity = decodeField(fields[10]),
                    oldRootKey = decodeNullable(fields[11]),
                )
            }
            require(localIntentPresent == (localIntent != null))
            require(localPresent == (local != null))
            return JournalRecord(
                phase = JournalPhase.valueOf(header[1]),
                publishedCount = header[2].toInt(),
                outputStageName = decodeField(header[3]),
                outputStageRootKey = decodeNullable(header[4]),
                outputStageIdentity = decodeNullable(header[5]),
                outputIntentToken = decodeField(header[6]),
                outputs = outputs,
                local = local,
                localIntent = localIntent,
            ).also(::validateRecord)
        }

        private fun encodeField(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeField(value: String): String {
            require(value.isNotEmpty() && !value.contains('='))
            val bytes = Base64.getUrlDecoder().decode(value)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
            return decodeUtf8Strict(bytes)
        }

        private fun encodeNullable(value: String?): String =
            value?.let(::encodeField) ?: "-"

        private fun decodeNullable(value: String): String? =
            value.takeUnless { it == "-" }?.let(::decodeField)

        private fun validateRecord(record: JournalRecord) {
            require(record.outputs.isNotEmpty())
            require(record.outputs.map(JournalOutput::name).toSet().size ==
                record.outputs.size
            )
            require(record.publishedCount in 0..record.outputs.size)
            singleName(record.outputStageName)
            require(OUTPUT_STAGE_PATTERN.matches(record.outputStageName))
            strictUuid(record.outputIntentToken)
            record.outputs.forEach { output ->
                singleName(output.name)
                require(output.newSha256.matches(SHA256_PATTERN))
                require(output.oldExists == (output.oldSha256 != null))
                require(output.oldExists == (output.oldIdentity != null))
                output.oldSha256?.let { require(it.matches(SHA256_PATTERN)) }
                output.oldIdentity?.let(::decodeStableIdentity)
                output.newIdentity?.let(::decodeStableIdentity)
            }
            val earlyOutput = record.phase in setOf(
                JournalPhase.INTENT,
                JournalPhase.OUTPUT_STAGE_CREATED,
            )
            if (record.phase == JournalPhase.INTENT) {
                require(record.outputStageRootKey == null)
                require(record.outputStageIdentity == null)
            } else {
                require(!record.outputStageRootKey.isNullOrEmpty())
                decodeStableIdentity(requireNotNull(record.outputStageIdentity))
            }
            record.outputs.forEach { output ->
                if (earlyOutput) {
                    require(!output.oldExists && output.newIdentity == null)
                } else {
                    require(output.newIdentity != null)
                }
            }
            val expectedCount = when (record.phase) {
                JournalPhase.OUTPUT_PARTIAL -> {
                    require(record.publishedCount > 0)
                    record.publishedCount
                }
                JournalPhase.OUTPUTS_PUBLISHED,
                JournalPhase.LOCAL_PUBLISHED,
                JournalPhase.COMMIT_READY,
                -> record.outputs.size
                else -> 0
            }
            require(record.publishedCount == expectedCount)
            record.localIntent?.let { intent ->
                require(LOCAL_STAGE_PATTERN.matches(intent.stageName))
                singleName(intent.stageName)
                strictUuid(intent.token)
                val parent = canonicalAbsolutePath(intent.parent)
                val trustedRoot = canonicalAbsolutePath(intent.trustedRoot)
                require(parent.startsWith(trustedRoot)) {
                    "local intent parent는 trusted root 내부여야 합니다."
                }
            }
            record.local?.let { local ->
                require(record.localIntent != null)
                require(local.trustedRoot == record.localIntent.trustedRoot)
                require(local.parent == record.localIntent.parent)
                require(local.stageName == record.localIntent.stageName)
                singleName(local.destinationName)
                require(LOCAL_STAGE_PATTERN.matches(local.stageName))
                require(LOCAL_BACKUP_PATTERN.matches(local.backupName))
                require(local.newProvenanceSha256.matches(SHA256_PATTERN))
                require(local.oldExists == (local.oldIdentitySha256 != null))
                require(local.oldExists == (local.oldRootKey != null))
                local.oldIdentitySha256?.let {
                    require(it.matches(SHA256_PATTERN))
                }
                require(local.stageRootKey.isNotEmpty())
                decodeStableIdentity(local.stageIdentity)
            }
            when (record.phase) {
                JournalPhase.INTENT,
                JournalPhase.OUTPUT_STAGE_CREATED,
                JournalPhase.OUTPUT_STAGE_READY,
                -> require(record.local == null)

                JournalPhase.LOCAL_STAGE_CREATED -> {
                    require(record.localIntent != null && record.local != null)
                }

                else -> if (record.localIntent != null) {
                    require(record.local != null)
                }
            }
        }

        private fun decodeUtf8Strict(bytes: ByteArray): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()

        private fun canonicalAbsolutePath(value: String): Path {
            val path = Path.of(value)
            require(path.isAbsolute && path.normalize().toString() == value)
            return path
        }
    }

    private data class OutputTarget(
        val index: Int,
        val path: Path,
        val name: Path,
        val bytes: ByteArray,
        val newSha256: String = sha256(bytes),
        var original: FileState? = null,
        var originalSha256: String? = null,
        var staged: FileState? = null,
        var stagedName: Path? = null,
        var backupName: Path? = null,
        var wasPublished: Boolean = false,
    )

    private data class FileState(
        val identity: StableFileIdentity,
        val size: Long,
        val modified: java.nio.file.attribute.FileTime,
        val links: Long,
    )

    private data class StableFileIdentity(
        val device: Long,
        val inode: Long,
    ) {
        fun encode(): String = "$device:$inode"
    }

    private fun targetState(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        absolute: Path,
    ): FileState? {
        val attributes = attributesOrNull(directory, name) ?: return null
        attributes.checkedRegular("output")
        val links = strictUnixLinks(absolute)
        require(links == 1L) {
            "출력 경로의 hard link는 허용되지 않습니다: $absolute"
        }
        return FileState(
            identity = stableFileIdentity(absolute, requireNotNull(attributes.fileKey())),
            size = attributes.size(),
            modified = attributes.lastModifiedTime(),
            links = links,
        )
    }

    private fun BasicFileAttributes.checkedRegular(label: String) {
        require(isRegularFile && !isSymbolicLink) {
            "$label 경로는 symlink가 아닌 일반 파일이어야 합니다."
        }
    }

    private fun requireSameState(
        expected: FileState?,
        actual: FileState?,
        message: String,
    ) {
        require(expected == actual) { message }
    }

    private fun writeNew(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        bytes: ByteArray,
    ) {
        directory.newByteChannel(
            name,
            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? java.nio.channels.FileChannel)?.force(true)
        }
    }

    private fun SeekableByteChannel.readAllBytes(): ByteArray {
        val size = size()
        require(size in 0..MAX_EXISTING_OUTPUT_BYTES) {
            "기존 wrapper APK가 비정상적으로 큽니다."
        }
        val bytes = ByteArray(size.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) break
        }
        require(!buffer.hasRemaining()) {
            "기존 wrapper APK를 완전히 읽지 못했습니다."
        }
        return bytes
    }

    private fun secureDirectory(path: Path): SecureDirectoryStream<Path> {
        val stream = Files.newDirectoryStream(path)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            throw IllegalStateException(
                "secure directory stream을 지원하지 않는 파일 시스템입니다: $path",
            )
        }
        return stream
    }

    private fun <T> withPublicationLock(
        parent: Path,
        block: () -> T,
    ): T {
        val normalized = parent.toAbsolutePath().normalize()
        val processLock = PUBLICATION_LOCKS.computeIfAbsent(normalized) {
            ReentrantLock()
        }
        return processLock.withLock {
            secureDirectory(normalized).use { handle ->
                validateDirectoryHandle(normalized, handle)
                val lockName = singleName(PUBLICATION_LOCK_FILE)
                if (attributesOrNull(handle, lockName) == null) {
                    try {
                        handle.newByteChannel(
                            lockName,
                            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                        ).use { channel ->
                            (channel as? FileChannel)?.force(true)
                                ?: throw IllegalStateException(
                                    "publisher lock 파일을 fsync할 수 없습니다.",
                                )
                        }
                        val view = requireNotNull(
                            handle.getFileAttributeView(
                                lockName,
                                PosixFileAttributeView::class.java,
                                NOFOLLOW_LINKS,
                            ),
                        )
                        view.setPermissions(JOURNAL_PERMISSIONS)
                        forceDirectory(normalized)
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        // 다른 프로세스가 먼저 생성했으므로 아래에서 검증한다.
                    }
                }
                val attributes = requireNotNull(attributesOrNull(handle, lockName))
                require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "publisher lock은 일반 파일이어야 합니다."
                }
                require(strictUnixLinks(normalized.resolve(PUBLICATION_LOCK_FILE)) == 1L) {
                    "publisher lock hard link는 허용되지 않습니다."
                }
                val permissions = requireNotNull(
                    handle.getFileAttributeView(
                        lockName,
                        PosixFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    ),
                ).readAttributes().permissions()
                require(permissions == JOURNAL_PERMISSIONS) {
                    "publisher lock은 owner-only여야 합니다."
                }
                val lockFileKey = requireNotNull(attributes.fileKey())
                val rootFileKey = requireNotNull(
                    handle.getFileAttributeView(BasicFileAttributeView::class.java)
                        ?.readAttributes()
                        ?.fileKey(),
                )
                val channel = handle.newByteChannel(
                    lockName,
                    setOf(WRITE, NOFOLLOW_LINKS),
                ) as? FileChannel ?: throw IllegalStateException(
                    "publisher lock을 FileChannel로 열 수 없습니다.",
                )
                channel.use {
                    it.lock().use {
                        activePublicationRoot.set(
                            PublicationRootGuard(normalized, rootFileKey),
                        )
                        try {
                            BuildPublicationFaultInjection.afterPublicationLock()
                            validateDirectoryHandle(normalized, handle)
                            require(
                                attributesOrNull(handle, lockName)?.fileKey() == lockFileKey,
                            ) {
                                "publisher lock identity가 변경되었습니다."
                            }
                            block().also {
                                validateDirectoryHandle(normalized, handle)
                                require(
                                    attributesOrNull(handle, lockName)?.fileKey() == lockFileKey,
                                ) {
                                    "publisher lock identity가 변경되었습니다."
                                }
                            }
                        } finally {
                            activePublicationRoot.remove()
                        }
                    }
                }
            }
        }
    }

    private fun validateDirectoryHandle(
        path: Path,
        handle: SecureDirectoryStream<Path>,
    ) {
        val absolute = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val anchored = requireNotNull(
            handle.getFileAttributeView(BasicFileAttributeView::class.java),
        ).readAttributes()
        activePublicationRoot.get()?.takeIf {
            path.toAbsolutePath().normalize() == it.path
        }?.let { guard ->
            require(absolute.fileKey() == guard.fileKey &&
                anchored.fileKey() == guard.fileKey
            ) {
                "publisher output parent identity가 transaction 중 변경되었습니다."
            }
        }
        require(
            absolute.isDirectory &&
                !absolute.isSymbolicLink &&
                absolute.fileKey() != null &&
                absolute.fileKey() == anchored.fileKey(),
        ) {
            "directory handle identity를 확인할 수 없습니다: $path"
        }
    }

    private fun attributesOrNull(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? = try {
        requireNotNull(
            directory.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        ).readAttributes()
    } catch (_: NoSuchFileException) {
        null
    }

    private fun validateTreeNoLinks(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        require(
            Files.isDirectory(normalized, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(normalized),
        ) {
            "local source tree는 symlink가 아닌 디렉터리여야 합니다."
        }
        Files.walk(normalized).use { paths ->
            paths.forEach { path ->
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                require(
                    !attributes.isSymbolicLink &&
                        (attributes.isDirectory || attributes.isRegularFile),
                ) {
                    "local source tree에는 일반 파일과 디렉터리만 허용됩니다."
                }
                if (attributes.isRegularFile) {
                    require(strictUnixLinks(path) == 1L) {
                        "local source tree의 hard link는 허용되지 않습니다."
                    }
                }
            }
        }
    }

    private fun forceTree(root: Path) {
        val normalized = root.toAbsolutePath().normalize()
        validateTreeNoLinks(normalized)
        val paths = Files.walk(normalized).use { stream -> stream.toList() }
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { path ->
            val before = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(before.isRegularFile && !before.isSymbolicLink)
            require(strictUnixLinks(path) == 1L) {
                "local stage의 hard link는 허용되지 않습니다."
            }
            val identity = stableFileIdentity(path, requireNotNull(before.fileKey()))
            BuildPublicationFaultInjection.beforeLocalFileFsync(path)
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
            val after = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(
                after.isRegularFile &&
                    !after.isSymbolicLink &&
                    strictUnixLinks(path) == 1L &&
                    stableFileIdentity(path, requireNotNull(after.fileKey())) == identity,
            ) {
                "local stage 파일이 fsync 중 교체되었습니다."
            }
        }
        paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
            .sortedByDescending { it.nameCount }
            .forEach { path ->
                val before = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                require(before.isDirectory && !before.isSymbolicLink)
                val identity = stableFileIdentity(path, requireNotNull(before.fileKey()))
                forceDirectory(path)
                val after = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                require(
                    after.isDirectory &&
                        !after.isSymbolicLink &&
                        stableFileIdentity(
                            path,
                            requireNotNull(after.fileKey()),
                        ) == identity,
                ) {
                    "local stage 디렉터리가 fsync 중 교체되었습니다."
                }
            }
    }

    private fun stageOwnerMarkerName(token: UUID): Path =
        singleName(".sixbridge-stage-owner-$token")

    private fun writeStageOwnerMarker(
        stagePath: Path,
        stageHandle: SecureDirectoryStream<Path>,
        token: UUID,
    ) {
        val name = stageOwnerMarkerName(token)
        val bytes = token.toString().toByteArray(Charsets.US_ASCII)
        stageHandle.newByteChannel(
            name,
            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("stage owner marker를 fsync할 수 없습니다.")
        }
        val view = requireNotNull(
            stageHandle.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        )
        view.setPermissions(JOURNAL_PERMISSIONS)
        require(view.readAttributes().permissions() == JOURNAL_PERMISSIONS)
        require(strictUnixLinks(stagePath.resolve(name.toString())) == 1L)
        forceDirectory(stagePath)
    }

    private fun removeStageOwnerMarker(stagePath: Path, token: UUID) {
        secureDirectory(stagePath).use { handle ->
            requireStageOwnerMarker(stagePath, handle, token)
            handle.deleteFile(stageOwnerMarkerName(token))
            forceDirectory(stagePath)
        }
    }

    private fun requireStageOwnerMarker(
        stagePath: Path,
        stageHandle: SecureDirectoryStream<Path>,
        token: UUID,
    ) {
        val name = stageOwnerMarkerName(token)
        val attributes = requireNotNull(attributesOrNull(stageHandle, name)) {
            "stage 내부 owner token이 없습니다."
        }
        require(attributes.isRegularFile && !attributes.isSymbolicLink)
        require(strictUnixLinks(stagePath.resolve(name.toString())) == 1L)
        val permissions = requireNotNull(
            stageHandle.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        ).readAttributes().permissions()
        require(permissions == JOURNAL_PERMISSIONS) {
            "stage 내부 owner token은 owner-only여야 합니다."
        }
        val marker = stageHandle.newByteChannel(
            name,
            setOf(READ, NOFOLLOW_LINKS),
        ).use { it.readAllBytes() }.toString(Charsets.US_ASCII)
        require(marker == token.toString()) {
            "stage 내부 owner token이 journal과 다릅니다."
        }
    }

    private fun writeIntentMarker(
        parentPath: Path,
        parentHandle: SecureDirectoryStream<Path>,
        token: UUID,
        ownershipRecorded: (StableFileIdentity) -> Unit,
    ) {
        BuildPublicationFaultInjection.beforeIntentMarkerCreate(parentPath, token)
        val name = singleName(".sixbridge-publish-intent-$token")
        val bytes = token.toString().toByteArray(Charsets.US_ASCII)
        parentHandle.newByteChannel(
            name,
            setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            (channel as? FileChannel)?.force(true)
                ?: throw IllegalStateException("publish intent marker를 fsync할 수 없습니다.")
        }
        val view = requireNotNull(
            parentHandle.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        )
        view.setPermissions(JOURNAL_PERMISSIONS)
        val identity = readIntentMarkerIdentity(
            parentPath,
            parentHandle,
            token,
        )
        ownershipRecorded(identity)
        forceDirectory(parentPath)
        require(
            readIntentMarkerIdentity(parentPath, parentHandle, token) == identity,
        ) {
            "publish intent marker가 directory fsync 중 교체되었습니다."
        }
    }

    private fun deleteIntentOwnedStage(
        parent: Path,
        stageNameValue: String,
        tokenValue: String,
        expectedIdentityValue: String?,
    ) {
        val stageName = singleName(stageNameValue)
        val token = UUID.fromString(tokenValue)
        val markerName = singleName(".sixbridge-publish-intent-$token")
        secureDirectory(parent).use { parentHandle ->
            val stageAttributes = attributesOrNull(parentHandle, stageName)
            val markerAttributes = attributesOrNull(parentHandle, markerName)
            require(
                stageAttributes != null ||
                    markerAttributes == null ||
                    expectedIdentityValue != null,
            ) {
                "stage 없는 INTENT marker는 소유권을 증명할 수 없습니다."
            }
            if (stageAttributes != null) {
                require(stageAttributes.isDirectory && !stageAttributes.isSymbolicLink) {
                    "intent stage는 일반 디렉터리여야 합니다."
                }
                val stagePath = parent.resolve(stageName.toString())
                val actualIdentity = stableFileIdentity(
                    stagePath,
                    requireNotNull(stageAttributes.fileKey()),
                )
                if (expectedIdentityValue != null) {
                    require(actualIdentity == decodeStableIdentity(expectedIdentityValue)) {
                        "intent stage의 dev+ino identity가 journal과 다릅니다."
                    }
                    secureDirectory(stagePath).use { stageHandle ->
                        val ownerName = stageOwnerMarkerName(token)
                        if (attributesOrNull(stageHandle, ownerName) != null) {
                            requireStageOwnerMarker(stagePath, stageHandle, token)
                        }
                    }
                } else {
                    secureDirectory(stagePath).use { stageHandle ->
                        requireStageOwnerMarker(stagePath, stageHandle, token)
                    }
                }
            }
            if (markerAttributes != null) {
                readIntentMarkerIdentity(parent, parentHandle, token)
            }
            if (stageAttributes != null) deleteTree(parentHandle, stageName)
            if (markerAttributes != null) {
                deleteVerifiedIntentMarker(parent, parentHandle, token, null)
            }
            require(attributesOrNull(parentHandle, stageName) == null)
            require(attributesOrNull(parentHandle, markerName) == null)
            forceDirectory(parent)
        }
    }

    private fun decodeStableIdentity(value: String): StableFileIdentity {
        require(STABLE_IDENTITY_PATTERN.matches(value))
        val fields = value.split(':')
        val device = fields[0].toLong()
        val inode = fields[1].toLong()
        return StableFileIdentity(device, inode)
    }

    private fun strictUuid(value: String): UUID =
        UUID.fromString(value).also {
            require(it.toString() == value) { "UUID는 canonical lowercase 형식이어야 합니다." }
        }

    private fun removeIntentMarker(
        parentPath: Path,
        token: UUID,
        expectedIdentity: StableFileIdentity?,
    ) {
        secureDirectory(parentPath).use { handle ->
            require(
                deleteVerifiedIntentMarker(
                    parentPath,
                    handle,
                    token,
                    expectedIdentity,
                ),
            ) {
                "publish intent marker가 없습니다."
            }
        }
    }

    private fun removeIntentMarkerIfPresent(
        parentPath: Path,
        token: UUID,
        expectedIdentity: StableFileIdentity? = null,
    ) {
        secureDirectory(parentPath).use { handle ->
            deleteVerifiedIntentMarker(
                parentPath,
                handle,
                token,
                expectedIdentity,
            )
        }
    }

    private fun deleteVerifiedIntentMarker(
        parentPath: Path,
        parentHandle: SecureDirectoryStream<Path>,
        token: UUID,
        expectedIdentity: StableFileIdentity?,
    ): Boolean {
        val name = singleName(".sixbridge-publish-intent-$token")
        if (attributesOrNull(parentHandle, name) == null) return false
        val identity = readIntentMarkerIdentity(parentPath, parentHandle, token)
        expectedIdentity?.let {
            require(identity == it) {
                "publish intent marker identity가 생성 증거와 다릅니다."
            }
        }
        require(
            readIntentMarkerIdentity(parentPath, parentHandle, token) == identity,
        ) {
            "publish intent marker가 삭제 검증 중 교체되었습니다."
        }
        parentHandle.deleteFile(name)
        require(attributesOrNull(parentHandle, name) == null)
        forceDirectory(parentPath)
        return true
    }

    private fun readIntentMarkerIdentity(
        parentPath: Path,
        parentHandle: SecureDirectoryStream<Path>,
        token: UUID,
    ): StableFileIdentity {
        val name = singleName(".sixbridge-publish-intent-$token")
        val absolute = parentPath.resolve(name.toString())
        val before = requireNotNull(attributesOrNull(parentHandle, name))
        require(before.isRegularFile && !before.isSymbolicLink) {
            "publish intent marker는 일반 파일이어야 합니다."
        }
        require(strictUnixLinks(absolute) == 1L) {
            "publish intent marker hard link는 허용되지 않습니다."
        }
        val permissions = requireNotNull(
            parentHandle.getFileAttributeView(
                name,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ),
        ).readAttributes().permissions()
        require(permissions == JOURNAL_PERMISSIONS) {
            "publish intent marker는 owner-only여야 합니다."
        }
        val identity = stableFileIdentity(
            absolute,
            requireNotNull(before.fileKey()),
        )
        val bytes = parentHandle.newByteChannel(
            name,
            setOf(READ, NOFOLLOW_LINKS),
        ).use { it.readAllBytes() }
        require(bytes.contentEquals(token.toString().toByteArray(Charsets.US_ASCII))) {
            "publish intent marker token이 journal과 다릅니다."
        }
        val after = requireNotNull(attributesOrNull(parentHandle, name))
        require(
            after.isRegularFile &&
                !after.isSymbolicLink &&
                strictUnixLinks(absolute) == 1L &&
                requireNotNull(
                    parentHandle.getFileAttributeView(
                        name,
                        PosixFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    ),
                ).readAttributes().permissions() == JOURNAL_PERMISSIONS &&
                stableFileIdentity(
                    absolute,
                    requireNotNull(after.fileKey()),
                ) == identity,
        ) {
            "publish intent marker가 읽는 동안 변경되었습니다."
        }
        return identity
    }

    private fun deleteTree(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ) {
        val attributes = attributesOrNull(parent, name) ?: return
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "삭제할 backup은 일반 디렉터리여야 합니다."
        }
        parent.newDirectoryStream(name, NOFOLLOW_LINKS).use { child ->
            val names = child.newDirectoryStream(Path.of("."), NOFOLLOW_LINKS).use { scan ->
                scan.map { singleName(it.fileName.toString()) }.toList()
            }
            names.forEach { childName ->
                val childAttributes = requireNotNull(
                    attributesOrNull(child, childName),
                )
                if (childAttributes.isDirectory && !childAttributes.isSymbolicLink) {
                    deleteTree(child, childName)
                } else {
                    require(childAttributes.isRegularFile && !childAttributes.isSymbolicLink) {
                        "backup tree에 허용되지 않은 entry가 있습니다."
                    }
                    child.deleteFile(childName)
                }
            }
        }
        try {
            parent.deleteDirectory(name)
        } catch (error: DirectoryNotEmptyException) {
            throw IllegalStateException("backup tree cleanup 중 race가 감지되었습니다.", error)
        }
    }

    private fun strictUnixLinks(path: Path): Long =
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()

    private fun stableFileIdentity(
        path: Path,
        expectedFileKey: Any,
    ): StableFileIdentity {
        val unix = Files.readAttributes(
            path,
            "unix:dev,ino",
            NOFOLLOW_LINKS,
        )
        val identity = StableFileIdentity(
            device = (unix["dev"] as? Number)?.toLong()
                ?: throw IllegalStateException("output device identity를 읽을 수 없습니다."),
            inode = (unix["ino"] as? Number)?.toLong()
                ?: throw IllegalStateException("output inode identity를 읽을 수 없습니다."),
        )
        val after = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(after.fileKey() == expectedFileKey) {
            "output identity가 조회 중 변경되었습니다."
        }
        return identity
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class FileProof(
        val sha256: String,
        val identity: String,
    )

    private fun fileProofOrNull(
        handle: SecureDirectoryStream<Path>,
        name: Path,
        absolute: Path,
    ): FileProof? {
        val state = targetState(handle, name, absolute) ?: return null
        require(state.links == 1L) {
            "journal recovery 파일의 hard link는 허용되지 않습니다."
        }
        val digest = sha256(
            handle.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use {
                it.readAllBytes()
            },
        )
        requireSameState(
            state,
            targetState(handle, name, absolute),
            "journal recovery 파일이 읽는 동안 변경되었습니다.",
        )
        return FileProof(digest, state.identity.encode())
    }

    private fun forceDirectory(path: Path) {
        BuildPublicationFaultInjection.beforeDirectoryFsync(path)
        activePublicationRoot.get()?.takeIf {
            path.toAbsolutePath().normalize() == it.path
        }?.let { guard ->
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(attributes.fileKey() == guard.fileKey) {
                "publisher output parent identity가 fsync 전에 변경되었습니다."
            }
        }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun validateTrustedLocalPath(
        trustedRoot: Path,
        destinationParent: Path,
    ) {
        val trusted = trustedRoot.toAbsolutePath().normalize()
        val destination = destinationParent.toAbsolutePath().normalize()
        require(destination.startsWith(trusted)) {
            "local source 대상은 trusted workspace root 내부여야 합니다."
        }
        require(
            Files.isDirectory(trusted, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(trusted),
        ) {
            "trusted workspace root는 symlink가 아닌 디렉터리여야 합니다."
        }
        val trustedReal = trusted.toRealPath(NOFOLLOW_LINKS)
        var current = trusted
        trusted.relativize(destination).forEach { component ->
            current = current.resolve(component.toString())
            if (!Files.exists(current, NOFOLLOW_LINKS)) {
                Files.createDirectory(current)
            }
            require(
                Files.isDirectory(current, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(current) &&
                    current.toRealPath(NOFOLLOW_LINKS).startsWith(trustedReal),
            ) {
                "local source 경로의 symlink ancestor는 허용되지 않습니다."
            }
        }
    }

    private fun requireAllowedOutputName(path: Path) {
        val name = requireNotNull(path.fileName) {
            "output은 파일명을 가져야 합니다."
        }.toString()
        require(!name.startsWith(INTERNAL_NAMESPACE_PREFIX)) {
            "output은 SIX Bridge 내부 namespace를 사용할 수 없습니다: $name"
        }
    }

    private fun singleName(value: String): Path =
        Path.of(value).also { name ->
            require(name.nameCount == 1 && name.toString() !in setOf(".", "..")) {
                "단일 파일명만 허용됩니다."
            }
        }

    private val JOURNAL_PERMISSIONS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )
    private val PUBLICATION_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    private val activePublicationRoot = ThreadLocal<PublicationRootGuard?>()
    private data class PublicationRootGuard(
        val path: Path,
        val fileKey: Any,
    )
    private const val PUBLICATION_LOCK_FILE = ".sixbridge-build-publisher.lock"
    private const val JOURNAL_FILE = ".sixbridge-publish-journal.v1"
    private const val MAX_EXISTING_OUTPUT_BYTES = 512L * 1024 * 1024
    private const val INTERNAL_NAMESPACE_PREFIX = ".sixbridge-"
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    private val STABLE_IDENTITY_PATTERN = Regex("[1-9][0-9]*:[1-9][0-9]*")
    private val OUTPUT_STAGE_PATTERN = Regex(
        """\.sixbridge-publish-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""",
    )
    private val LOCAL_STAGE_PATTERN = Regex(
        """\.sixbridge-local-stage-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""",
    )
    private val LOCAL_BACKUP_PATTERN = Regex(
        """\.sixbridge-local-backup-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}""",
    )
}

private class ForcedBuildTermination :
    Error("injected forced build termination")

internal object BuildPublicationFaultInjection {
    private val beforeOutput = ThreadLocal<((Path) -> Unit)?>()
    private val afterLocal = ThreadLocal<(() -> Unit)?>()
    private val crashPoint = ThreadLocal<BuildCrashPoint?>()
    private val failCleanup = ThreadLocal<Boolean?>()
    private val afterStaged = ThreadLocal<(() -> Unit)?>()
    private val afterLock = ThreadLocal<(() -> Unit)?>()
    private val failRollback = ThreadLocal<Boolean?>()
    private val directoryFsyncObserver = ThreadLocal<((Path) -> Unit)?>()
    private val directoryFsyncFailurePredicate =
        ThreadLocal<((Path) -> Boolean)?>()
    private val localFileFsyncObserver = ThreadLocal<((Path) -> Unit)?>()
    private val localFileFsyncFailurePredicate =
        ThreadLocal<((Path) -> Boolean)?>()
    private val failBeforeStaged = ThreadLocal<Boolean?>()
    private val failAfterIntent = ThreadLocal<Boolean?>()
    private val localStageCrashPoint = ThreadLocal<LocalStageCrashPoint?>()
    private val outputStageCrashPoint = ThreadLocal<OutputStageCrashPoint?>()
    private val beforeIntentMarker =
        ThreadLocal<((Path, UUID) -> Unit)?>()

    @JvmSynthetic
    fun <T> beforeOutputPublish(
        action: (Path) -> Unit,
        block: () -> T,
    ): T {
        check(beforeOutput.get() == null)
        beforeOutput.set(action)
        return try {
            block()
        } finally {
            beforeOutput.remove()
        }
    }

    @JvmSynthetic
    fun <T> failAfterLocalPublish(block: () -> T): T {
        check(afterLocal.get() == null)
        afterLocal.set {
            throw IllegalStateException("injected failure after local source publish")
        }
        return try {
            block()
        } finally {
            afterLocal.remove()
        }
    }

    @JvmSynthetic
    fun beforeOutputPublish(path: Path) {
        beforeOutput.get()?.invoke(path)
    }

    @JvmSynthetic
    fun afterLocalPublish() {
        afterLocal.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> pauseAfterStagedJournal(
        action: () -> Unit,
        block: () -> T,
    ): T {
        check(afterStaged.get() == null)
        afterStaged.set(action)
        return try {
            block()
        } finally {
            afterStaged.remove()
        }
    }

    @JvmSynthetic
    fun afterStagedJournal() {
        afterStaged.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> replaceParentAfterLock(
        action: () -> Unit,
        block: () -> T,
    ): T {
        check(afterLock.get() == null)
        afterLock.set(action)
        return try {
            block()
        } finally {
            afterLock.remove()
        }
    }

    @JvmSynthetic
    fun afterPublicationLock() {
        afterLock.get()?.invoke()
    }

    @JvmSynthetic
    fun <T> failOutputRollback(block: () -> T): T {
        check(failRollback.get() == null)
        failRollback.set(true)
        return try {
            block()
        } finally {
            failRollback.remove()
        }
    }

    @JvmSynthetic
    fun shouldFailOutputRollback(): Boolean = failRollback.get() == true

    @JvmSynthetic
    fun <T> observeDirectoryFsync(
        observer: (Path) -> Unit,
        block: () -> T,
    ): T {
        check(directoryFsyncObserver.get() == null)
        directoryFsyncObserver.set(observer)
        return try {
            block()
        } finally {
            directoryFsyncObserver.remove()
        }
    }

    @JvmSynthetic
    fun <T> failDirectoryFsyncWhen(
        predicate: (Path) -> Boolean,
        block: () -> T,
    ): T {
        check(directoryFsyncFailurePredicate.get() == null)
        directoryFsyncFailurePredicate.set(predicate)
        return try {
            block()
        } finally {
            directoryFsyncFailurePredicate.remove()
        }
    }

    @JvmSynthetic
    fun beforeDirectoryFsync(path: Path) {
        directoryFsyncObserver.get()?.invoke(path)
        if (directoryFsyncFailurePredicate.get()?.invoke(path) == true) {
            directoryFsyncFailurePredicate.remove()
            throw IllegalStateException("injected publisher directory fsync failure")
        }
    }

    @JvmSynthetic
    fun <T> observeLocalFileFsync(
        observer: (Path) -> Unit,
        block: () -> T,
    ): T {
        check(localFileFsyncObserver.get() == null)
        localFileFsyncObserver.set(observer)
        return try {
            block()
        } finally {
            localFileFsyncObserver.remove()
        }
    }

    @JvmSynthetic
    fun <T> failLocalFileFsyncWhen(
        predicate: (Path) -> Boolean,
        block: () -> T,
    ): T {
        check(localFileFsyncFailurePredicate.get() == null)
        localFileFsyncFailurePredicate.set(predicate)
        return try {
            block()
        } finally {
            localFileFsyncFailurePredicate.remove()
        }
    }

    @JvmSynthetic
    fun beforeLocalFileFsync(path: Path) {
        localFileFsyncObserver.get()?.invoke(path)
        if (localFileFsyncFailurePredicate.get()?.invoke(path) == true) {
            localFileFsyncFailurePredicate.remove()
            throw IllegalStateException("injected local file fsync failure")
        }
    }

    @JvmSynthetic
    fun <T> forceLocalStageCrash(
        point: LocalStageCrashPoint,
        block: () -> T,
    ): T {
        check(localStageCrashPoint.get() == null)
        localStageCrashPoint.set(point)
        return try {
            block()
        } finally {
            localStageCrashPoint.remove()
        }
    }

    @JvmSynthetic
    fun crashLocalStageAt(point: LocalStageCrashPoint) {
        if (localStageCrashPoint.get() == point) throw ForcedBuildTermination()
    }

    @JvmSynthetic
    fun <T> forceOutputStageCrash(
        point: OutputStageCrashPoint,
        block: () -> T,
    ): T {
        check(outputStageCrashPoint.get() == null)
        outputStageCrashPoint.set(point)
        return try {
            block()
        } finally {
            outputStageCrashPoint.remove()
        }
    }

    @JvmSynthetic
    fun crashOutputStageAt(point: OutputStageCrashPoint) {
        if (outputStageCrashPoint.get() == point) throw ForcedBuildTermination()
    }

    @JvmSynthetic
    fun <T> beforeIntentMarkerCreate(
        action: (Path, UUID) -> Unit,
        block: () -> T,
    ): T {
        check(beforeIntentMarker.get() == null)
        beforeIntentMarker.set(action)
        return try {
            block()
        } finally {
            beforeIntentMarker.remove()
        }
    }

    @JvmSynthetic
    fun beforeIntentMarkerCreate(parent: Path, token: UUID) {
        beforeIntentMarker.get()?.invoke(parent, token)
    }

    @JvmSynthetic
    fun <T> forceAfterStageBeforeStagedJournal(block: () -> T): T {
        check(failBeforeStaged.get() == null)
        failBeforeStaged.set(true)
        return try {
            block()
        } finally {
            failBeforeStaged.remove()
        }
    }

    @JvmSynthetic
    fun afterStageBeforeStagedJournal() {
        if (failBeforeStaged.get() == true) throw ForcedBuildTermination()
    }

    @JvmSynthetic
    fun <T> forceAfterIntentBeforeStage(block: () -> T): T {
        check(failAfterIntent.get() == null)
        failAfterIntent.set(true)
        return try {
            block()
        } finally {
            failAfterIntent.remove()
        }
    }

    @JvmSynthetic
    fun afterIntentBeforeStage() {
        if (failAfterIntent.get() == true) throw ForcedBuildTermination()
    }

    @JvmSynthetic
    fun <T> forceTermination(
        point: BuildCrashPoint,
        block: () -> T,
    ): T {
        check(crashPoint.get() == null)
        crashPoint.set(point)
        return try {
            block()
        } finally {
            crashPoint.remove()
        }
    }

    @JvmSynthetic
    fun crashAt(point: BuildCrashPoint) {
        if (crashPoint.get() == point) throw ForcedBuildTermination()
    }

    @JvmSynthetic
    fun <T> failCommitCleanup(block: () -> T): T {
        check(failCleanup.get() == null)
        failCleanup.set(true)
        return try {
            block()
        } finally {
            failCleanup.remove()
        }
    }

    @JvmSynthetic
    fun beforeCommitCleanup() {
        if (failCleanup.get() == true) {
            throw IllegalStateException("injected commit cleanup failure")
        }
    }
}
