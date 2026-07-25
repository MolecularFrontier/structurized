package tech.molecules.structurized.ai.prism;

import java.util.List;

public record PrismSessionInfo(
        PrismSessionSummary summary,
        List<PrismSubjectSetSummary> subjectSets,
        List<PrismEndpointSummary> endpoints,
        List<PrismRowSetSummary> rowSets
) {
    public PrismSessionInfo {
        subjectSets = subjectSets == null ? List.of() : List.copyOf(subjectSets);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        rowSets = rowSets == null ? List.of() : List.copyOf(rowSets);
    }
}
