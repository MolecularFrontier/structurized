package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismRowSetStructureCollection(
        String sessionId,
        String rowSetId,
        long revision,
        int rowCount,
        int structureCount,
        int skippedRows,
        List<PrismRowStructureEntry> structures,
        String structureColumnId,
        String structureFormat
) {
    public PrismRowSetStructureCollection {
        structures = structures == null ? List.of() : List.copyOf(structures);
    }

    public PrismRowSetStructureCollection(
            String sessionId,
            String rowSetId,
            long revision,
            int rowCount,
            int structureCount,
            int skippedRows,
            List<PrismRowStructureEntry> structures
    ) {
        this(sessionId, rowSetId, revision, rowCount, structureCount, skippedRows, structures, null, null);
    }
}
