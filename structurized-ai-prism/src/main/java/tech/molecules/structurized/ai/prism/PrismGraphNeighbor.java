package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismGraphNeighbor(
        PrismRowMember row,
        int degree,
        List<PrismGraphEdgeView> edges
) {
    public PrismGraphNeighbor {
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
