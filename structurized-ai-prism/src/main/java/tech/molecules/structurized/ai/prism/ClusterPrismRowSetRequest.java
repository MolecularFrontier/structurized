package tech.molecules.structurized.ai.prism;

public record ClusterPrismRowSetRequest(
        String sessionId,
        String rowSetId,
        String analysisId,
        String label,
        String descriptor,
        Double threshold,
        Integer maxCrossNeighbors,
        Boolean publishColumns
) {}
