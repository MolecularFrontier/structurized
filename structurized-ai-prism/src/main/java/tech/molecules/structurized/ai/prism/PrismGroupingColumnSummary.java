package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGroupingColumnSummary(
        PrismGroupingSummary grouping,
        List<String> columnIds,
        boolean includeSingletons,
        int totalGroups,
        int returnedGroups,
        int offset,
        int limit,
        List<PrismGroupColumnSummaryRow> groups
) {
    public PrismGroupingColumnSummary {
        columnIds = columnIds == null ? List.of() : List.copyOf(columnIds);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
