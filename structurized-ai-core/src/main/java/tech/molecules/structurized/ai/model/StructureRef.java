package tech.molecules.structurized.ai.model;

import java.util.Objects;

/**
 * Stable repository-local reference to one stored molecular snapshot.
 */
public record StructureRef(String repositoryId, String structureId) {
    public StructureRef {
        repositoryId = requireId(repositoryId, "repositoryId");
        structureId = requireId(structureId, "structureId");
    }

    public String qualifiedId() {
        return repositoryId + ":" + structureId;
    }

    private static String requireId(String value, String name) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
