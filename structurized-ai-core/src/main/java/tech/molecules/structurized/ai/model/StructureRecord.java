package tech.molecules.structurized.ai.model;

import java.util.Map;

public record StructureRecord(
        String repositoryId,
        String structureId,
        String label,
        String inputSmiles,
        String canonicalSmiles,
        String canonicalIdCode,
        Map<String, String> fields,
        int componentCount,
        int atomCount,
        int bondCount
) {
    public StructureRef ref() {
        return new StructureRef(repositoryId, structureId);
    }
}
