package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismGraphSimilarityStats(
        int edgeCount,
        Double min,
        Double p25,
        Double median,
        Double p75,
        Double max,
        int mutualKnnEdgeCount,
        Map<String, Integer> edgeSourceCounts
) {
    public PrismGraphSimilarityStats {
        edgeSourceCounts = edgeSourceCounts == null ? Map.of() : Map.copyOf(edgeSourceCounts);
    }
}
