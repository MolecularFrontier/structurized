package tech.molecules.structurized.ai.prism;

public record RunPrismLiveEvaluatorRequest(
        String sessionId,
        String bindingId,
        String documentId,
        Long expectedDocumentRevision
) {
}
