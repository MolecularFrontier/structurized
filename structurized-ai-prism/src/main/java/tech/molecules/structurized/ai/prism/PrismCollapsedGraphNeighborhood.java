package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismCollapsedGraphNeighborhood(
        PrismGraphSummary graph,
        PrismRowMember center,
        int neighborCount,
        int edgeCount,
        String outputMode,
        int returnedNeighbors,
        List<PrismCollapsedGraphNeighbor> neighbors
) {
    public PrismCollapsedGraphNeighborhood {
        neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
    }
}
