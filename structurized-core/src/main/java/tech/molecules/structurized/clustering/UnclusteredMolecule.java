package tech.molecules.structurized.clustering;

public record UnclusteredMolecule(
        int inputIndex,
        String structureId,
        String label,
        String reason
) {}
