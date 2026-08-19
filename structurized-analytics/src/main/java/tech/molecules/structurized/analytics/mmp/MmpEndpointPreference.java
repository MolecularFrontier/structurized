package tech.molecules.structurized.analytics.mmp;

import java.util.Objects;

/** Endpoint run and its optimization direction in one recommendation request. */
public record MmpEndpointPreference(
        String runId,
        MmpOptimizationDirection direction
) {
    public MmpEndpointPreference {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        runId = runId.trim();
        direction = Objects.requireNonNull(direction, "direction");
    }
}
