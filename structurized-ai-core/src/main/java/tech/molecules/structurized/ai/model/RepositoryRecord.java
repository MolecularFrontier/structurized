package tech.molecules.structurized.ai.model;

public record RepositoryRecord(
        String repositoryId,
        String label,
        String description,
        boolean mutable,
        String sourceType,
        int structureCount
) {}
