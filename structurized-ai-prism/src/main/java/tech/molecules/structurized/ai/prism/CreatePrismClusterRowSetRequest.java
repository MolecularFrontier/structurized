package tech.molecules.structurized.ai.prism;

public record CreatePrismClusterRowSetRequest(
        String sessionId,
        String analysisId,
        String clusterId,
        String rowSetId,
        String name,
        String description
) {}
