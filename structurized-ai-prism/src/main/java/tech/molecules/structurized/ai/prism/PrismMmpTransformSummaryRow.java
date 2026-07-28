package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismMmpTransformSummaryRow(
        String transformId,
        Integer cutCount,
        String keyFragment,
        String fromFragment,
        String toFragment,
        String transformText,
        int supportCount,
        int measuredDeltaCount,
        Double meanDelta,
        Double medianDelta,
        Double minDelta,
        Double maxDelta,
        Double positiveFraction,
        List<String> exampleEdgeIds,
        List<String> exampleSourceRowIds,
        List<String> exampleTargetRowIds
) {
    public PrismMmpTransformSummaryRow {
        exampleEdgeIds = exampleEdgeIds == null ? List.of() : List.copyOf(exampleEdgeIds);
        exampleSourceRowIds = exampleSourceRowIds == null ? List.of() : List.copyOf(exampleSourceRowIds);
        exampleTargetRowIds = exampleTargetRowIds == null ? List.of() : List.copyOf(exampleTargetRowIds);
    }
}
