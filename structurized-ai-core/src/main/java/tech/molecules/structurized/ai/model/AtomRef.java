package tech.molecules.structurized.ai.model;

import java.util.Objects;

public record AtomRef(StructureRef structure, String atomId) {
    public AtomRef {
        Objects.requireNonNull(structure, "structure");
        atomId = requireId(atomId, "atomId");
    }

    public String qualifiedId() {
        return structure.qualifiedId() + ":" + atomId;
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
