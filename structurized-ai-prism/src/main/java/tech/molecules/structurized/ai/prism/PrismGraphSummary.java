package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismGraphSummary(
        String sessionId,
        String graphId,
        String title,
        String description,
        String graphType,
        String pluginId,
        int schemaVersion,
        boolean directed,
        String sourceRowSetId,
        int nodeCount,
        int edgeCount,
        Map<String, Object> metadata
) {
    public PrismGraphSummary {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
