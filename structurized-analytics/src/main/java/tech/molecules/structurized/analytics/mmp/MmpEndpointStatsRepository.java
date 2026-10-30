package tech.molecules.structurized.analytics.mmp;

import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence API for endpoint-specific MMP statistics.
 */
public interface MmpEndpointStatsRepository {
    /** Default page size for supporting-pair lookups. */
    int DEFAULT_PAIR_PAGE_SIZE = 100;

    void saveStatsRun(MmpEndpointStatsRun run, List<MmpTransformStats> stats);

    /**
     * Stores the per-subject endpoint values backing one run.
     *
     * <p>Values are O(subjects) while pairs are O(pairs), and unlike the pair table they also allow
     * a delta to be evaluated for a pair that was never mined.</p>
     */
    default void saveSubjectValues(String runId, Map<String, Double> valuesBySubjectId) {
        // Optional capability; in-memory repositories may ignore it.
    }

    /** Returns the per-subject endpoint values for one run. */
    default Map<String, Double> findSubjectValues(String runId) {
        return Map.of();
    }

    Optional<MmpEndpointStatsRun> findStatsRun(String runId);

    List<MmpEndpointStatsRun> listStatsRuns();

    /**
     * Lists transform statistics for one run <em>without</em> supporting pairs, because a run can
     * hold millions of them. Use {@link #findEndpointPairs} for a specific transform.
     */
    List<MmpTransformStats> listTransformStats(String runId);

    /**
     * Returns a page of supporting pairs for one transform, with real measured values and deltas.
     * Every supporting pair is retained, so even single-pair transforms can be shown in full.
     */
    default List<MmpPair> findEndpointPairs(String runId, String transformId, int limit, int offset) {
        return listTransformStats(runId).stream()
                .filter(stats -> stats.transformId().equals(transformId))
                .findFirst()
                .map(stats -> stats.supportingPairs().stream().skip(offset).limit(limit).toList())
                .orElseGet(List::of);
    }

    /** Returns a page of supporting pairs using {@link #DEFAULT_PAIR_PAGE_SIZE}. */
    default List<MmpPair> findEndpointPairs(String runId, String transformId) {
        return findEndpointPairs(runId, transformId, DEFAULT_PAIR_PAGE_SIZE, 0);
    }

    /** Returns the total number of supporting pairs stored for one transform. */
    default int countEndpointPairs(String runId, String transformId) {
        return listTransformStats(runId).stream()
                .filter(stats -> stats.transformId().equals(transformId))
                .findFirst()
                .map(MmpTransformStats::supportCount)
                .orElse(0);
    }

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
    /** Summary-only, bounded lookup. Exceeding the bound aborts rather than biases ranking. */
    default List<MmpTransformStats> findTransformSummariesBySourceFragments(
            String runId,int cutCount,Set<String> sources,int maxRows) {
        if(maxRows<0)throw new IllegalArgumentException("maxRows must be non-negative");
        var rows=listTransformStats(runId).stream().filter(s->s.cutCount()==cutCount && sources.contains(s.fromValueIdcode()))
                .limit((long)maxRows+1).map(s->s.withSupportingPairs(List.of())).toList();
        if(rows.size()>maxRows)throw new MmpRecommendationLimitException(MmpRecommendationLimitException.Limit.TRANSFORM_STATISTICS);
        return rows;
    }

    /** Summary-only evidence; no example pair loading. */
    default List<MmpTransformStats> findTransformSummariesByIds(String runId,Set<String> ids) {
        return listTransformStats(runId).stream().filter(s->ids.contains(s.transformId()))
                .map(s->s.withSupportingPairs(List.of())).toList();
    }

}
