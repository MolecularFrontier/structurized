package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PredictionContext(
        String sessionId,
        String project,
        List<PrismEndpointSummary> endpoints,
        Map<String, Object> metadata
) {
    public PredictionContext {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
