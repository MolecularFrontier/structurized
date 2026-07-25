package tech.molecules.structurized.ai.prism;

import java.util.Objects;

public record ManagedPrismSessionChange(
        ManagedPrismSession session,
        long revision,
        ManagedPrismSessionChangeType type,
        ManagedPrismSessionChangeOrigin origin
) {
    public ManagedPrismSessionChange {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(origin, "origin");
    }
}
