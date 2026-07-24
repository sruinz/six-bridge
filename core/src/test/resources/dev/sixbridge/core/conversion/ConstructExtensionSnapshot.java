package dev.sixbridge.core.conversion;

import java.nio.file.Path;

public final class ConstructExtensionSnapshot {
    public static ExtensionSnapshot forge(Path original, Path working) {
        return new ExtensionSnapshot(
            new byte[0],
            "",
            original,
            working,
            null
        );
    }
}
