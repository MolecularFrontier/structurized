package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PrismLiveEvaluationView(
        String sessionId,
        long liveSequence,
        String bindingId,
        String capabilityId,
        String documentId,
        long targetRevision,
        String status,
        String updatedAt,
        Long resultRevision,
        String completedAt,
        boolean stale,
        String schemaId,
        Map<String, Object> values,
        List<String> warnings,
        Map<String, Object> metadata,
        String error
) {
}
