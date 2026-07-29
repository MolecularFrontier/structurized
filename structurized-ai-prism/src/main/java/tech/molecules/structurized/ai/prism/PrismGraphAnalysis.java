package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGraphAnalysis(
        PrismGraphSummary graph,
        int sourceRowCount,
        int connectedRowCount,
        int isolatedSourceRowCount,
        PrismGraphDegreeStats degree,
        PrismGraphSimilarityStats similarity,
        int topNodeLimit,
        List<PrismGraphNodeStat> topDegreeRows
) {
    public PrismGraphAnalysis {
        topDegreeRows = topDegreeRows == null ? List.of() : List.copyOf(topDegreeRows);
    }
}
