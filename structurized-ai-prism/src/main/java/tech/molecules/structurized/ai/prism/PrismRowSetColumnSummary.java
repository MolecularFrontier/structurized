package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismRowSetColumnSummary(
        PrismRowSetSummary rowSet,
        List<String> columnIds,
        List<PrismRuntimeColumnValueSummary> columns
) {
    public PrismRowSetColumnSummary {
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
