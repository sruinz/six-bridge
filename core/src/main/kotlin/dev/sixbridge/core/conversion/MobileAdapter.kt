package dev.sixbridge.core.conversion

import java.nio.file.Path

interface MobileAdapter {
    val id: String
    val version: Int

    fun apply(workingRoot: Path, decision: AdapterDecision): AdapterDecision
}
