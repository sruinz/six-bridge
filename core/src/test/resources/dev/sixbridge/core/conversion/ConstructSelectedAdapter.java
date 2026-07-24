package dev.sixbridge.core.conversion;

public final class ConstructSelectedAdapter {
    public static SelectedAdapter forge(AdapterDecision decision) {
        return new SelectedAdapter(decision);
    }
}
