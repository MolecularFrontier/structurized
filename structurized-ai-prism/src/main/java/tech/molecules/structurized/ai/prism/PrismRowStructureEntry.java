package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismRowStructureEntry(
        String rowId,
        String subjectId,
        String structureId,
        String label,
        String smiles,
        Map<String, String> fields
) {
    public PrismRowStructureEntry {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
