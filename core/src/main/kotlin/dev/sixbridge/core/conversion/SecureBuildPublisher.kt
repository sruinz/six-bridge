package dev.sixbridge.core.conversion

import java.nio.ByteBuffer
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
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.jvm.JvmSynthetic

internal data class LocalSourcePublication(
    val source: Path,
    val destination: Path,
)

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
        val outputTransaction = OutputTransaction(parent, normalized)
        val localTransaction = localSource?.let(::LocalSourceTransaction)
        var published = false
        try {
            outputTransaction.stage()
            localTransaction?.stage()
            outputTransaction.publish()
            localTransaction?.publish()
            BuildPublicationFaultInjection.afterLocalPublish()
            published = true
            localTransaction?.commit()
            outputTransaction.commit()
        } catch (error: Throwable) {
            if (!published) {
                localTransaction?.rollback(error)
                outputTransaction.rollback(error)
            }
            throw error
        } finally {
            localTransaction?.finish()
            outputTransaction.finish()
        }
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
        private var published = false
        private var committed = false

        fun stage() {
            secureDirectory(parent).use { parentHandle ->
                validateDirectoryHandle(parent, parentHandle)
                targets.forEach { target ->
                    target.original = targetState(
                        parentHandle,
                        target.name,
                        target.path,
                    )
                }
            }
            val path = parent.resolve(".sixbridge-publish-${UUID.randomUUID()}")
            val created = OwnedTempTree.createAt(path)
            owner = created
            stagePath = path
            secureDirectory(path).use { stageHandle ->
                validateDirectoryHandle(path, stageHandle)
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

        fun publish() {
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
        }

        fun finish() {
            val retainedOwner = owner ?: return
            runCatching { OwnedTempTree.delete(retainedOwner) }
            owner = null
        }
    }

    private class LocalSourceTransaction(
        request: LocalSourcePublication,
    ) {
        private val source = request.source.toAbsolutePath().normalize()
        private val destination = request.destination.toAbsolutePath().normalize()
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

        fun stage() {
            require(source != destination) {
                "local source와 저장 대상이 같을 수 없습니다."
            }
            validateTreeNoLinks(source)
            sourceState = WorkspaceTreeDigest.capture(source)
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
    }

    private data class OutputTarget(
        val index: Int,
        val path: Path,
        val name: Path,
        val bytes: ByteArray,
        var original: FileState? = null,
        var staged: FileState? = null,
        var stagedName: Path? = null,
        var backupName: Path? = null,
        var wasPublished: Boolean = false,
    )

    private data class FileState(
        val fileKey: Any,
        val size: Long,
        val modified: java.nio.file.attribute.FileTime,
        val links: Long?,
    )

    private fun targetState(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        absolute: Path,
    ): FileState? {
        val attributes = attributesOrNull(directory, name) ?: return null
        attributes.checkedRegular("output")
        val links = unixLinks(absolute)
        require(links == null || links == 1L) {
            "출력 경로의 hard link는 허용되지 않습니다: $absolute"
        }
        return FileState(
            fileKey = requireNotNull(attributes.fileKey()),
            size = attributes.size(),
            modified = attributes.lastModifiedTime(),
            links = links,
        )
    }

    private fun BasicFileAttributes.checkedRegular(label: String): FileState {
        require(isRegularFile && !isSymbolicLink) {
            "$label 경로는 symlink가 아닌 일반 파일이어야 합니다."
        }
        return FileState(
            fileKey = requireNotNull(fileKey()),
            size = size(),
            modified = lastModifiedTime(),
            links = null,
        )
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
                    val links = unixLinks(path)
                    require(links == null || links == 1L) {
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

    private fun unixLinks(path: Path): Long? = runCatching {
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
    }.getOrNull()

    private fun singleName(value: String): Path =
        Path.of(value).also { name ->
            require(name.nameCount == 1 && name.toString() !in setOf(".", "..")) {
                "단일 파일명만 허용됩니다."
            }
        }

    private const val MAX_EXISTING_OUTPUT_BYTES = 512L * 1024 * 1024
}

internal object BuildPublicationFaultInjection {
    private val beforeOutput = ThreadLocal<((Path) -> Unit)?>()
    private val afterLocal = ThreadLocal<(() -> Unit)?>()

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
}
