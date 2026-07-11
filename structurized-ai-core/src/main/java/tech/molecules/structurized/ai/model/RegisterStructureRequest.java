package tech.molecules.structurized.ai.model;

import java.util.Map;

public record RegisterStructureRequest(
        String smiles,
        String repositoryId,
        String structureId,
        String label,
        Map<String, String> fields
) {
    public RegisterStructureRequest(String smiles) {
        this(smiles, "session", null, null, Map.of());
    }
}
