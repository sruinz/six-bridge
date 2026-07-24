package dev.sixbridge.core.conversion;

import dev.sixbridge.core.profile.BrowserProfile;
import java.nio.file.Path;

public final class ConstructAdapterExecutionContext
    implements AdapterExecutionContext {
    public Path getOriginalRoot() {
        return Path.of(".");
    }

    public Path getStagedRoot() {
        return Path.of(".");
    }

    public BrowserProfile getProfile() {
        return null;
    }
}
