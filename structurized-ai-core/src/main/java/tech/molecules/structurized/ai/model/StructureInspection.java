package tech.molecules.structurized.ai.model;

import java.util.List;

public record StructureInspection(
        StructureRecord record,
        List<ComponentInspection> components,
        List<AtomInspection> atoms,
        List<BondInspection> bonds
) {}
