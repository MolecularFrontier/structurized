package tech.molecules.structurized.ai.prism;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record MaterializePrismSarResult(
        String sessionId,
        String analysisId,
        String outputPrefix,
        String matchedRowSetId,
        Map<String, String> dimensionColumnIds,
        int matchedCount,
        int unmatchedCount,
        int multiAttachmentCount,
        int ambiguousCount,
        boolean reused,
        String fingerprint
) {
    public MaterializePrismSarResult {
        dimensionColumnIds = dimensionColumnIds == null || dimensionColumnIds.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensionColumnIds));
    }
}
