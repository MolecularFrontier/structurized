package tech.molecules.structurized.ai.model;

import java.util.List;

public record SubstructureSearchMatch(
        String repositoryId,
        String structureId,
        String label,
        String componentId,
        int matchCount,
        List<AtomMapping> atomMappings
) {}
