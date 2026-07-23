package tech.molecules.structurized.clustering;

public record ClusterMember(
        int inputIndex,
        String structureId,
        String label,
        double similarityToRepresentative
) {}
