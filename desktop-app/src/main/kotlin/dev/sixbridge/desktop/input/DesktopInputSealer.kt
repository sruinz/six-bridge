package dev.sixbridge.desktop.input

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeCrxDownloader
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.input.UnsupportedInputException
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.conversion.DesktopWorkspace
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.util.UUID

class DesktopInputChangedException internal constructor() :
    IllegalArgumentException("입력이 봉인 중 변경되었습니다.")

class DesktopInputSealer private constructor(
    private val chromeDownloader: ChromeCrxDownloader,
    private val afterLocalRead: (Path) -> Unit,
    private val afterSourceRead: (Path) -> Unit,
) {
    constructor(chromeDownloader: ChromeCrxDownloader) : this(
        chromeDownloader,
        {},
        {},
    )

    fun seal(
        input: DesktopExtensionInput,
        workspace: DesktopWorkspace,
        control: AnalysisControl,
    ): ResolvedExtensionInput {
        control.checkCancelled()
        require(
            workspace.root.fileSystem.isOpen &&
                workspace.inputRoot.fileSystem == workspace.root.fileSystem &&
                Files.isDirectory(workspace.inputRoot, NOFOLLOW_LINKS),
        ) {
            "desktop workspace가 열려 있지 않습니다."
        }
        val child = Files.createDirectory(
            workspace.inputRoot.resolve("input-${UUID.randomUUID()}"),
        )
        return try {
            when (input) {
                is DesktopExtensionInput.ChromeStore ->
                    sealChromeStore(input.url, child, control)
                is DesktopExtensionInput.Local ->
                    sealLocal(input.path.toAbsolutePath().normalize(), child, control)
            }
        } catch (error: Throwable) {
            deleteTreeBestEffort(child)
            throw error
        }
    }

    private fun sealChromeStore(
        url: String,
        child: Path,
        control: AnalysisControl,
    ): ResolvedExtensionInput {
        val address = ChromeWebStoreAddress.parse(url)
        control.checkCancelled()
        val bytes = chromeDownloader.download(address)
        control.checkCancelled()
        require(bytes.size.toLong() <= MAX_ARCHIVE_BYTES) {
            "다운로드한 CRX3가 허용 크기를 초과합니다."
        }
        val archive = child.resolve("input.crx")
        Files.write(archive, bytes, StandardOpenOption.CREATE_NEW)
        val sealedBytes = Files.readAllBytes(archive)
        val parsed = Crx3.parse(sealedBytes)
        require(parsed.extensionId == address.extensionId) {
            "다운로드한 CRX3 ID가 요청 ID와 다릅니다."
        }
        requireArchiveDepth(parsed.zipBytes)
        val source = extractCrx(parsed.zipBytes, child, control)
        return ResolvedExtensionInput(
            kind = ExtensionInputKind.CHROME_WEB_STORE,
            originalInput = "desktop-chrome-store",
            sourceRoot = source,
            crxBytes = sealedBytes,
            expectedExtensionId = parsed.extensionId,
            archivePath = archive,
        )
    }

    private fun sealLocal(
        input: Path,
        child: Path,
        control: AnalysisControl,
    ): ResolvedExtensionInput {
        val shape = readHostAttributes(input)
        return when {
            shape.isDirectory && !shape.isSymbolicLink ->
                sealSource(input, child, control)
            shape.isRegularFile && !shape.isSymbolicLink ->
                sealArchive(input, child, control)
            else -> throw UnsupportedInputException(
                "지원하지 않는 local input 형식입니다.",
            )
        }
    }

    private fun sealArchive(
        input: Path,
        child: Path,
        control: AnalysisControl,
    ): ResolvedExtensionInput {
        val extension = input.fileName.toString().substringAfterLast('.', "").lowercase()
        val kind = when (extension) {
            "zip" -> ExtensionInputKind.ZIP
            "crx", "crx3" -> ExtensionInputKind.CRX3
            else -> throw UnsupportedInputException(
                "지원하지 않는 local archive 형식입니다.",
            )
        }
        val before = stableHostFile(input)
        require(before.size <= MAX_ARCHIVE_BYTES) {
            "local archive가 허용 크기를 초과합니다."
        }
        val archive = child.resolve(
            if (kind == ExtensionInputKind.ZIP) "input.zip" else "input.crx",
        )
        copyHostFileOnce(input, before, archive, control)
        afterLocalRead(input)
        val after = stableHostFile(input)
        if (before != after) throw DesktopInputChangedException()
        control.checkCancelled()

        val sealedBytes = Files.readAllBytes(archive)
        return if (kind == ExtensionInputKind.ZIP) {
            requireArchiveDepth(sealedBytes)
            val extraction = Files.createDirectory(child.resolve("extracted"))
            val source = ExtensionZip.extractInto(archive, extraction, control)
            ResolvedExtensionInput(
                kind = kind,
                originalInput = "desktop-local",
                sourceRoot = source,
                archivePath = archive,
            )
        } else {
            val parsed = Crx3.parse(sealedBytes)
            requireArchiveDepth(parsed.zipBytes)
            val source = extractCrx(parsed.zipBytes, child, control)
            ResolvedExtensionInput(
                kind = kind,
                originalInput = "desktop-local",
                sourceRoot = source,
                crxBytes = sealedBytes,
                expectedExtensionId = parsed.extensionId,
                archivePath = archive,
            )
        }
    }

    private fun sealSource(
        source: Path,
        child: Path,
        control: AnalysisControl,
    ): ResolvedExtensionInput {
        val currentOwner = currentUserOwner()
        val first = captureInventory(source, currentOwner, control)
        val staging = Files.createDirectory(child.resolve("source"))
        first.entries.filter(SourceInventoryEntry::directory).forEach { entry ->
            Files.createDirectories(staging.resolve(entry.relative))
        }
        first.entries.filterNot(SourceInventoryEntry::directory).forEach { entry ->
            control.checkCancelled()
            val target = staging.resolve(entry.relative)
            Files.createDirectories(target.parent)
            copyHostFileOnce(entry.path, entry.state, target, control)
        }
        afterSourceRead(source)
        val second = captureInventory(source, currentOwner, control)
        if (first != second) throw DesktopInputChangedException()

        val marker = staging.resolve(PROJECT_MARKER_FILE)
        val projectIdentity = if (Files.exists(marker, NOFOLLOW_LINKS)) {
            parseProjectMarker(Files.readString(marker))
        } else {
            UUID.randomUUID().toString().replace("-", "").also { identity ->
                Files.writeString(
                    marker,
                    "$PROJECT_MARKER_PREFIX$identity",
                    StandardOpenOption.CREATE_NEW,
                )
            }
        }
        val canonicalZip = ExtensionZip.createBytes(
            staging,
            includeProjectMarker = true,
        )
        val archive = child.resolve("source.zip")
        Files.write(archive, canonicalZip, StandardOpenOption.CREATE_NEW)
        val extraction = Files.createDirectory(child.resolve("extracted"))
        val extracted = ExtensionZip.extractInto(archive, extraction, control)
        return ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = "desktop-local",
            sourceRoot = extracted,
            projectIdentity = projectIdentity,
            archivePath = archive,
        )
    }

    private fun extractCrx(
        zipBytes: ByteArray,
        child: Path,
        control: AnalysisControl,
    ): Path {
        val extraction = Files.createDirectory(child.resolve("extracted"))
        return ExtensionZip.extractInto(zipBytes, extraction, control)
    }

    private fun captureInventory(
        source: Path,
        currentOwner: UserPrincipal,
        control: AnalysisControl,
    ): SourceInventory {
        val root = stableSourcePath(source, currentOwner, directory = true)
        val entries = mutableListOf<SourceInventoryEntry>()
        val fileKeys = mutableSetOf<Any>()
        var fileCount = 0
        var totalBytes = 0L
        try {
            Files.walkFileTree(
                source,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        control.checkCancelled()
                        if (directory != source) {
                            val relative = source.relativize(directory)
                            if (relative.any { it.toString() in EXCLUDED_DIRECTORIES }) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            requireDepth(relative)
                            val state = stableSourcePath(
                                directory,
                                currentOwner,
                                directory = true,
                            )
                            require(fileKeys.add(state.fileKey)) {
                                "source에 중복 file identity가 있습니다."
                            }
                            entries += SourceInventoryEntry(
                                path = directory,
                                relative = portableRelativeForTesting(relative),
                                directory = true,
                                state = state,
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        control.checkCancelled()
                        val relative = source.relativize(file)
                        requireDepth(relative)
                        if (relative.fileName.toString() in EXCLUDED_FILES) {
                            return FileVisitResult.CONTINUE
                        }
                        val state = stableSourcePath(
                            file,
                            currentOwner,
                            directory = false,
                        )
                        require(fileKeys.add(state.fileKey)) {
                            "source에 중복 file identity가 있습니다."
                        }
                        require(state.size <= MAX_ENTRY_BYTES) {
                            "source file이 개별 크기 제한을 초과합니다."
                        }
                        fileCount += 1
                        require(fileCount <= MAX_ENTRY_COUNT) {
                            "source file 수가 제한을 초과합니다."
                        }
                        require(state.size <= MAX_TOTAL_BYTES - totalBytes) {
                            "source 전체 크기가 제한을 초과합니다."
                        }
                        totalBytes += state.size
                        entries += SourceInventoryEntry(
                            path = file,
                            relative = portableRelativeForTesting(relative),
                            directory = false,
                            state = state,
                        )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (error: DesktopInputChangedException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: IOException) {
            throw DesktopInputChangedException()
        }
        return SourceInventory(
            root = root,
            entries = entries.sortedBy(SourceInventoryEntry::relative),
        )
    }

    private fun stableSourcePath(
        path: Path,
        currentOwner: UserPrincipal,
        directory: Boolean,
    ): HostFileState {
        val attributes = readHostAttributes(path)
        require(
            !attributes.isSymbolicLink &&
                !attributes.isOther &&
                if (directory) attributes.isDirectory else attributes.isRegularFile,
        ) {
            "source에는 실제 디렉터리와 일반 파일만 허용됩니다."
        }
        val owner = try {
            Files.getOwner(path, NOFOLLOW_LINKS)
        } catch (_: IOException) {
            throw DesktopInputChangedException()
        }
        require(sameOwner(owner, currentOwner)) {
            "source는 현재 사용자 소유여야 합니다."
        }
        Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )?.let { view ->
            val permissions = try {
                view.readAttributes().permissions()
            } catch (_: IOException) {
                throw DesktopInputChangedException()
            }
            require(
                PosixFilePermission.GROUP_WRITE !in permissions &&
                    PosixFilePermission.OTHERS_WRITE !in permissions,
            ) {
                "source는 group 또는 other가 쓸 수 없어야 합니다."
            }
        }
        return HostFileState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "source file identity를 확인할 수 없습니다."
            },
            size = attributes.size(),
            modified = attributes.lastModifiedTime(),
        )
    }

    private fun currentUserOwner(): UserPrincipal =
        try {
            Files.getOwner(Path.of(System.getProperty("user.home")), NOFOLLOW_LINKS)
        } catch (_: IOException) {
            throw IllegalArgumentException("현재 사용자 owner를 확인할 수 없습니다.")
        }

    private fun sameOwner(actual: UserPrincipal, expected: UserPrincipal): Boolean =
        actual == expected || actual.name == expected.name

    private fun stableHostFile(path: Path): HostFileState {
        val attributes = readHostAttributes(path)
        if (
            !attributes.isRegularFile ||
            attributes.isSymbolicLink ||
            attributes.isOther
        ) {
            throw DesktopInputChangedException()
        }
        return HostFileState(
            fileKey = requireNotNull(attributes.fileKey()) {
                "local archive identity를 확인할 수 없습니다."
            },
            size = attributes.size(),
            modified = attributes.lastModifiedTime(),
        )
    }

    private fun copyHostFileOnce(
        source: Path,
        expected: HostFileState,
        target: Path,
        control: AnalysisControl,
    ) {
        val options = setOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW_LINKS)
        try {
            FileChannel.open(source, options).use { input ->
                require(input.size() == expected.size) {
                    "입력 크기가 봉인 직전에 변경되었습니다."
                }
                Files.newByteChannel(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        control.checkCancelled()
                        buffer.clear()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(read > 0) { "입력 읽기가 진행되지 않습니다." }
                        copied += read
                        require(copied <= expected.size) {
                            "입력 크기가 봉인 중 변경되었습니다."
                        }
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
                    }
                    require(copied == expected.size) {
                        "입력이 봉인 중 잘렸습니다."
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: IOException) {
            throw DesktopInputChangedException()
        }
        if (stableHostFile(source) != expected) throw DesktopInputChangedException()
    }

    private fun readHostAttributes(path: Path): BasicFileAttributes =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: IOException) {
            throw DesktopInputChangedException()
        }

    private fun requireDepth(relative: Path) {
        require(relative.nameCount <= MAX_PATH_DEPTH) {
            "source path depth가 제한을 초과합니다."
        }
    }

    private fun requireArchiveDepth(zipBytes: ByteArray) {
        require(zipBytes.size >= ZIP_END_SIZE) {
            "ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        val start = maxOf(0, zipBytes.size - ZIP_END_SIZE - MAX_ZIP_COMMENT)
        val end = (zipBytes.size - ZIP_END_SIZE downTo start).firstOrNull { offset ->
            int32(zipBytes, offset) == ZIP_END_SIGNATURE &&
                offset + ZIP_END_SIZE + uint16(zipBytes, offset + 20) == zipBytes.size
        } ?: throw IllegalArgumentException("ZIP 중앙 디렉터리를 찾지 못했습니다.")
        val count = uint16(zipBytes, end + 10)
        require(count <= MAX_ENTRY_COUNT) { "ZIP 항목 수가 제한을 초과합니다." }
        var offset = uint32(zipBytes, end + 16).toInt()
        repeat(count) {
            require(offset >= 0 && offset <= zipBytes.size - ZIP_CENTRAL_SIZE) {
                "ZIP 중앙 디렉터리가 손상되었습니다."
            }
            require(int32(zipBytes, offset) == ZIP_CENTRAL_SIGNATURE) {
                "ZIP 중앙 디렉터리가 손상되었습니다."
            }
            val nameLength = uint16(zipBytes, offset + 28)
            val extraLength = uint16(zipBytes, offset + 30)
            val commentLength = uint16(zipBytes, offset + 32)
            val nameStart = offset + ZIP_CENTRAL_SIZE
            val nameEnd = nameStart + nameLength
            require(nameEnd <= zipBytes.size) {
                "ZIP 중앙 디렉터리가 손상되었습니다."
            }
            val raw = zipBytes.copyOfRange(nameStart, nameEnd).decodeToString()
            val depth = raw.trimEnd('/').split('/').size
            require(depth <= MAX_PATH_DEPTH) {
                "ZIP path depth가 제한을 초과합니다."
            }
            offset = nameEnd + extraLength + commentLength
        }
    }

    private fun parseProjectMarker(value: String): String {
        val match = PROJECT_MARKER_FORMAT.matchEntire(value.trim())
            ?: throw IllegalArgumentException(
                "source project marker 형식이 올바르지 않습니다.",
            )
        return match.groupValues[1]
    }

    private fun deleteTreeBestEffort(root: Path) {
        runCatching {
            if (!Files.exists(root, NOFOLLOW_LINKS)) return@runCatching
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }

    private fun int32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        int32(bytes, offset).toLong() and 0xffff_ffffL

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xffff

    companion object {
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val MAX_PATH_DEPTH = 64
        const val MAX_COMPRESSION_RATIO = 100

        private const val PROJECT_MARKER_FILE = ".sixbridge-project-id"
        private const val PROJECT_MARKER_PREFIX = "sixbridge-project-v1:"
        private val PROJECT_MARKER_FORMAT =
            Regex("$PROJECT_MARKER_PREFIX([0-9a-f]{32})")
        private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
        private val EXCLUDED_FILES = setOf(".DS_Store")
        private const val ZIP_END_SIGNATURE = 0x06054b50
        private const val ZIP_CENTRAL_SIGNATURE = 0x02014b50
        private const val ZIP_END_SIZE = 22
        private const val ZIP_CENTRAL_SIZE = 46
        private const val MAX_ZIP_COMMENT = 65_535

        internal fun testing(
            chromeDownloader: ChromeCrxDownloader,
            afterLocalRead: (Path) -> Unit,
            afterSourceRead: (Path) -> Unit = {},
        ): DesktopInputSealer = DesktopInputSealer(
            chromeDownloader,
            afterLocalRead,
            afterSourceRead,
        )

        internal fun portableRelativeForTesting(relative: Path): String =
            relative.joinToString("/") { it.toString() }
    }

    private data class HostFileState(
        val fileKey: Any,
        val size: Long,
        val modified: FileTime,
    )

    private data class SourceInventory(
        val root: HostFileState,
        val entries: List<SourceInventoryEntry>,
    )

    private data class SourceInventoryEntry(
        val path: Path,
        val relative: String,
        val directory: Boolean,
        val state: HostFileState,
    ) {
        override fun equals(other: Any?): Boolean =
            other is SourceInventoryEntry &&
                relative == other.relative &&
                directory == other.directory &&
                state == other.state

        override fun hashCode(): Int {
            var result = relative.hashCode()
            result = 31 * result + directory.hashCode()
            result = 31 * result + state.hashCode()
            return result
        }
    }
}
