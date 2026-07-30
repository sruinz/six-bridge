package dev.sixbridge.desktop.security

import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsOwnerAclTest {
    @Test
    fun `Windows ACL은 현재 owner 하나만 허용한다`() {
        val entries = WindowsOwnerAcl.ownerOnly(FakePrincipal("current-user"))

        assertEquals(1, entries.size)
        assertEquals(AclEntryType.ALLOW, entries.single().type())
        assertEquals("current-user", entries.single().principal().name)
        assertEquals(
            EnumSet.allOf(AclEntryPermission::class.java),
            entries.single().permissions(),
        )
    }

    private data class FakePrincipal(
        private val value: String,
    ) : UserPrincipal {
        override fun getName(): String = value
    }
}
