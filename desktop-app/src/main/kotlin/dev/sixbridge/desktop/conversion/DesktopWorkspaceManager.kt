package dev.sixbridge.desktop.conversion

import java.util.concurrent.ConcurrentHashMap

class DesktopWorkspaceManager(
    private val provider: DesktopSecureWorkspaceProvider =
        DesktopSecureWorkspaceProvider(),
) {
    private val leases = ConcurrentHashMap<String, DesktopWorkspaceLease>()

    fun create(): DesktopWorkspaceLease {
        val lease = provider.create()
        check(leases.putIfAbsent(lease.workspace.token, lease) == null) {
            "중복 desktop workspace token이 생성되었습니다."
        }
        return lease
    }

    fun cleanup(token: String): Boolean {
        val lease = leases.remove(token) ?: return false
        lease.close()
        return true
    }

    fun cleanupOrphans(): Int {
        var cleaned = 0
        leases.entries.forEach { entry ->
            val lease = entry.value
            if (lease.isClosed && leases.remove(entry.key, lease)) {
                cleaned += 1
            }
        }
        return cleaned
    }
}
