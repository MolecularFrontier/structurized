package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PredictionRunSummary(
        PrismAnalysisSummary analysis,
        String capabilityId,
        String providerId,
        String workflowId,
        String workflowVersion,
        int inputCount,
        int valueCount,
        int successCount,
        int outOfDomainCount,
        int failureCount,
        List<String> publishedColumnIds
) {
    public PredictionRunSummary {
        publishedColumnIds = publishedColumnIds == null ? List.of() : List.copyOf(publishedColumnIds);
    }
}
