package dev.sixbridge.desktop.security

import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsOwnerAclTest {
    @Test
    fun `Windows ACL은 현재 owner 하나만 허용한다`() {
        val owner = FakePrincipal("current-user")
        val entries = WindowsOwnerAcl.ownerOnly(owner)

        assertEquals(1, entries.size)
        assertEquals(AclEntryType.ALLOW, entries.single().type())
        assertEquals("current-user", entries.single().principal().name)
        assertEquals(
            EnumSet.allOf(AclEntryPermission::class.java),
            entries.single().permissions(),
        )
        WindowsOwnerAcl.requireOwnerOnly(owner, entries)
    }

    @Test
    fun `Windows ACL validator는 owner가 다른 entry를 거부한다`() {
        assertFailsWith<IllegalStateException> {
            WindowsOwnerAcl.requireOwnerOnly(
                owner = FakePrincipal("current-user"),
                entries = WindowsOwnerAcl.ownerOnly(FakePrincipal("other-user")),
            )
        }
    }

    @Test
    fun `Windows ACL validator는 ALLOW 외 type을 거부한다`() {
        val owner = FakePrincipal("current-user")

        assertFailsWith<IllegalStateException> {
            WindowsOwnerAcl.requireOwnerOnly(
                owner = owner,
                entries = listOf(
                    entry(
                        owner = owner,
                        type = AclEntryType.DENY,
                        permissions = EnumSet.allOf(AclEntryPermission::class.java),
                    ),
                ),
            )
        }
    }

    @Test
    fun `Windows ACL validator는 entry 추가와 중복을 거부한다`() {
        val owner = FakePrincipal("current-user")
        val valid = WindowsOwnerAcl.ownerOnly(owner).single()
        val other = WindowsOwnerAcl.ownerOnly(FakePrincipal("other-user")).single()

        listOf(
            listOf(valid, valid),
            listOf(valid, other),
        ).forEach { entries ->
            assertFailsWith<IllegalStateException> {
                WindowsOwnerAcl.requireOwnerOnly(owner, entries)
            }
        }
    }

    @Test
    fun `Windows ACL validator는 permission 누락과 다른 principal 권한 확대를 거부한다`() {
        val owner = FakePrincipal("current-user")
        val missingPermission = EnumSet.allOf(AclEntryPermission::class.java).apply {
            remove(AclEntryPermission.READ_DATA)
        }
        val expandedToOther = listOf(
            WindowsOwnerAcl.ownerOnly(owner).single(),
            WindowsOwnerAcl.ownerOnly(FakePrincipal("other-user")).single(),
        )

        assertFailsWith<IllegalStateException> {
            WindowsOwnerAcl.requireOwnerOnly(
                owner = owner,
                entries = listOf(
                    entry(
                        owner = owner,
                        type = AclEntryType.ALLOW,
                        permissions = missingPermission,
                    ),
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            WindowsOwnerAcl.requireOwnerOnly(owner, expandedToOther)
        }
    }

    private fun entry(
        owner: UserPrincipal,
        type: AclEntryType,
        permissions: Set<AclEntryPermission>,
    ): AclEntry = AclEntry.newBuilder()
        .setType(type)
        .setPrincipal(owner)
        .setPermissions(permissions)
        .build()

    private data class FakePrincipal(
        private val value: String,
    ) : UserPrincipal {
        override fun getName(): String = value
    }
}
