package dev.sixbridge.core.conversion

import kotlin.test.Test
import kotlin.test.assertEquals

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
    }
}
