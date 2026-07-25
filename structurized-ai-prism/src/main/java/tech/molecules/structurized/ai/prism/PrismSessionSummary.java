package tech.molecules.structurized.ai.prism;

public record PrismSessionSummary(
        String sessionId,
        String datasetId,
        String label,
        String sourcePath,
        int totalRowCount,
        int visibleRowCount,
        int visibleColumnCount,
        int rowSetCount,
        int endpointCount,
        int endpointValueCount,
        long revision
) {}
