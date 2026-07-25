package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismGroupSummary(
        String groupId,
        String label,
        String description,
        String parentGroupId,
        String representativeRowId,
        int memberCount,
        Map<String, Object> metadata
) {
}
