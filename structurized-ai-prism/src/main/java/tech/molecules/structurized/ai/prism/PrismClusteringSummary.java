package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismClusteringSummary(
        PrismAnalysisSummary analysis,
        String descriptor,
        String strategy,
        double threshold,
        int sourceRowCount,
        int inputMoleculeCount,
        int skippedRowCount,
        int clusterCount,
        int singletonCount,
        int unclusteredCount,
        List<PrismSkippedAnalysisRow> skippedRows
) {
    public PrismClusteringSummary {
        skippedRows = skippedRows == null ? List.of() : List.copyOf(skippedRows);
    }
}
