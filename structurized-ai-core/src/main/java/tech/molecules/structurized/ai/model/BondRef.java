package tech.molecules.structurized.ai.model;

import java.util.Objects;

public record BondRef(StructureRef structure, String bondId) {
    public BondRef {
        Objects.requireNonNull(structure, "structure");
        bondId = requireId(bondId, "bondId");
    }

    public String qualifiedId() {
        return structure.qualifiedId() + ":" + bondId;
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
