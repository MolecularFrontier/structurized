package tech.molecules.structurized.ai.model;

public record BondInspection(
        String bondId,
        String atom1,
        String atom2,
        int order,
        int type,
        boolean aromatic,
        boolean delocalized,
        boolean ringBond,
        Integer smallestRingSize,
        String stereo,
        boolean rotatableCandidate
) {}
