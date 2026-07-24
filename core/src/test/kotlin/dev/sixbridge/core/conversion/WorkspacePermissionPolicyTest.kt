package dev.sixbridge.core.conversion

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacePermissionPolicyTest {
    @Test
    fun `uses explicit Android app sandbox evidence when POSIX and ACL views are absent`() {
        assertEquals(
            WorkspacePermissionMode.ANDROID_APP_PRIVATE,
            WorkspacePermissionPolicy.selectMode(
                posixAvailable = false,
                aclOwnerOnly = false,
                runtimeName = "Android Runtime",
                androidPrivatePathVerified = true,
            ),
        )
        assertEquals(
            WorkspacePermissionMode.UNSUPPORTED,
            WorkspacePermissionPolicy.selectMode(
                posixAvailable = false,
                aclOwnerOnly = false,
                runtimeName = "Android Runtime",
                androidPrivatePathVerified = false,
            ),
        )
    }

    @Test
    fun `rejects an ordinary JVM filesystem without POSIX or owner-only ACL evidence`() {
        assertEquals(
            WorkspacePermissionMode.UNSUPPORTED,
            WorkspacePermissionPolicy.selectMode(
                posixAvailable = false,
                aclOwnerOnly = false,
                runtimeName = "OpenJDK Runtime Environment",
                androidPrivatePathVerified = false,
            ),
        )
    }

    @Test
    fun `prefers concrete POSIX or owner-only ACL evidence`() {
        assertEquals(
            WorkspacePermissionMode.POSIX_OWNER_ONLY,
            WorkspacePermissionPolicy.selectMode(
                posixAvailable = true,
                aclOwnerOnly = false,
                runtimeName = "OpenJDK Runtime Environment",
                androidPrivatePathVerified = false,
            ),
        )
        assertEquals(
            WorkspacePermissionMode.ACL_OWNER_ONLY,
            WorkspacePermissionPolicy.selectMode(
                posixAvailable = false,
                aclOwnerOnly = true,
                runtimeName = "OpenJDK Runtime Environment",
                androidPrivatePathVerified = false,
            ),
        )
    }
}
