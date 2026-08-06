package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence API for endpoint-specific MMP statistics.
 */
public interface MmpEndpointStatsRepository {
    void saveStatsRun(MmpEndpointStatsRun run, List<MmpTransformStats> stats);

    Optional<MmpEndpointStatsRun> findStatsRun(String runId);

    List<MmpEndpointStatsRun> listStatsRuns();

    List<MmpTransformStats> listTransformStats(String runId);

    default List<MmpTransformStats> findTransformStatsBySourceFragments(
            String runId,
            int cutCount,
            Set<String> fromValueIdcodes
    ) {
        Set<String> sources = Set.copyOf(fromValueIdcodes == null ? Set.of() : fromValueIdcodes);
        if (sources.isEmpty()) return List.of();
        return listTransformStats(runId).stream()
                .filter(stats -> stats.cutCount() == cutCount)
                .filter(stats -> sources.contains(stats.fromValueIdcode()))
                .toList();
    }

    default List<MmpTransformStats> findTransformStatsByIds(
            String runId,
            Set<String> transformIds
    ) {
        Set<String> ids = Set.copyOf(transformIds == null ? Set.of() : transformIds);
        if (ids.isEmpty()) return List.of();
        return listTransformStats(runId).stream()
                .filter(stats -> ids.contains(stats.transformId()))
                .toList();
    }
}
