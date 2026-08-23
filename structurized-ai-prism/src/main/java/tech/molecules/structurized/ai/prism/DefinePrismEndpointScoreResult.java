package tech.molecules.structurized.ai.prism;

import java.util.List;

public record DefinePrismEndpointScoreResult(
        PrismEndpointScoreSummary score,
        boolean reused,
        long revision,
        List<String> warnings
) {
    public DefinePrismEndpointScoreResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
