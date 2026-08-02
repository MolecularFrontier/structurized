package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record ConfigurePrismLiveEvaluatorRequest(
        String sessionId,
        String bindingId,
        String capabilityId,
        String mode,
        Long quietPeriodMillis,
        Map<String, Object> configuration,
        Long expectedWorkspaceRevision
) {
    public ConfigurePrismLiveEvaluatorRequest {
        if (configuration != null) configuration = Map.copyOf(configuration);
    }
}
