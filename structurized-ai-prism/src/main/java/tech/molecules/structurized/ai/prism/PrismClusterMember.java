package tech.molecules.structurized.ai.prism;

public record PrismClusterMember(
        String rowId,
        String subjectId,
        String structureId,
        String label,
        String smiles,
        double similarityToRepresentative
) {}
