package tech.molecules.structurized.ai.ocl;

import java.util.List;

public record ComponentSnapshot(
        String componentId,
        List<Integer> atomIndices,
        int heavyAtomCount,
        int formalCharge,
        String canonicalIdCode
) {}
