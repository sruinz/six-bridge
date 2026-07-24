package dev.sixbridge.core.conversion;

import dev.sixbridge.core.compat.CompatibilityReport;
import java.util.List;

public final class ConstructConversionPlan {
    public static ConversionPlan forge(CompatibilityReport report) {
        return new ConversionPlan(report, List.of());
    }
}
