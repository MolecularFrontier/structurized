package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismRowSetSummary(
        String sessionId,
        String rowSetId,
        String name,
        String description,
        int rowCount,
        Map<String, Object> provenance
) {
    public PrismRowSetSummary {
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
