package tech.molecules.structurized.clustering;

public record ClusterCrossNeighbor(
        String structureId,
        String label,
        String clusterId,
        double similarityToRepresentative
) {}
