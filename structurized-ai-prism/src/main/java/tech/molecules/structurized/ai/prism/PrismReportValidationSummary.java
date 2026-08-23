package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.report.PrismReportDiagnostic;
import tech.molecules.structurized.prism.report.PrismReportMetadata;

import java.util.List;

public record PrismReportValidationSummary(
        String sessionId,
        String source,
        boolean valid,
        int errorCount,
        int warningCount,
        PrismReportMetadata metadata,
        int blockCount,
        List<String> referencedColumnIds,
        List<String> referencedRowSetIds,
        List<PrismReportDiagnostic> diagnostics
) {
    public PrismReportValidationSummary {
        referencedColumnIds = List.copyOf(referencedColumnIds);
        referencedRowSetIds = List.copyOf(referencedRowSetIds);
        diagnostics = List.copyOf(diagnostics);
    }
}
