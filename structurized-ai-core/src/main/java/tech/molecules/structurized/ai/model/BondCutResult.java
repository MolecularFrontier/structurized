package tech.molecules.structurized.ai.model;

import java.util.List;

public record BondCutResult(
        StructureRef parent,
        List<BondCut> cuts,
        List<CutFragment> fragments,
        List<String> warnings
) {}
