package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGraphPathStep(
        String fromRowId,
        String toRowId,
        int rawEdgeCount,
        List<PrismMmpTransformText> exampleTransforms
) {
    public PrismGraphPathStep {
        exampleTransforms = exampleTransforms == null ? List.of() : List.copyOf(exampleTransforms);
    }
}
