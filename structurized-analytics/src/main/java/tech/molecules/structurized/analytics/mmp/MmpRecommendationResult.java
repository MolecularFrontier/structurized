package tech.molecules.structurized.analytics.mmp;

import java.util.List;
import java.util.Objects;

/** Complete deterministic output of one recommendation request. */
public record MmpRecommendationResult(
        List<MmpRecommendationCandidate> candidates,
        MmpRecommendationDiagnostics diagnostics
) {
    public MmpRecommendationResult {
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
