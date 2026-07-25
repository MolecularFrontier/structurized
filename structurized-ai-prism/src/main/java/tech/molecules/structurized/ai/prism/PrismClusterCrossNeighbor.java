package tech.molecules.structurized.ai.prism;

public record PrismClusterCrossNeighbor(
        String rowId,
        String label,
        String clusterId,
        double similarity
) {}
