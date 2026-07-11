package tech.molecules.structurized.ai.model;

public record ExactStructureSearchMatch(
        String repositoryId,
        String structureId,
        String label,
        String componentId
) {}
