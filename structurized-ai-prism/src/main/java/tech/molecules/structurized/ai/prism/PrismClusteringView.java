package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismClusteringView(
        PrismClusteringSummary summary,
        int totalClusters,
        int offset,
        int limit,
        List<PrismClusterSummary> clusters,
        List<PrismUnclusteredRow> unclustered
) {
    public PrismClusteringView {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        unclustered = unclustered == null ? List.of() : List.copyOf(unclustered);
    }
}
