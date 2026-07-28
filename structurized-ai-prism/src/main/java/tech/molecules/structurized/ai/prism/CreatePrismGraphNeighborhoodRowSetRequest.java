package tech.molecules.structurized.ai.prism;

public record CreatePrismGraphNeighborhoodRowSetRequest(
        String sessionId,
        String graphId,
        String centerRowId,
        int maxDepth,
        boolean includeCenter,
        String rowSetId,
        String name,
        String description
) {
}
