package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismClusterSummary(
        String clusterId,
        String representativeRowId,
        String representativeLabel,
        String representativeSmiles,
        int size,
        double minimumSimilarityToRepresentative,
        double meanSimilarityToRepresentative,
        List<PrismClusterMember> exampleMembers,
        List<PrismClusterCrossNeighbor> nearestCrossClusterNeighbors
) {
    public PrismClusterSummary {
        exampleMembers = exampleMembers == null ? List.of() : List.copyOf(exampleMembers);
        nearestCrossClusterNeighbors = nearestCrossClusterNeighbors == null ? List.of() : List.copyOf(nearestCrossClusterNeighbors);
    }
}
