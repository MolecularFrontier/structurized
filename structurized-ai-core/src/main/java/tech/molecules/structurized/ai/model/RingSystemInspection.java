package tech.molecules.structurized.ai.model;

import java.util.List;

public record RingSystemInspection(
        String atomId,
        boolean inRingSystem,
        List<String> atomIds,
        List<String> bondIds,
        List<String> junctionAtoms,
        List<RingSystemAttachment> attachments,
        List<Integer> ringSizes,
        int aromaticAtomCount,
        int aromaticBondCount,
        String ringSystemSmiles,
        String algorithm
) {}
