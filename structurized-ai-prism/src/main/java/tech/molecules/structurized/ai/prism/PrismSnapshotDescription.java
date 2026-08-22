package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotCapabilities;
import tech.molecules.structurized.prism.engine.snapshot.PrismSnapshotOrigin;

import java.util.List;

public record PrismSnapshotDescription(PrismSessionSummary summary,
                                       List<PrismEndpointSummary> endpoints,
                                       List<PrismRowSetSummary> rowSets,
                                       PrismSnapshotCapabilities capabilities,
                                       PrismSnapshotOrigin origin) {
    public PrismSnapshotDescription {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        rowSets = rowSets == null ? List.of() : List.copyOf(rowSets);
    }
}
