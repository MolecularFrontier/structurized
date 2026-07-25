package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGroupingView(
        PrismGroupingSummary summary,
        int totalGroups,
        int offset,
        int limit,
        List<PrismGroupSummary> groups
) {
}
