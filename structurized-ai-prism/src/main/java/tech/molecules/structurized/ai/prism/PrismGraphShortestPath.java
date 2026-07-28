package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGraphShortestPath(
        PrismGraphSummary graph,
        PrismRowMember source,
        PrismRowMember target,
        boolean connected,
        Integer distance,
        int searchedDepth,
        String reason,
        List<PrismRowMember> pathRows,
        List<PrismGraphPathStep> steps
) {
    public PrismGraphShortestPath {
        pathRows = pathRows == null ? List.of() : List.copyOf(pathRows);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
