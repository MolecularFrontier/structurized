package tech.molecules.structurized.ai.prism;

public record CreatePrismEndpointRowSetRequest(
        String sessionId,
        String endpointId,
        String rowSetId,
        String name,
        String operator,
        Double value,
        String measurementDateField,
        String measuredAfter,
        String measuredBefore,
        Boolean requireMeasuredDate
) {}
