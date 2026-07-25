package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismRowMember(
        String rowId,
        int physicalRow,
        String subjectId,
        String structureId,
        String batchId,
        String project,
        String series,
        String smiles,
        Map<String, String> fields
) {
    public PrismRowMember {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
