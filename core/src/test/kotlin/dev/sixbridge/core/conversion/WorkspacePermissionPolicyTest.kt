package dev.sixbridge.core.conversion

import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspacePermissionPolicyTest {
    @Test
    fun `rejects metadata without a concrete POSIX or ACL view`() {
        assertEquals(
            WorkspaceMetadataMode.UNSUPPORTED,
            WorkspacePermissionPolicy.selectMetadataMode(
                posixAvailable = false,
                aclAvailable = false,
            ),
        )
        assertEquals(
            WorkspaceMetadataMode.POSIX,
            WorkspacePermissionPolicy.selectMetadataMode(
                posixAvailable = true,
                aclAvailable = false,
            ),
        )
    }

    @Test
    fun `ACL metadata support does not require owner-only source ACL`() {
        assertEquals(
            WorkspaceMetadataMode.ACL,
            WorkspacePermissionPolicy.selectMetadataMode(
                posixAvailable = false,
                aclAvailable = true,
            ),
        )
    }

    @Test
    fun `dual POSIX and ACL metadata views are both preserved`() {
        assertEquals(
            WorkspaceMetadataMode.POSIX_AND_ACL,
            WorkspacePermissionPolicy.selectMetadataMode(
                posixAvailable = true,
                aclAvailable = true,
            ),
        )
    }

    @Test
    fun `private workspace requires concrete owner-only policy`() {
        assertEquals(
            WorkspacePermissionMode.ACL_OWNER_ONLY,
            WorkspacePermissionPolicy.selectPrivateMode(
                posixAvailable = false,
                posixOwnerOnly = false,
                aclAvailable = true,
                aclOwnerOnly = true,
            ),
        )
        assertEquals(
            WorkspacePermissionMode.UNSUPPORTED,
            WorkspacePermissionPolicy.selectPrivateMode(
                posixAvailable = false,
                posixOwnerOnly = false,
                aclAvailable = true,
                aclOwnerOnly = false,
            ),
        )
        assertEquals(
            WorkspacePermissionMode.POSIX_AND_ACL_OWNER_ONLY,
            WorkspacePermissionPolicy.selectPrivateMode(
                posixAvailable = true,
                posixOwnerOnly = true,
                aclAvailable = true,
                aclOwnerOnly = true,
            ),
        )
        assertEquals(
            WorkspacePermissionMode.UNSUPPORTED,
            WorkspacePermissionPolicy.selectPrivateMode(
                posixAvailable = true,
                posixOwnerOnly = true,
                aclAvailable = true,
                aclOwnerOnly = false,
            ),
        )
    }

    @Test
    fun `non-owner ACL cannot grant ACL or owner mutation`() {
        val owner = principal("owner")
        val other = principal("other")

        assertFalse(
            WorkspacePermissionPolicy.isOwnerOnlyAcl(
                owner,
                listOf(allow(other, AclEntryPermission.WRITE_ACL)),
            ),
        )
        assertFalse(
            WorkspacePermissionPolicy.isOwnerOnlyAcl(
                owner,
                listOf(allow(other, AclEntryPermission.WRITE_OWNER)),
            ),
        )
        assertTrue(
            WorkspacePermissionPolicy.isOwnerOnlyAcl(
                owner,
                listOf(allow(other, AclEntryPermission.READ_ATTRIBUTES)),
            ),
        )
    }

    private fun principal(name: String) = object : UserPrincipal {
        override fun getName(): String = name
    }

    private fun allow(
        principal: UserPrincipal,
        permission: AclEntryPermission,
    ): AclEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(principal)
        .setPermissions(permission)
        .build()
}
