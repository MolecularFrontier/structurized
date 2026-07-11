package tech.molecules.structurized.ai.model;

public record CreateRepositoryRequest(
        String repositoryId,
        String label,
        String description,
        boolean mutable
) {}
