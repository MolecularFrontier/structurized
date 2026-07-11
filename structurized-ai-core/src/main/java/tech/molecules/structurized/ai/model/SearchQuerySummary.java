package tech.molecules.structurized.ai.model;

public record SearchQuerySummary(
        String type,
        String input,
        String normalized
) {}
