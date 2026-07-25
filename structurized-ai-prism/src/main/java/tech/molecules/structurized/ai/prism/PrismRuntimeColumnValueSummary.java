package tech.molecules.structurized.ai.prism;

public record PrismRuntimeColumnValueSummary(
        String columnId,
        String displayName,
        String type,
        String semanticType,
        String role,
        String unit,
        String endpointId,
        String direction,
        int rowCount,
        int validCount,
        int missingCount,
        PrismNumericColumnStats numeric,
        PrismCategoricalColumnStats categorical
) {
}
