package tech.molecules.structurized.mmp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory matched molecular pair miner.
 */
public final class MmpMiner {
    private MmpMiner() {}

    public static MmpMiningResult mine(List<MmpInputCompound> compounds, MmpMiningConfig config) {
        Objects.requireNonNull(compounds, "compounds");
        Objects.requireNonNull(config, "config");

        Map<String, MmpInputCompound> inputById = new HashMap<>();
        List<MmpFragmentationRecord> records = new ArrayList<>();
        for (MmpInputCompound compound : compounds) {
            inputById.put(compound.compoundId(), compound);
            records.addAll(MmpFragmenter.fragment(compound, config));
        }
        records.sort(Comparator.comparing(MmpFragmentationRecord::canonicalRecordId));

        List<MmpPair> pairs = buildPairs(records, inputById, config);
        List<MmpTransformStats> stats = MmpStatsAggregator.aggregate(pairs, config);
        return new MmpMiningResult(records, pairs, stats);
    }

    private static List<MmpPair> buildPairs(List<MmpFragmentationRecord> records,
            Map<String, MmpInputCompound> inputById, MmpMiningConfig config) {
        List<MmpPair> pairs = new ArrayList<>();
        forEachPair(records, config, pair -> {
            Double a = inputById.get(pair.compoundIdA()).value();
            Double b = inputById.get(pair.compoundIdB()).value();
            pairs.add(new MmpPair(pair.compoundIdA(), pair.compoundIdB(), a, b, null,
                    pair.keyIdcode(), pair.fromValueIdcode(), pair.toValueIdcode(), pair.transformId(), pair.cutCount()));
        });
        pairs.sort(Comparator.comparing(MmpPair::transformId).thenComparing(MmpPair::compoundIdA)
                .thenComparing(MmpPair::compoundIdB).thenComparing(MmpPair::keyIdcode));
        return pairs;
    }

    /** Streams all structural pairs; never retains the quadratic pair list. Positive limits fail,
     * rather than silently truncate. Zero limits mean unlimited. */
    public static void forEachPair(List<MmpFragmentationRecord> records, MmpMiningConfig config,
            java.util.function.Consumer<MmpPair> sink) {
        Map<KeyGroup, List<MmpFragmentationRecord>> groups = new java.util.TreeMap<>();
        for (var record : records) {
            groups.computeIfAbsent(new KeyGroup(record.cutCount(), record.keyIdcode()), ignored -> new ArrayList<>()).add(record);
        }
        for (var group : groups.values()) {
            group.sort(Comparator.comparing(MmpFragmentationRecord::compoundId).thenComparing(MmpFragmentationRecord::valueIdcode));
            long emitted = 0;
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    var a = group.get(i); var b = group.get(j);
                    if (a.compoundId().equals(b.compoundId()) || a.valueIdcode().equals(b.valueIdcode())) continue;
                    emitted += config.emitReverseTransforms() ? 2 : 1;
                    if (config.maxPairsPerKey() > 0 && emitted > config.maxPairsPerKey()) {
                        throw new IllegalStateException("Pair safety limit exceeded; no complete result can be published");
                    }
                    sink.accept(new MmpPair(a.compoundId(), b.compoundId(), null, null, null,
                            a.keyIdcode(), a.valueIdcode(), b.valueIdcode(), null, a.cutCount()));
                    if (config.emitReverseTransforms()) sink.accept(new MmpPair(b.compoundId(), a.compoundId(), null, null, null,
                            b.keyIdcode(), b.valueIdcode(), a.valueIdcode(), null, b.cutCount()));
                }
            }
        }
    }

    private record KeyGroup(int cutCount, String keyIdcode) implements Comparable<KeyGroup> {
        @Override
        public int compareTo(KeyGroup other) {
            int cutCmp = Integer.compare(cutCount, other.cutCount);
            if (cutCmp != 0) {
                return cutCmp;
            }
            return keyIdcode.compareTo(other.keyIdcode);
        }
    }
}
