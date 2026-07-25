package tech.molecules.structurized.ai.prism;

import java.util.List;

public record CreatePrismColumnRowSetRequest(
        String sessionId,
        String baseRowSetId,
        String rowSetId,
        String name,
        String description,
        String columnId,
        String filterType,
        Double minimum,
        Double maximum,
        List<String> values,
        String pattern,
        String textMode,
        Boolean caseInsensitive,
        Boolean includeMissing
) {
    public CreatePrismColumnRowSetRequest {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
