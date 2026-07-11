package tech.molecules.structurized.ai.model;

import java.util.List;

public record ComponentInspection(
        String componentId,
        int heavyAtomCount,
        int atomCount,
        List<String> atomIds,
        int formalCharge,
        String canonicalIdCode
) {}
