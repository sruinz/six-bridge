package dev.sixbridge.core.conversion;

public final class ConstructPlannerImplementation {
    public static SelectedAdapter forge(AdapterDecision decision) {
        return new ConversionPlanner.PlannedAdapter(decision);
    }
}
