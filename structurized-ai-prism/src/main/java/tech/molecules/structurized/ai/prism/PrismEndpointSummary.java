package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismEndpointSummary(
        String endpointId,
        String name,
        String path,
        String datatype,
        String endpointType,
        String unit,
        String evaluationMode,
        String description,
        String numericScale,
        Double numericLowerBound,
        Double numericUpperBound,
        List<String> categories
) {}
