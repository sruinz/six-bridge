package dev.sixbridge.core.conversion;

import dev.sixbridge.core.profile.BrowserProfile;
import java.nio.file.Path;

public final class ActivateTransactionContext {
    public static void activate(
        Path original,
        Path staged,
        BrowserProfile profile
    ) {
        ConversionWorkspaceTransaction.TransactionContext.INSTANCE.activate(
            original,
            staged,
            profile
        );
    }
}
