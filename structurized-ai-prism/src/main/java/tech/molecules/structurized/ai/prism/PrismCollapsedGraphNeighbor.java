package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismCollapsedGraphNeighbor(
        PrismRowMember row,
        int degree,
        int rawEdgeCount,
        int distinctTransformCount,
        Double meanDelta,
        Double medianDelta,
        Double minDelta,
        Double maxDelta,
        List<PrismMmpTransformText> exampleTransforms
) {
    public PrismCollapsedGraphNeighbor {
        exampleTransforms = exampleTransforms == null ? List.of() : List.copyOf(exampleTransforms);
    }
}
