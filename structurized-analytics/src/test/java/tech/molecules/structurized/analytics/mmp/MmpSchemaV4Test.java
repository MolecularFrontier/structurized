package tech.molecules.structurized.analytics.mmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpStatsAggregator;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema v4: sign counts, complete per-run pair storage, and per-run subject values. */
class MmpSchemaV4Test {

    private static MmpEndpointStatsRun run(String runId, int statsCount, int pairCount) {
        return new MmpEndpointStatsRun(runId, "endpoint-1", "subjects-1", "universe-1",
                "mmp-hash", "stats-hash", Instant.parse("2026-01-01T00:00:00Z"),
                4, 4, pairCount, statsCount, null);
    }

    private static MmpPair pair(String a, String b, double valueA, double valueB) {
        return new MmpPair(a, b, valueA, valueB, null, "KEY", "A", "B", null, 1);
    }

    private static MmpTransformStats aggregateOne(List<MmpPair> pairs) {
        return MmpStatsAggregator.aggregate(pairs,
                MmpMiningConfig.defaults().toBuilder().minTransformSupport(1).build()).getFirst();
    }

    @Test
    @DisplayName("artifact reports schema version 4")
    void schemaVersionIsFour(@TempDir Path tempDir) {
        Path artifact = tempDir.resolve("v4.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveStatsRun(run("run-1", 0, 0), List.of());
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            assertEquals(4, repository.artifactSchemaVersion());
        }
    }

    @Test
    @DisplayName("hydrogen-cut flag participates in the config hash and round trips")
    void hydrogenCutsAffectHashAndPersist(@TempDir Path tempDir) {
        MmpMiningConfig base = MmpMiningConfig.defaults();
        MmpMiningConfig withHydrogen = base.toBuilder().allowHydrogenCuts(true).build();
        assertNotEquals(MmpAnalyticsHashes.mmpConfigHash(base),
                MmpAnalyticsHashes.mmpConfigHash(withHydrogen));

        String hash = MmpAnalyticsHashes.mmpConfigHash(withHydrogen);
        Path artifact = tempDir.resolve("config.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveMiningConfig(hash, MmpMiningConfigSnapshot.from(withHydrogen));
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            MmpMiningConfig loaded = repository.findMiningConfig(hash).orElseThrow().toMiningConfig();
            assertTrue(loaded.allowHydrogenCuts());
            assertEquals(hash, MmpAnalyticsHashes.mmpConfigHash(loaded));
        }
    }

    @Test
    @DisplayName("aggregator separates increases, decreases and exact ties")
    void aggregatorComputesSignCounts() {
        MmpTransformStats stats = aggregateOne(List.of(
                pair("a1", "b1", 1.0, 2.0),
                pair("a2", "b2", 1.0, 3.0),
                pair("a3", "b3", 5.0, 4.0),
                pair("a4", "b4", 7.0, 7.0)));
        assertEquals(4, stats.supportCount());
        assertEquals(2, stats.upCount());
        assertEquals(1, stats.downCount());
        assertEquals(1, stats.tieCount());
        assertEquals(3, stats.decidedCount());
        assertEquals(0.5, stats.positiveFraction(), 1e-9);
        assertEquals(0.25, stats.tieFraction(), 1e-9);
        // Only 3 non-tied pairs, so a two-sided binomial test cannot reach p <= 0.05.
        assertFalse(stats.isDirectionTestable());
    }

