package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismGroupingSummary(
        String sessionId,
        String groupingId,
        String title,
        String description,
        String mode,
        String sourceRowSetId,
        String facetColumnId,
        int groupCount,
        int membershipCount,
        Map<String, Object> provenance
) {
}
