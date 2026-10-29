package tech.molecules.structurized.mmp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates numeric deltas for directed MMP transformations.
 */
public final class MmpStatsAggregator {
    private MmpStatsAggregator() {}

    public static List<MmpTransformStats> aggregate(List<MmpPair> pairs, MmpMiningConfig config) {
        Objects.requireNonNull(pairs, "pairs");
        Objects.requireNonNull(config, "config");

        Map<String, List<MmpPair>> byTransform = new HashMap<>();
        for (MmpPair pair : pairs) {
            if (pair.delta() == null) {
                continue;
            }
            byTransform.computeIfAbsent(pair.transformId(), ignored -> new ArrayList<>()).add(pair);
        }

        List<MmpTransformStats> stats = new ArrayList<>();
        for (Map.Entry<String, List<MmpPair>> entry : byTransform.entrySet()) {
            List<MmpPair> supportPairs = entry.getValue();
            if (supportPairs.size() < config.minTransformSupport()) {
                continue;
            }
            supportPairs.sort(Comparator.comparing(MmpPair::compoundIdA).thenComparing(MmpPair::compoundIdB));
            stats.add(toStats(entry.getKey(), supportPairs));
        }
        stats.sort(Comparator.comparing(MmpTransformStats::transformId));
        return stats;
    }

    private static MmpTransformStats toStats(String transformId, List<MmpPair> pairs) {
        List<Double> deltas = pairs.stream().map(MmpPair::delta).sorted().toList();
        double sum = deltas.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / deltas.size();
        double median = median(deltas);
        double variance = deltas.stream()
                .mapToDouble(delta -> {
                    double centered = delta - mean;
                    return centered * centered;
                })
                .sum() / deltas.size();
        int upCount = 0;
        int downCount = 0;
        int tieCount = 0;
        for (double delta : deltas) {
            if (delta > 0.0) {
                upCount++;
            } else if (delta < 0.0) {
                downCount++;
            } else {
                tieCount++;
            }
        }
        double positiveFraction = upCount / (double) deltas.size();
        MmpPair firstPair = pairs.getFirst();
        return new MmpTransformStats(
                transformId,
                firstPair.fromValueIdcode(),
                firstPair.toValueIdcode(),
                firstPair.cutCount(),
                deltas.size(),
                mean,
                median,
                Math.sqrt(variance),
                deltas.getFirst(),
                deltas.getLast(),
                positiveFraction,
                upCount,
                downCount,
                tieCount,
                pairs
        );
    }

    private static double median(List<Double> sortedValues) {
        int size = sortedValues.size();
        int mid = size / 2;
        if (size % 2 == 1) {
            return sortedValues.get(mid);
        }
        return (sortedValues.get(mid - 1) + sortedValues.get(mid)) / 2.0;
    }
}
