package tech.molecules.structurized.clustering;

import java.util.List;

public record SimilarityClusteringResult(
        String descriptor,
        String strategy,
        double threshold,
        int moleculeCount,
        int clusterCount,
        int singletonCount,
        int unclusteredCount,
        List<SimilarityCluster> clusters,
        List<UnclusteredMolecule> unclustered
) {
    public SimilarityClusteringResult {
        clusters = List.copyOf(clusters == null ? List.of() : clusters);
        unclustered = List.copyOf(unclustered == null ? List.of() : unclustered);
    }
}
