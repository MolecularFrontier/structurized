package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record CreatePrismRowSetFromRowsRequest(
        String sessionId,
        List<String> rowIds,
        String rowSetId,
        String name,
        String description,
        Map<String, Object> provenance
) {
    public CreatePrismRowSetFromRowsRequest {
        rowIds = rowIds == null ? List.of() : List.copyOf(rowIds);
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
    }
}
