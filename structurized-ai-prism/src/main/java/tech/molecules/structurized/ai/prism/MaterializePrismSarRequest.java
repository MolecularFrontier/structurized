package tech.molecules.structurized.ai.prism;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record MaterializePrismSarRequest(
        String sessionId,
        String analysisId,
        String sourceRowSetId,
        String outputPrefix,
        String scaffoldIdcode,
        String analysisFingerprint,
        List<PrismSarDimensionAssignment> dimensions,
        Set<String> matchedRowIds,
        int unmatchedCount,
        int multiAttachmentCount,
        int ambiguousCount
) {
    public MaterializePrismSarRequest {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        matchedRowIds = matchedRowIds == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(matchedRowIds));
    }
}
