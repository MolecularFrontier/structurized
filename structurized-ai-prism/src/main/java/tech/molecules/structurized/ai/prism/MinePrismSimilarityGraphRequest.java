package tech.molecules.structurized.ai.prism;

public record MinePrismSimilarityGraphRequest(
        String sessionId,
        String rowSetId,
        String structureColumnId,
        String graphId,
        String label,
        String descriptor,
        String mode,
        Integer neighborCount,
        Double similarityThreshold,
        Boolean mutualKnnOnly,
        Integer maxEdges
) {
}
