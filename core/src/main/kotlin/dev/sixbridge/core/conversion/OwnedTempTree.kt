package dev.sixbridge.core.conversion

import java.nio.file.Path
import kotlin.jvm.JvmSynthetic

internal object OwnedTempTree {
    @JvmSynthetic
    fun delete(path: Path) {
        PendingTempCleanup.delete(path)
    }
}

internal class OwnedTempCleanupException :
    IllegalStateException("소유한 임시 경로를 정리하지 못했습니다.")
