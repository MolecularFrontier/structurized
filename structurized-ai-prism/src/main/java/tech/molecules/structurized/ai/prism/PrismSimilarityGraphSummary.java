package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismSimilarityGraphSummary(
        PrismGraphSummary graph,
        String structureColumnId,
        int sourceRowCount,
        int validStructureCount,
        int skippedRowCount,
        int edgeCount,
        Map<String, Object> configuration,
        PrismGraphSimilarityStats similarity,
        List<PrismSkippedAnalysisRow> skippedRows
) {
    public PrismSimilarityGraphSummary {
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        skippedRows = skippedRows == null ? List.of() : List.copyOf(skippedRows);
    }
}
