package tech.molecules.structurized.ai.prism;

public record PrismGraphTsvExport(
        PrismGraphSummary graph,
        String format,
        int rowCount,
        String tsv
) {
}
