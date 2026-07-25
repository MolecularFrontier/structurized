package tech.molecules.structurized.ai.prism;

import java.util.List;

public record CombinePrismRowSetsRequest(
        String sessionId,
        String rowSetId,
        String name,
        String description,
        String operation,
        List<String> rowSetIds
) {
    public CombinePrismRowSetsRequest {
        rowSetIds = rowSetIds == null ? List.of() : List.copyOf(rowSetIds);
    }
}
