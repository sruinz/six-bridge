package dev.sixbridge.core.conversion;

import java.util.List;

public final class ImplementSelectedAdapter implements SelectedAdapter {
    public String getId() { return "action-popup-entry"; }
    public int getVersion() { return 1; }
    public AdapterRisk getRisk() { return AdapterRisk.BEHAVIOR_CHANGE; }
    public AdapterDisposition getDisposition() { return AdapterDisposition.REQUIRES_APPROVAL; }
    public boolean getApplied() { return false; }
    public List<SourceEvidence> getEvidence() { return List.of(); }
    public List<String> getChanges() { return List.of(); }
    public List<String> getRemainingLimitations() { return List.of(); }
}
