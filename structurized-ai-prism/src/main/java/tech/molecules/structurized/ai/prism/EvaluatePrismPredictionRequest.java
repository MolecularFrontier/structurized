package tech.molecules.structurized.ai.prism;

public record EvaluatePrismPredictionRequest(
        String sessionId,
        String rowSetId,
        String predictionRunId,
        String label,
        String endpointId,
        String capabilityId,
        String mode,
        Boolean publishValue,
        Boolean publishStatus,
        Boolean publishUncertainty,
        Boolean publishApplicability
) {
}
