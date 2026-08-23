package tech.molecules.structurized.ai.prism;

public record PrismReportSaveResult(
        boolean saved,
        String sessionId,
        String path,
        long bytes,
        PrismReportValidationSummary validation
) {
}
