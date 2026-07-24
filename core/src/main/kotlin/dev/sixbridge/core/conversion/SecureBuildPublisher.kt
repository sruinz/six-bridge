package dev.sixbridge.core.conversion

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
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
        val parent = normalized.map { requireNotNull(it.first.parent) }.distinct()
            .singleOrNull()
            ?: throw IllegalArgumentException("모든 출력은 같은 디렉터리에 있어야 합니다.")
        Files.createDirectories(parent)
        recoverPending(
            normalized.map { it.first },
            localSource,
        )
        val outputTransaction = OutputTransaction(parent, normalized)
        val localTransaction = localSource?.let(::LocalSourceTransaction)
        val journal = PublicationJournal(parent)
        try {
            outputTransaction.stage()
            localTransaction?.stage()
            journal.write(
                JournalRecord.create(
                    JournalPhase.STAGED,
                    0,
                    outputTransaction,
                    localTransaction,
                ),
            )
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
                return
            }
        } catch (error: Throwable) {
            if (error is ForcedBuildTermination) {
                localTransaction?.abandonForCrash()
                outputTransaction.abandonForCrash()
            } else {
                localTransaction?.rollback(error)
                outputTransaction.rollback(error)
                runCatching { journal.delete() }.exceptionOrNull()
                    ?.let(error::addSuppressed)
            }
            throw error
        } finally {
            localTransaction?.finish()
            outputTransaction.finish()
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
        val journal = PublicationJournal(parent)
        val record = journal.read()
        if (record == null) {
            journal.delete()
            return
        }
        record.requireMatches(normalized, localSource)
        if (record.phase == JournalPhase.COMMIT_READY) {
            recoverCommitted(record, parent)
        } else {
            recoverOld(record, parent)
        }
        journal.delete()
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
        private var stagePath: Path? = null
        private var stageRootKey: String? = null
        private var published = false
        private var committed = false
        val targetCount: Int
            get() = targets.size

        fun stage() {
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
            val path = parent.resolve(".sixbridge-publish-${UUID.randomUUID()}")
            val created = OwnedTempTree.createAt(path)
            owner = created
            stagePath = path
            secureDirectory(path).use { stageHandle ->
                validateDirectoryHandle(path, stageHandle)
                stageRootKey = requireNotNull(
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        NOFOLLOW_LINKS,
                    ).fileKey(),
                ).toString()
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
        }

        fun publish(
            afterBackup: () -> Unit,
            afterPublished: (Int) -> Unit,
        ) {
            val path = requireNotNull(stagePath)
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
                        publishedCount += 1
                        afterPublished(publishedCount)
                    }
                    published = true
                }
            }
        }

        fun rollback(failure: Throwable) {
            if (committed || owner == null) return
            val path = requireNotNull(stagePath)
            runCatching {
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
                published = false
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }

        fun commit() {
            check(published)
            val path = requireNotNull(stagePath)
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
            requireNotNull(stagePath).fileName.toString()

        fun journalStageRootKey(): String = requireNotNull(stageRootKey)

        fun journalTargets(): List<JournalOutput> = targets.map { target ->
            JournalOutput(
                name = target.name.toString(),
                oldExists = target.original != null,
                oldSha256 = target.originalSha256,
                newSha256 = target.newSha256,
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

        fun stage() {
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
            val created = OwnedTempTree.createAt(stageOriginal)
            owner = created
            NativeWorkspaceTree.copyInto(source, stageOriginal)
            stageRootKey = requireNotNull(
                Files.readAttributes(
                    stageOriginal,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                ).fileKey(),
            ).toString()
            check(WorkspaceTreeDigest.capture(stageOriginal).provenanceSha256 ==
                requireNotNull(sourceState).provenanceSha256
            ) {
                "local source stage가 원본과 다릅니다."
            }
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
            published = true
            validateTreeNoLinks(destination)
            check(
                WorkspaceTreeDigest.capture(destination).provenanceSha256 ==
                    requireNotNull(sourceState).provenanceSha256,
            ) {
                "published local source가 검증된 stage와 다릅니다."
            }
        }

        fun rollback(failure: Throwable) {
            if (committed || owner == null) return
            runCatching {
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
            }.exceptionOrNull()?.let(failure::addSuppressed)
        }

        fun commit() {
            check(published)
            if (backupExists) {
                secureDirectory(parent).use { parentHandle ->
                    deleteTree(parentHandle, backupName)
                }
                backupExists = false
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
            oldRootKey = originalRootIdentity?.toString(),
        )
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
                        stageAttributes.fileKey().toString() == record.outputStageRootKey,
                ) {
                    "journal output stage identity가 변경되었습니다."
                }
                secureDirectory(stagePath).use { stageHandle ->
                    record.outputs.forEachIndexed { index, output ->
                        val targetName = singleName(output.name)
                        val backupName = singleName("backup-$index")
                        val targetSha = sha256OrNull(
                            outputHandle,
                            targetName,
                            outputParent.resolve(output.name),
                        )
                        val backupSha = sha256OrNull(
                            stageHandle,
                            backupName,
                            stagePath.resolve(backupName.toString()),
                        )
                        if (targetSha == output.newSha256) {
                            outputHandle.deleteFile(targetName)
                        } else if (targetSha != null &&
                            !(output.oldExists && targetSha == output.oldSha256)
                        ) {
                            throw IllegalStateException(
                                "journal recovery 대상 output이 외부에서 변경되었습니다.",
                            )
                        }
                        if (output.oldExists) {
                            if (backupSha != null) {
                                require(backupSha == output.oldSha256) {
                                    "journal output backup이 변경되었습니다."
                                }
                                require(attributesOrNull(outputHandle, targetName) == null) {
                                    "journal output 복구 대상이 비어 있지 않습니다."
                                }
                                stageHandle.move(backupName, outputHandle, targetName)
                            } else {
                                require(
                                    sha256OrNull(
                                        outputHandle,
                                        targetName,
                                        outputParent.resolve(output.name),
                                    ) == output.oldSha256,
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
                }
            } else {
                record.outputs.forEach { output ->
                    val sha = sha256OrNull(
                        outputHandle,
                        singleName(output.name),
                        outputParent.resolve(output.name),
                    )
                    require(
                        (output.oldExists && sha == output.oldSha256) ||
                            (!output.oldExists && sha == null),
                    ) {
                        "journal stage 없이 output 원본 상태를 확인할 수 없습니다."
                    }
                }
            }
        }
        record.local?.let(::recoverLocalOld)
    }

    private fun recoverCommitted(record: JournalRecord, outputParent: Path) {
        secureDirectory(outputParent).use { outputHandle ->
            record.outputs.forEach { output ->
                require(
                    sha256OrNull(
                        outputHandle,
                        singleName(output.name),
                        outputParent.resolve(output.name),
                    ) == output.newSha256,
                ) {
                    "commit-ready journal의 published output이 변경되었습니다."
                }
            }
            val stageName = singleName(record.outputStageName)
            attributesOrNull(outputHandle, stageName)?.let { attributes ->
                require(
                    attributes.isDirectory &&
                        !attributes.isSymbolicLink &&
                        attributes.fileKey().toString() == record.outputStageRootKey,
                ) {
                    "commit-ready output stage identity가 변경되었습니다."
                }
                deleteTree(outputHandle, stageName)
            }
        }
        record.local?.let(::recoverLocalCommitted)
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
                        WorkspaceTreeDigest.capture(
                            parent.resolve(local.stageName),
                        ).provenanceSha256 == local.newProvenanceSha256,
                ) {
                    "local source stage가 journal 기록과 다릅니다."
                }
                deleteTree(handle, stage)
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
                }
            }
        }
    }

    private data class JournalOutput(
        val name: String,
        val oldExists: Boolean,
        val oldSha256: String?,
        val newSha256: String,
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
        val oldRootKey: String?,
    )

    private enum class JournalPhase {
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
        val outputStageRootKey: String,
        val outputs: List<JournalOutput>,
        val local: JournalLocal?,
    ) {
        fun requireMatches(
            outputPaths: List<Path>,
            localRequest: LocalSourcePublication?,
        ) {
            require(
                outputs.map { it.name } ==
                    outputPaths.map { it.fileName.toString() },
            ) {
                "pending publish journal과 요청 output 집합이 다릅니다."
            }
            if (local == null) {
                require(localRequest == null)
            } else {
                requireNotNull(localRequest)
                val destination = localRequest.destination.toAbsolutePath().normalize()
                require(
                    destination.parent.toString() == local.parent &&
                        destination.fileName.toString() == local.destinationName,
                ) {
                    "pending publish journal과 local source 대상이 다릅니다."
                }
            }
        }

        companion object {
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
                output.journalTargets(),
                local?.journalLocal(),
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
                    handle.newByteChannel(
                        name,
                        setOf(READ, NOFOLLOW_LINKS),
                    ).use { it.readAllBytes() }.toString(Charsets.UTF_8),
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

        private fun encode(record: JournalRecord): String = buildString {
            appendLine("SIXBRIDGE-PUBLISH-JOURNAL-V1")
            appendLine(
                listOf(
                    "H",
                    record.phase.name,
                    record.publishedCount.toString(),
                    encodeField(record.outputStageName),
                    encodeField(record.outputStageRootKey),
                ).joinToString("|"),
            )
            record.outputs.forEach { output ->
                appendLine(
                    listOf(
                        "O",
                        encodeField(output.name),
                        output.oldExists.toString(),
                        encodeNullable(output.oldSha256),
                        output.newSha256,
                    ).joinToString("|"),
                )
            }
            record.local?.let { local ->
                appendLine(
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
                        encodeNullable(local.oldRootKey),
                    ).joinToString("|"),
                )
            }
        }

        private fun decode(text: String): JournalRecord {
            val lines = text.lineSequence().filter(String::isNotEmpty).toList()
            require(lines.firstOrNull() == "SIXBRIDGE-PUBLISH-JOURNAL-V1")
            val header = lines.getOrNull(1)?.split('|') ?: error("journal header 없음")
            require(header.size == 5 && header[0] == "H")
            val outputs = lines.drop(2).filter { it.startsWith("O|") }.map { line ->
                val fields = line.split('|')
                require(fields.size == 5)
                JournalOutput(
                    decodeField(fields[1]),
                    fields[2].toBooleanStrict(),
                    decodeNullable(fields[3]),
                    fields[4],
                )
            }
            val local = lines.drop(2).singleOrNull { it.startsWith("L|") }?.let { line ->
                val fields = line.split('|')
                require(fields.size == 11)
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
                    oldRootKey = decodeNullable(fields[10]),
                )
            }
            return JournalRecord(
                phase = JournalPhase.valueOf(header[1]),
                publishedCount = header[2].toInt(),
                outputStageName = decodeField(header[3]),
                outputStageRootKey = decodeField(header[4]),
                outputs = outputs,
                local = local,
            )
        }

        private fun encodeField(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decodeField(value: String): String =
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

        private fun encodeNullable(value: String?): String =
            value?.let(::encodeField) ?: "-"

        private fun decodeNullable(value: String): String? =
            value.takeUnless { it == "-" }?.let(::decodeField)
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
        val fileKey: Any,
        val size: Long,
        val modified: java.nio.file.attribute.FileTime,
        val links: Long,
    )

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
            fileKey = requireNotNull(attributes.fileKey()),
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sha256OrNull(
        handle: SecureDirectoryStream<Path>,
        name: Path,
        absolute: Path,
    ): String? {
        val state = targetState(handle, name, absolute) ?: return null
        require(state.links == 1L) {
            "journal recovery 파일의 hard link는 허용되지 않습니다."
        }
        return sha256(
            handle.newByteChannel(name, setOf(READ, NOFOLLOW_LINKS)).use {
                it.readAllBytes()
            },
        )
    }

    private fun forceDirectory(path: Path) {
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
    private const val JOURNAL_FILE = ".sixbridge-publish-journal.v1"
    private const val MAX_EXISTING_OUTPUT_BYTES = 512L * 1024 * 1024
}

private class ForcedBuildTermination :
    Error("injected forced build termination")

internal object BuildPublicationFaultInjection {
    private val beforeOutput = ThreadLocal<((Path) -> Unit)?>()
    private val afterLocal = ThreadLocal<(() -> Unit)?>()
    private val crashPoint = ThreadLocal<BuildCrashPoint?>()
    private val failCleanup = ThreadLocal<Boolean?>()

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
