package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGraphNeighborhood(
        PrismGraphSummary graph,
        PrismRowMember center,
        int neighborCount,
        int edgeCount,
        List<PrismGraphNeighbor> neighbors
) {
    public PrismGraphNeighborhood {
        neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
    }
}
