package dev.sixbridge.core.profile;

import java.util.List;
import java.util.Set;

public final class BrowserProfileJavaAbiClient {
    private BrowserProfileJavaAbiClient() {}

    public static BrowserProfile run() {
        BrowserProfile profile = new BrowserProfile(
            "java-package",
            "java-version",
            13L,
            30,
            Set.of("java-supported"),
            Set.of("java-detected"),
            new WrapperContract(true, false, true, false),
            "java-source",
            "java-installation",
            "java-signer",
            List.of("java-difference"),
            Set.of("java-feature"),
            Set.of("java-unavailable")
        );
        return profile.copy(
            "java-copy-package",
            "java-copy-version",
            14L,
            31,
            Set.of("java-copy-supported"),
            Set.of("java-copy-detected"),
            new WrapperContract(false, true, false, true),
            "java-copy-source",
            "java-copy-installation",
            "java-copy-signer",
            List.of("java-copy-difference"),
            Set.of("java-copy-feature"),
            Set.of("java-copy-unavailable")
        );
    }
}