    @Test
    @DisplayName("sign counts must partition the support")
    void inconsistentSignCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MmpTransformStats(
                "1|A>>B", "A", "B", 1, 10, 0.5, 0.4, 1.0, -2.0, 3.0,
                0.6, 6, 3, 0, List.of()));
    }

    @Test
    @DisplayName("every supporting pair is persisted, not a capped sample")
    void allSupportingPairsArePersisted(@TempDir Path tempDir) {
        // 25 pairs is well above the old 10-example cap.
        List<MmpPair> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pairs.add(pair("a" + i, "b" + i, 1.0, 1.0 + i));
        }
        MmpTransformStats stats = aggregateOne(pairs);
        assertEquals(25, stats.supportCount());
        assertEquals(25, stats.supportingPairs().size());

        Path artifact = tempDir.resolve("pairs.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveStatsRun(run("run-1", 1, 25), List.of(stats));
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            String transformId = stats.transformId();
            assertEquals(25, repository.countEndpointPairs("run-1", transformId));

            List<MmpPair> loaded = repository.findEndpointPairs("run-1", transformId, 100, 0);
            assertEquals(25, loaded.size());
            MmpPair first = loaded.getFirst();
            assertEquals("KEY", first.keyIdcode());
            assertEquals("A", first.fromValueIdcode());
            assertEquals("B", first.toValueIdcode());
            assertEquals(1, first.cutCount());
            assertEquals(transformId, first.transformId());

            // deltas survive persistence
            assertTrue(loaded.stream().allMatch(p -> p.delta() != null));
            assertEquals(loaded.stream().mapToDouble(MmpPair::delta).sum(),
                    pairs.stream().mapToDouble(MmpPair::delta).sum(), 1e-9);

            // paging works
            assertEquals(10, repository.findEndpointPairs("run-1", transformId, 10, 0).size());
            assertEquals(5, repository.findEndpointPairs("run-1", transformId, 10, 20).size());
            assertEquals(0, repository.findEndpointPairs("run-1", transformId, 10, 25).size());
        }
    }

    @Test
    @DisplayName("listTransformStats omits pairs; targeted lookups attach them")
    void pairAttachmentIsTargeted(@TempDir Path tempDir) {
        MmpTransformStats stats = aggregateOne(List.of(
                pair("a1", "b1", 1.0, 2.0), pair("a2", "b2", 1.0, 3.0)));
        Path artifact = tempDir.resolve("attach.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveStatsRun(run("run-1", 1, 2), List.of(stats));
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            MmpTransformStats listed = repository.listTransformStats("run-1").getFirst();
            assertTrue(listed.supportingPairs().isEmpty(), "bulk listing must not load pairs");
            assertEquals(2, listed.supportCount());
            assertEquals(2, listed.upCount());

            MmpTransformStats byId = repository
                    .findTransformStatsByIds("run-1", Set.of(stats.transformId())).getFirst();
            assertEquals(2, byId.supportingPairs().size(), "targeted lookup attaches pairs");

            MmpTransformStats bySource = repository
                    .findTransformStatsBySourceFragments("run-1", 1, Set.of("A")).getFirst();
            assertEquals(2, bySource.supportingPairs().size());
            assertEquals(2, bySource.upCount());
            assertEquals(0, bySource.tieCount());
        }
    }

    @Test
    @DisplayName("structural pairs carry no endpoint values")
    void structuralPairsAreEndpointIndependent(@TempDir Path tempDir) {
        Path artifact = tempDir.resolve("structural.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveUniverse(new MmpUniverse("universe-1", "u", List.of("s"),
                    "mmp-hash", Instant.parse("2026-01-01T00:00:00Z"), null), List.of("a1", "b1"));
            repository.replacePairs("universe-1", List.of(pair("a1", "b1", 1.0, 2.0)));
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            MmpPair loaded = repository.listPairs("universe-1").getFirst();
            assertEquals("a1", loaded.compoundIdA());
            assertEquals("KEY", loaded.keyIdcode());
            // mmp_pair is shared across endpoints, so it stores no values or deltas.
            assertEquals(null, loaded.valueA());
            assertEquals(null, loaded.valueB());
            assertEquals(null, loaded.delta());
        }
    }

    @Test
    @DisplayName("per-run subject values round trip and allow delta reconstruction")
    void subjectValuesRoundTrip(@TempDir Path tempDir) {
        Path artifact = tempDir.resolve("values.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(artifact)) {
            repository.saveStatsRun(run("run-1", 0, 0), List.of());
            repository.saveSubjectValues("run-1", Map.of("c1", 1.5, "c2", 4.5));
        }
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.openReadOnly(artifact)) {
            Map<String, Double> values = repository.findSubjectValues("run-1");
            assertEquals(2, values.size());
            assertEquals(1.5, values.get("c1"), 1e-9);
            // A delta can be evaluated even for a pair that was never mined.
            assertEquals(3.0, values.get("c2") - values.get("c1"), 1e-9);
        }
    }
}
