package dev.sixbridge.core.conversion;

import dev.sixbridge.core.compat.CompatibilityReport;
import java.util.List;

public final class ImplementConversionPlan implements ConversionPlan {
    public CompatibilityReport getReport() { return null; }
    public List<SelectedAdapter> getSelectedAdapters() { return List.of(); }
    public String getProofSha256() { return ""; }
}
