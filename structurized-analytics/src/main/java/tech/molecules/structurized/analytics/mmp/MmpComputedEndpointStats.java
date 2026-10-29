package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete in-memory output for one endpoint statistics run. */
public record MmpComputedEndpointStats(
        MmpEndpointStatsRun run,
        List<MmpTransformStats> transformStats,
        Map<String, Double> subjectValues
) {
    public MmpComputedEndpointStats {
        run = Objects.requireNonNull(run, "run");
        transformStats = List.copyOf(transformStats == null ? List.of() : transformStats);
        subjectValues = Map.copyOf(subjectValues == null ? Map.of() : subjectValues);
    }

    public MmpComputedEndpointStats(MmpEndpointStatsRun run, List<MmpTransformStats> transformStats) {
        this(run, transformStats, Map.of());
    }
}
