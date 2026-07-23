package tech.molecules.structurized.clustering;

import java.util.List;

public record SimilarityCluster(
        String clusterId,
        int representativeIndex,
        String representativeStructureId,
        String representativeLabel,
        List<ClusterMember> members,
        List<ClusterCrossNeighbor> nearestCrossNeighbors
) {
    public SimilarityCluster {
        members = List.copyOf(members == null ? List.of() : members);
        nearestCrossNeighbors = List.copyOf(nearestCrossNeighbors == null ? List.of() : nearestCrossNeighbors);
    }

    public int size() {
        return members.size();
    }
}
