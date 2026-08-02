package tech.molecules.structurized.ai.prism;

public enum ManagedPrismSessionChangeType {
    PROJECTION,
    STRUCTURE,
    VIEWS,
    MOLECULES,
    LIVE_CONFIGURATION;

    static ManagedPrismSessionChangeType merge(ManagedPrismSessionChangeType left,
                                                ManagedPrismSessionChangeType right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
