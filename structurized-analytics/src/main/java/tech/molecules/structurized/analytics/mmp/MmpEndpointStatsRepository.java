package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.List;
import java.util.Optional;

/**
 * Persistence API for endpoint-specific MMP statistics.
 */
public interface MmpEndpointStatsRepository {
    void saveStatsRun(MmpEndpointStatsRun run, List<MmpTransformStats> stats);

    Optional<MmpEndpointStatsRun> findStatsRun(String runId);

    List<MmpEndpointStatsRun> listStatsRuns();

    List<MmpTransformStats> listTransformStats(String runId);
}
