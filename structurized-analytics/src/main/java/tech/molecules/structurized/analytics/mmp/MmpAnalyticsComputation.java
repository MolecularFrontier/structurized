package tech.molecules.structurized.analytics.mmp;

import java.util.List;

/** Complete provider- and repository-independent result of one analytics computation. */
public record MmpAnalyticsComputation(
        List<MmpComputedUniverse> universes,
        List<MmpComputedEndpointStats> endpointStats,
        int requestedEndpointCount,
        List<String> warnings
) {
    public MmpAnalyticsComputation {
        universes = List.copyOf(universes == null ? List.of() : universes);
        endpointStats = List.copyOf(endpointStats == null ? List.of() : endpointStats);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        if (requestedEndpointCount < 0) {
            throw new IllegalArgumentException("requestedEndpointCount must not be negative");
        }
    }

    public MmpEndpointStatsComputationResult summary() {
        return new MmpEndpointStatsComputationResult(
                universes.stream().map(MmpComputedUniverse::universe).toList(),
                endpointStats.stream().map(MmpComputedEndpointStats::run).toList(),
                requestedEndpointCount,
                universes.stream().mapToInt(MmpComputedUniverse::structuralSubjectCount).sum(),
                universes.stream().mapToInt(MmpComputedUniverse::missingStructureCount).sum(),
                universes.stream().mapToInt(universe -> universe.fragmentationRecords().size()).sum(),
                universes.stream().mapToInt(universe -> universe.pairs().size()).sum(),
                warnings
        );
    }
}
