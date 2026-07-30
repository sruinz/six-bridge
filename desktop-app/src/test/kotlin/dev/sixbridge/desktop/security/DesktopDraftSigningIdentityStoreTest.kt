package dev.sixbridge.desktop.security

import dev.sixbridge.core.draft.DraftSignature
import dev.sixbridge.desktop.DesktopApplicationPaths
import dev.sixbridge.desktop.DesktopHostOs
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDraftSigningIdentityStoreTest {
    @Test
    fun `macOS signer는 owner-only root와 파일을 만들고 재시작 뒤 fingerprint를 유지한다`() {
        val paths = newPaths("desktop-draft-mac-stable")

        val first = DesktopDraftSigningIdentityStore(paths, DesktopHostOs.MAC).getOrCreate()
        val second = DesktopDraftSigningIdentityStore(paths, DesktopHostOs.MAC).getOrCreate()

        assertEquals(
            DraftSignature.fingerprint(first.public.encoded),
            DraftSignature.fingerprint(second.public.encoded),
        )
        listOf(paths.dataRoot, paths.workRoot, paths.draftKeyRoot).forEach {
            assertEquals(DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(it, NOFOLLOW_LINKS))
        }
        paths.draftKeyRoot.listDirectoryEntries().forEach {
            assertEquals(FILE_PERMISSIONS, Files.getPosixFilePermissions(it, NOFOLLOW_LINKS))
        }
        assertEquals(
            setOf(
                ".sixbridge-draft-key.lock",
                "draft-transfer.identity",
                "draft-transfer.pk8",
                "draft-transfer.pub",
            ),
            paths.draftKeyRoot.listDirectoryEntries().map { it.fileName.toString() }.toSet(),
        )
    }

    @Test
    fun `기존 macOS root의 넓은 권한은 자동 수정하지 않고 key를 만들지 않는다`() {
        val paths = newPaths("desktop-draft-mac-wide")
        Files.createDirectory(paths.dataRoot)
        Files.setPosixFilePermissions(paths.dataRoot, DIRECTORY_PERMISSIONS)
        Files.createDirectory(paths.workRoot)
        val wide = DIRECTORY_PERMISSIONS + PosixFilePermission.GROUP_READ
        Files.setPosixFilePermissions(paths.workRoot, wide)

        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(paths, DesktopHostOs.MAC).getOrCreate()
        }

        assertEquals(wide, Files.getPosixFilePermissions(paths.workRoot, NOFOLLOW_LINKS))
        assertFalse(Files.exists(paths.draftKeyRoot, NOFOLLOW_LINKS))
    }

    @Test
    fun `macOS symlink와 non-directory root는 외부나 기존 항목을 수정하지 않는다`() {
        val parent = Files.createTempDirectory("desktop-draft-mac-links")
        val outside = Files.createDirectory(parent.resolve("outside"))
        val linkedPaths = pathsAt(parent.resolve("linked-data"))
        Files.createSymbolicLink(linkedPaths.dataRoot, outside)

        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(linkedPaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertTrue(Files.list(outside).use { it.findAny().isEmpty })

        val filePaths = pathsAt(parent.resolve("file-data"))
        Files.write(filePaths.dataRoot, byteArrayOf(1))
        Files.setPosixFilePermissions(filePaths.dataRoot, FILE_PERMISSIONS)
        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(filePaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(filePaths.dataRoot))
    }

    @Test
    fun `macOS 부분 key pair와 unknown 파일은 자동 복구하거나 삭제하지 않는다`() {
        val partialPaths = newPaths("desktop-draft-mac-partial")
        createExistingRoots(partialPaths)
        val privateKey = partialPaths.draftKeyRoot.resolve("draft-transfer.pk8")
        Files.write(privateKey, byteArrayOf(1))
        Files.setPosixFilePermissions(privateKey, FILE_PERMISSIONS)
        val partialBefore = snapshot(partialPaths.draftKeyRoot)

        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(partialPaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertEquals(partialBefore.keys, snapshot(partialPaths.draftKeyRoot).keys)
        assertContentEquals(partialBefore.getValue("draft-transfer.pk8"), Files.readAllBytes(privateKey))

        val unknownPaths = newPaths("desktop-draft-mac-unknown")
        DesktopDraftSigningIdentityStore(unknownPaths, DesktopHostOs.MAC).getOrCreate()
        val unknown = unknownPaths.draftKeyRoot.resolve("foreign.txt")
        Files.writeString(unknown, "foreign")
        Files.setPosixFilePermissions(unknown, FILE_PERMISSIONS)

        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(unknownPaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertEquals("foreign", Files.readString(unknown))
    }

    @Test
    fun `macOS mismatched pair와 넓어진 key 권한은 기존 private key를 보존하고 실패한다`() {
        val firstPaths = newPaths("desktop-draft-mac-mismatch-first")
        val secondPaths = newPaths("desktop-draft-mac-mismatch-second")
        DesktopDraftSigningIdentityStore(firstPaths, DesktopHostOs.MAC).getOrCreate()
        DesktopDraftSigningIdentityStore(secondPaths, DesktopHostOs.MAC).getOrCreate()
        val firstPrivate = firstPaths.draftKeyRoot.resolve("draft-transfer.pk8")
        val firstPublic = firstPaths.draftKeyRoot.resolve("draft-transfer.pub")
        val originalPrivate = Files.readAllBytes(firstPrivate)
        Files.copy(
            secondPaths.draftKeyRoot.resolve("draft-transfer.pub"),
            firstPublic,
            REPLACE_EXISTING,
        )

        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(firstPaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertContentEquals(originalPrivate, Files.readAllBytes(firstPrivate))

        Files.setPosixFilePermissions(
            firstPublic,
            FILE_PERMISSIONS + PosixFilePermission.GROUP_READ,
        )
        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(firstPaths, DesktopHostOs.MAC).getOrCreate()
        }
        assertTrue(PosixFilePermission.GROUP_READ in Files.getPosixFilePermissions(firstPublic))
        assertContentEquals(originalPrivate, Files.readAllBytes(firstPrivate))
    }

    @Test
    fun `macOS atomic move 실패도 외부 파일을 지우지 않고 orphan을 보존한다`() {
        val paths = newPaths("desktop-draft-mac-atomic")
        val outside = paths.dataRoot.parent.resolve("outside.txt")
        Files.writeString(outside, "outside")
        val store = DesktopDraftSigningIdentityStore.testingMac(
            paths = paths,
            atomicMove = WindowsAtomicMove { source, target ->
                throw AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "unsupported",
                )
            },
        )

        assertFailsWith<IllegalStateException> { store.getOrCreate() }

        val names = paths.draftKeyRoot.listDirectoryEntries()
            .map { it.fileName.toString() }
            .toSet()
        assertEquals(4, names.size)
        assertTrue(".sixbridge-draft-key.lock" in names)
        assertEquals(3, names.count { it.startsWith(".sixbridge-draft-key.orphan-") })
        assertTrue(names.none { it in FINAL_KEY_FILES })
        assertEquals("outside", Files.readString(outside))
        assertFailsWith<IllegalStateException> {
            DesktopDraftSigningIdentityStore(paths, DesktopHostOs.MAC).getOrCreate()
        }
        assertEquals(names, paths.draftKeyRoot.listDirectoryEntries().map {
            it.fileName.toString()
        }.toSet())
        assertEquals("outside", Files.readString(outside))
    }

    @Test
    fun `Windows store는 fake ACL 경계에서도 stable pair와 기존 손상 fail-closed를 유지한다`() {
        val acl = FakeWindowsAclAccess()
        val paths = newPaths("desktop-draft-windows-stable")
        val first = windowsStore(paths, acl).getOrCreate()
        val second = windowsStore(paths, acl).getOrCreate()

        assertContentEquals(first.public.encoded, second.public.encoded)

        val unknown = paths.draftKeyRoot.resolve("foreign.txt")
        Files.writeString(unknown, "foreign")
        assertFailsWith<IllegalStateException> { windowsStore(paths, acl).getOrCreate() }
        assertEquals("foreign", Files.readString(unknown))
    }

    @Test
    fun `Windows reparse와 ACL 확대는 자동 수정하거나 외부를 변경하지 않는다`() {
        val acl = FakeWindowsAclAccess()
        val parent = Files.createTempDirectory("desktop-draft-windows-reparse")
        val paths = pathsAt(parent.resolve("data"))
        Files.createDirectory(paths.dataRoot)
        Files.createDirectory(paths.workRoot)
        val outside = Files.createDirectory(parent.resolve("outside"))
        Files.createSymbolicLink(paths.draftKeyRoot, outside)

        assertFailsWith<IllegalStateException> { windowsStore(paths, acl).getOrCreate() }
        assertTrue(Files.list(outside).use { it.findAny().isEmpty })

        val aclPaths = newPaths("desktop-draft-windows-acl")
        windowsStore(aclPaths, acl).getOrCreate()
        val publicKey = aclPaths.draftKeyRoot.resolve("draft-transfer.pub")
        val original = Files.readAllBytes(publicKey)
        acl.widen(publicKey)

        assertFailsWith<IllegalStateException> { windowsStore(aclPaths, acl).getOrCreate() }
        assertContentEquals(original, Files.readAllBytes(publicKey))
        assertTrue(acl.isWide(publicKey))
    }

    @Test
    fun `Windows partial과 mismatched pair는 자동 재생성하지 않는다`() {
        val acl = FakeWindowsAclAccess()
        val partialPaths = newPaths("desktop-draft-windows-partial")
        createExistingRoots(partialPaths)
        val partial = partialPaths.draftKeyRoot.resolve("draft-transfer.pk8")
        Files.write(partial, byteArrayOf(1))

        assertFailsWith<IllegalStateException> {
            windowsStore(partialPaths, acl).getOrCreate()
        }
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(partial))

        val firstPaths = newPaths("desktop-draft-windows-mismatch-first")
        val secondPaths = newPaths("desktop-draft-windows-mismatch-second")
        windowsStore(firstPaths, acl).getOrCreate()
        windowsStore(secondPaths, acl).getOrCreate()
        val firstPrivate = firstPaths.draftKeyRoot.resolve("draft-transfer.pk8")
        val originalPrivate = Files.readAllBytes(firstPrivate)
        Files.copy(
            secondPaths.draftKeyRoot.resolve("draft-transfer.pub"),
            firstPaths.draftKeyRoot.resolve("draft-transfer.pub"),
            REPLACE_EXISTING,
        )

        assertFailsWith<IllegalStateException> {
            windowsStore(firstPaths, acl).getOrCreate()
        }
        assertContentEquals(originalPrivate, Files.readAllBytes(firstPrivate))
    }

    @Test
    fun `Windows atomic move 실패는 외부 파일을 지우지 않고 orphan만 남겨 다음 실행을 거부한다`() {
        val paths = newPaths("desktop-draft-windows-atomic")
        val outside = paths.dataRoot.parent.resolve("outside.txt")
        Files.writeString(outside, "outside")
        val acl = FakeWindowsAclAccess()
        val store = DesktopDraftSigningIdentityStore.testingWindows(
            paths = paths,
            aclAccess = acl,
            atomicMove = WindowsAtomicMove { source, target ->
                throw AtomicMoveNotSupportedException(
                    source.toString(),
                    target.toString(),
                    "unsupported",
                )
            },
        )

        assertFailsWith<IllegalStateException> { store.getOrCreate() }

        val names = paths.draftKeyRoot.listDirectoryEntries()
            .map { it.fileName.toString() }
            .toSet()
        assertEquals(4, names.size)
        assertTrue(".sixbridge-draft-key.lock" in names)
        assertEquals(3, names.count { it.startsWith(".sixbridge-draft-key.orphan-") })
        assertTrue(names.none { it in FINAL_KEY_FILES })
        assertEquals("outside", Files.readString(outside))

        val orphaned = snapshot(paths.draftKeyRoot)
        assertFailsWith<IllegalStateException> {
            windowsStore(paths, acl).getOrCreate()
        }
        assertEquals(orphaned.keys, snapshot(paths.draftKeyRoot).keys)
        orphaned.forEach { (name, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(paths.draftKeyRoot.resolve(name)))
        }
        assertEquals("outside", Files.readString(outside))
    }

    private fun windowsStore(
        paths: DesktopApplicationPaths,
        acl: WindowsAclAccess,
    ): DesktopDraftSigningIdentityStore =
        DesktopDraftSigningIdentityStore.testingWindows(
            paths = paths,
            aclAccess = acl,
            atomicMove = WindowsAtomicMove { source, target ->
                Files.move(source, target, ATOMIC_MOVE)
            },
        )

    private fun newPaths(prefix: String): DesktopApplicationPaths =
        pathsAt(Files.createTempDirectory(prefix).resolve("data"))

    private fun pathsAt(dataRoot: Path): DesktopApplicationPaths = DesktopApplicationPaths(
        dataRoot = dataRoot,
        workRoot = dataRoot.resolve("work"),
        draftKeyRoot = dataRoot.resolve("draft-signing"),
    )

    private fun createExistingRoots(paths: DesktopApplicationPaths) {
        Files.createDirectory(paths.dataRoot)
        Files.setPosixFilePermissions(paths.dataRoot, DIRECTORY_PERMISSIONS)
        Files.createDirectory(paths.workRoot)
        Files.setPosixFilePermissions(paths.workRoot, DIRECTORY_PERMISSIONS)
        Files.createDirectory(paths.draftKeyRoot)
        Files.setPosixFilePermissions(paths.draftKeyRoot, DIRECTORY_PERMISSIONS)
    }

    private fun snapshot(root: Path): Map<String, ByteArray> =
        root.listDirectoryEntries().associate {
            it.fileName.toString() to Files.readAllBytes(it)
        }

    private class FakeWindowsAclAccess : WindowsAclAccess {
        private val wide = mutableSetOf<Path>()

        override fun applyOwnerOnly(path: Path) {
            wide.remove(path.toAbsolutePath().normalize())
        }

        override fun requireOwnerOnly(path: Path) {
            require(path.toAbsolutePath().normalize() !in wide) {
                "Windows ACL이 owner-only가 아닙니다."
            }
        }

        fun widen(path: Path) {
            wide.add(path.toAbsolutePath().normalize())
        }

        fun isWide(path: Path): Boolean =
            path.toAbsolutePath().normalize() in wide
    }

    companion object {
        private val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        private val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val FINAL_KEY_FILES = setOf(
            "draft-transfer.identity",
            "draft-transfer.pk8",
            "draft-transfer.pub",
        )
    }
}
