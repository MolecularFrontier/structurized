package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.clustering.SimilarityClusteringResult;

import java.util.Map;

final class PrismClusteringAnalysis implements PrismAnalysis {
    private final PrismAnalysisSummary summary;
    private final PrismClusteringSummary clusteringSummary;
    private final SimilarityClusteringResult result;
    private final Map<String, PrismRowStructureEntry> structuresByRowId;

    PrismClusteringAnalysis(PrismAnalysisSummary summary,
                            PrismClusteringSummary clusteringSummary,
                            SimilarityClusteringResult result,
                            Map<String, PrismRowStructureEntry> structuresByRowId) {
        this.summary = summary;
        this.clusteringSummary = clusteringSummary;
        this.result = result;
        this.structuresByRowId = Map.copyOf(structuresByRowId);
    }

    @Override
    public PrismAnalysisSummary summary() {
        return summary;
    }

    PrismClusteringSummary clusteringSummary() {
        return clusteringSummary;
    }

    SimilarityClusteringResult result() {
        return result;
    }

    PrismRowStructureEntry structure(String rowId) {
        return structuresByRowId.get(rowId);
    }
}
