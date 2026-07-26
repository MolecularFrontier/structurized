package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismGraphEdgeView(
        String edgeId,
        String sourceRowId,
        String targetRowId,
        String label,
        Map<String, Object> properties
) {
    public PrismGraphEdgeView {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
