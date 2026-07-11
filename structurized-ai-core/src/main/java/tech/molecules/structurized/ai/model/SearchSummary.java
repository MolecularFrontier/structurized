package tech.molecules.structurized.ai.model;

public record SearchSummary(
        int matchingStructures,
        int returnedStructures,
        boolean truncated
) {}
