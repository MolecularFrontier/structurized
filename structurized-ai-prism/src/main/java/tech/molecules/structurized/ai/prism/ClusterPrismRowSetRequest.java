package tech.molecules.structurized.ai.prism;

public record ClusterPrismRowSetRequest(
        String sessionId,
        String rowSetId,
        String analysisId,
        String label,
        String descriptor,
        Double threshold,
        Integer maxCrossNeighbors,
        Boolean publishColumns,
        String structureColumnId
) {
    public ClusterPrismRowSetRequest(
            String sessionId,
            String rowSetId,
            String analysisId,
            String label,
            String descriptor,
            Double threshold,
            Integer maxCrossNeighbors,
            Boolean publishColumns
    ) {
        this(sessionId, rowSetId, analysisId, label, descriptor, threshold, maxCrossNeighbors, publishColumns, null);
    }
}
