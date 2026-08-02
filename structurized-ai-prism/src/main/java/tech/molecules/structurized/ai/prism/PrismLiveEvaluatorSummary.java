package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PrismLiveEvaluatorSummary(
        String sessionId,
        long workspaceRevision,
        String bindingId,
        String capabilityId,
        String displayName,
        String description,
        String mode,
        long quietPeriodMillis,
        Map<String, Object> configuration
) {
}
