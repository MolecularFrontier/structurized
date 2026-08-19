package tech.molecules.structurized.analytics.mmp;

import com.actelion.research.chem.Canonizer;
import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpMiner;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpMiningResult;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpSelectionMode;
import tech.molecules.structurized.mmp.MmpStatsAggregator;
import tech.molecules.structurized.mmp.MmpTransformStats;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpRecommendationServiceTest {
    @Test
    void primaryRunGeneratesProductsAndSecondaryRunAnnotatesThem(@TempDir Path tempDir)
            throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        StereoMolecule target = parse("CCc1ccccc1");
        MmpMiningConfig config = config();
        MmpMiningResult mined = MmpMiner.mine(List.of(
                new MmpInputCompound("source", source, 1.0),
                new MmpInputCompound("target", target, 2.0)), config);
        List<MmpTransformStats> primaryStats =
                MmpStatsAggregator.aggregate(mined.pairs(), config);
        List<MmpTransformStats> secondaryStats = primaryStats.stream()
                .map(stats -> new MmpTransformStats(
                        stats.transformId(), stats.fromValueIdcode(), stats.toValueIdcode(),
                        stats.cutCount(), stats.supportCount(), -stats.meanDelta(),
                        -stats.medianDelta(), stats.standardDeviation(),
                        -stats.maxDelta(), -stats.minDelta(),
                        1.0 - stats.positiveFraction(), stats.examplePairs()))
                .toList();
        Path database = tempDir.resolve("recommend.sqlite");
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveStatsRun(run("primary", primaryStats.size()), primaryStats);
            repository.saveStatsRun(run("secondary", secondaryStats.size()), secondaryStats);
            MmpRecommendationRequest request = MmpRecommendationRequest.defaults(
                    canonical(source), Set.of(), MmpSelectionMode.ALL_SITES,
                    List.of(
                            new MmpEndpointPreference(
                                    "primary", MmpOptimizationDirection.HIGHER_IS_BETTER),
                            new MmpEndpointPreference(
                                    "secondary", MmpOptimizationDirection.LOWER_IS_BETTER)),
                    "primary", config);

            MmpRecommendationResult result =
                    new MmpRecommendationService(repository).recommend(request);

            MmpRecommendationCandidate targetCandidate = result.candidates().stream()
                    .filter(candidate -> candidate.productIdcode().equals(canonical(target)))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(targetCandidate.statsFor("primary"));
            assertNotNull(targetCandidate.statsFor("secondary"));
            assertFalse(targetCandidate.attachments().isEmpty());
            assertTrue(result.diagnostics().applicationAttemptCount() > 0);
            assertEquals(result.candidates().size(), result.diagnostics().resultCount());
            MmpPair example = targetCandidate.statsFor("primary").examplePairs().getFirst();
            MmpPairStructureEvidence evidence = MmpPairStructureEvidence.reconstruct(example);
            assertNotNull(evidence.compoundAIdcode());
            assertNotNull(evidence.compoundBIdcode());
        }
    }

    @Test
    void targetedRepositoryQueriesRetainOnlyRequestedStatsAndExamples(@TempDir Path tempDir)
            throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        StereoMolecule target = parse("CCc1ccccc1");
        MmpMiningConfig config = config();
        List<MmpTransformStats> stats = MmpStatsAggregator.aggregate(
                MmpMiner.mine(List.of(
                        new MmpInputCompound("source", source, 1.0),
                        new MmpInputCompound("target", target, 2.0)), config).pairs(),
                config);
        MmpTransformStats wanted = stats.getFirst();
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(tempDir.resolve("query.sqlite"))) {
            repository.saveStatsRun(run("primary", stats.size()), stats);

            List<MmpTransformStats> bySource = repository.findTransformStatsBySourceFragments(
                    "primary", wanted.cutCount(), Set.of(wanted.fromValueIdcode()));
            List<MmpTransformStats> byId = repository.findTransformStatsByIds(
                    "primary", Set.of(wanted.transformId()));

            assertTrue(bySource.stream()
                    .allMatch(value -> value.fromValueIdcode().equals(wanted.fromValueIdcode())));
            assertEquals(List.of(wanted.transformId()),
                    byId.stream().map(MmpTransformStats::transformId).toList());
            assertFalse(byId.getFirst().examplePairs().isEmpty());
        }
    }

    @Test
    void rejectsMixedUniversesAndMissingSelections(@TempDir Path tempDir) throws Exception {
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(tempDir.resolve("invalid.sqlite"))) {
            repository.saveStatsRun(run("primary", 0), List.of());
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "other", "other", "set", "different-universe", "hash", "stats",
                    Instant.parse("2026-08-06T10:00:00Z"), 2, 2, 0, 0, null), List.of());
            MmpRecommendationRequest request = MmpRecommendationRequest.defaults(
                    canonical(parse("CC")), Set.of(), MmpSelectionMode.ALL_SITES,
                    List.of(
                            new MmpEndpointPreference("primary", MmpOptimizationDirection.NEUTRAL),
                            new MmpEndpointPreference("other", MmpOptimizationDirection.NEUTRAL)),
                    "primary", config());

            assertThrows(IllegalArgumentException.class,
                    () -> new MmpRecommendationService(repository).recommend(request));
            assertThrows(IllegalArgumentException.class,
                    () -> MmpRecommendationRequest.defaults(
                            canonical(parse("CC")), Set.of(), MmpSelectionMode.EDITABLE_REGION,
                            List.of(new MmpEndpointPreference(
                                    "primary", MmpOptimizationDirection.NEUTRAL)),
                            "primary", config()));
        }
    }

    private static MmpEndpointStatsRun run(String id, int statsCount) {
        return new MmpEndpointStatsRun(
                id, id, "set", "universe", "hash", "stats",
                Instant.parse("2026-08-06T10:00:00Z"), 2, 2, 2, statsCount, null);
    }

    private static MmpMiningConfig config() {
        return MmpMiningConfig.builder()
                .maxCuts(2)
                .minKeyHeavyAtoms(1)
                .maxVariableHeavyAtoms(20)
                .maxVariableToMolHeavyAtomFraction(1.0)
                .minTransformSupport(1)
                .build();
    }

    private static StereoMolecule parse(String smiles) throws Exception {
        StereoMolecule molecule = new StereoMolecule();
        new SmilesParser().parse(molecule, smiles);
        molecule.ensureHelperArrays(Molecule.cHelperRings);
        return molecule;
    }

    private static String canonical(StereoMolecule molecule) {
        return new Canonizer(molecule).getIDCode();
    }
}
