package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismGroupColumnSummaryRow(
        String groupId,
        String label,
        String description,
        String parentGroupId,
        String representativeRowId,
        int memberCount,
        Map<String, Object> metadata,
        List<PrismRuntimeColumnValueSummary> columns
) {
    public PrismGroupColumnSummaryRow {
        metadata = NullSafeMaps.immutableCopy(metadata);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
