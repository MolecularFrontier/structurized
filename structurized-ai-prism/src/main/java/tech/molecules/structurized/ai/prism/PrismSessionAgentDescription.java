package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismSessionAgentDescription(
        PrismSessionSummary summary,
        List<PrismColumnSummary> columns,
        List<PrismColumnSummary> identifierColumns,
        List<PrismColumnSummary> structureColumns,
        List<PrismColumnSummary> endpointColumns,
        List<PrismRowSetSummary> rowSets,
        Map<String, Integer> columnTypeCounts,
        Map<String, Integer> semanticTypeCounts
) {
    public PrismSessionAgentDescription {
        columns = columns == null ? List.of() : List.copyOf(columns);
        identifierColumns = identifierColumns == null ? List.of() : List.copyOf(identifierColumns);
        structureColumns = structureColumns == null ? List.of() : List.copyOf(structureColumns);
        endpointColumns = endpointColumns == null ? List.of() : List.copyOf(endpointColumns);
        rowSets = rowSets == null ? List.of() : List.copyOf(rowSets);
        columnTypeCounts = columnTypeCounts == null ? Map.of() : Map.copyOf(columnTypeCounts);
        semanticTypeCounts = semanticTypeCounts == null ? Map.of() : Map.copyOf(semanticTypeCounts);
    }
}
