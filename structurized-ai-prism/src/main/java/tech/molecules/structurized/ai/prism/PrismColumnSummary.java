package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismColumnSummary(
        String sessionId,
        String columnId,
        String displayName,
        String type,
        String semanticType,
        String role,
        String unit,
        String endpointId,
        String direction,
        String structureFormat,
        long missingCount,
        long nonMissingCount,
        Map<String, Object> raw
) {
    public PrismColumnSummary {
        raw = raw == null ? Map.of() : Map.copyOf(raw);
    }
}
