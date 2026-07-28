package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismMmpTransformSummary(
        PrismGraphSummary graph,
        String sortBy,
        int totalTransforms,
        int returnedTransforms,
        int offset,
        int limit,
        List<PrismMmpTransformSummaryRow> transforms
) {
    public PrismMmpTransformSummary {
        transforms = transforms == null ? List.of() : List.copyOf(transforms);
    }
}
