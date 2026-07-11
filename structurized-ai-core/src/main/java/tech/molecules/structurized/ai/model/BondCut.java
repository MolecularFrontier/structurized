package tech.molecules.structurized.ai.model;

public record BondCut(
        String bondId,
        int attachmentId,
        String atom1,
        String atom2,
        int bondOrder,
        boolean ringBond,
        boolean aromatic
) {}
