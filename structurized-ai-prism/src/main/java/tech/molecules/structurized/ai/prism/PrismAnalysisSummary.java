package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismAnalysisSummary(
        String sessionId,
        String analysisId,
        String type,
        String label,
        String sourceRowSetId,
        long sourceRevision,
        long resultRevision,
        String createdAt,
        List<String> publishedColumnIds,
        Map<String, Object> details
) {
    public PrismAnalysisSummary {
        publishedColumnIds = publishedColumnIds == null ? List.of() : List.copyOf(publishedColumnIds);
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
