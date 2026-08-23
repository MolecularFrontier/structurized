package tech.molecules.structurized.ai.prism;

public record PrismReportPublicationResult(
        boolean published,
        String sessionId,
        String viewId,
        String title,
        PrismReportValidationSummary validation
) {
}
