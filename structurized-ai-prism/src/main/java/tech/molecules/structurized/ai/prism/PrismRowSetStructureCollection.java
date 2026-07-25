package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismRowSetStructureCollection(
        String sessionId,
        String rowSetId,
        long revision,
        int rowCount,
        int structureCount,
        int skippedRows,
        List<PrismRowStructureEntry> structures
) {
    public PrismRowSetStructureCollection {
        structures = structures == null ? List.of() : List.copyOf(structures);
    }
}
