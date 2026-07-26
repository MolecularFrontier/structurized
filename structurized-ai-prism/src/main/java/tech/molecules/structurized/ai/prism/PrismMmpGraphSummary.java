package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismMmpGraphSummary(
        PrismGraphSummary graph,
        String structureColumnId,
        String valueColumnId,
        int sourceRowCount,
        int validStructureCount,
        int skippedRowCount,
        int fragmentationRecordCount,
        int pairCount,
        int transformCount,
        Map<String, Object> configuration,
        List<PrismSkippedAnalysisRow> skippedRows
) {
    public PrismMmpGraphSummary {
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        skippedRows = skippedRows == null ? List.of() : List.copyOf(skippedRows);
    }
}
