package tech.molecules.structurized.workbench.prism;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.IDCodeParser;
import com.actelion.research.chem.SmilesParser;
import com.actelion.research.chem.StereoMolecule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationRequest;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationResult;
import tech.molecules.structurized.analytics.mmp.MmpRecommendationService;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpInputCompound;
import tech.molecules.structurized.mmp.MmpMiner;
import tech.molecules.structurized.mmp.MmpMiningConfig;
import tech.molecules.structurized.mmp.MmpStatsAggregator;
import tech.molecules.structurized.mmp.MmpTransformStats;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpRecommendationPanelTest {
    @Test
    void editorBuildsRequestAndDisplaysGeneratedEvidence(@TempDir Path tempDir) throws Exception {
        StereoMolecule source = parse("Cc1ccccc1");
        StereoMolecule target = parse("CCc1ccccc1");
        MmpMiningConfig config = config();
        List<MmpTransformStats> stats = MmpStatsAggregator.aggregate(
                MmpMiner.mine(List.of(
                        new MmpInputCompound("source", source, 1.0),
                        new MmpInputCompound("target", target, 2.0)), config).pairs(),
                config);
        Path database = tempDir.resolve("recommend-ui.sqlite");
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "primary", "solubility", "set", "universe", "hash", "stats",
                    Instant.parse("2026-08-06T10:00:00Z"), 2, 2, 2, stats.size(), null), stats);
        }

        AtomicReference<MmpRecommendationPanel> panelRef = new AtomicReference<>();
        AtomicReference<MmpRecommendationRequest> requestRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MmpRecommendationPanel panel = new MmpRecommendationPanel();
            panel.setDatabasePath(database);
            Set<Integer> allAtoms = new LinkedHashSet<>();
            for (int atom = 0; atom < source.getAllAtoms(); atom++) allAtoms.add(atom);
            panel.setInputMolecule(source, allAtoms);
            panelRef.set(panel);
            requestRef.set(panel.buildRequest());
        });

        MmpRecommendationResult result;
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(database)) {
            MmpRecommendationRequest request = requestRef.get();
            request = new MmpRecommendationRequest(
                    request.inputIdcode(), request.selectedAtomIndices(), request.selectionMode(),
                    request.endpointPreferences(), request.primaryRunId(), config,
                    request.maxResults(), request.maxApplicationAttempts());
            result = new MmpRecommendationService(repository).recommend(request);
        }
        assertFalse(result.candidates().isEmpty());

        MmpRecommendationResult finalResult = result;
        SwingUtilities.invokeAndWait(() -> {
            MmpRecommendationPanel panel = panelRef.get();
            panel.applyResultForTest(finalResult);
            assertEquals(finalResult.candidates().size(), panel.resultCount());
            assertTrue(panel.evidenceCount() > 0);
        });
    }

    @Test
    void editableRegionRequiresAnAtomSelection(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("empty-selection.sqlite");
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "primary", "endpoint", "set", "universe", "hash", "stats",
                    Instant.parse("2026-08-06T10:00:00Z"), 0, 0, 0, 0, null), List.of());
        }
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MmpRecommendationPanel panel = new MmpRecommendationPanel();
            panel.setDatabasePath(database);
            panel.setInputMolecule(parseUnchecked("CC"), Set.of());
            thrown.set(assertThrows(IllegalArgumentException.class, panel::buildRequest));
        });
        assertTrue(thrown.get().getMessage().contains("Select editable atoms"));
    }

    @Test
    void remapsEditorSelectionToCanonicalIdcodeAtomIndices(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("selection-mapping.sqlite");
        try (SqliteMmpAnalyticsRepository repository =
                     SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "primary", "endpoint", "set", "universe", "hash", "stats",
                    Instant.parse("2026-08-06T10:00:00Z"), 0, 0, 0, 0, null), List.of());
        }
        StereoMolecule editorMolecule = parse("CCN(CC)CCO");
        int editorOxygen = atomWithAtomicNumber(editorMolecule, 8);
        AtomicReference<MmpRecommendationRequest> requestRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MmpRecommendationPanel panel = new MmpRecommendationPanel();
            panel.setDatabasePath(database);
            panel.setInputMolecule(editorMolecule, Set.of(editorOxygen));
            requestRef.set(panel.buildRequest());
        });

        MmpRecommendationRequest request = requestRef.get();
        StereoMolecule canonicalMolecule = new StereoMolecule();
        new IDCodeParser().parse(canonicalMolecule, request.inputIdcode());
        int canonicalOxygen = atomWithAtomicNumber(canonicalMolecule, 8);

        assertFalse(editorOxygen == canonicalOxygen,
                "fixture must exercise canonical atom reordering");
        assertEquals(Set.of(canonicalOxygen), request.selectedAtomIndices());
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

    private static StereoMolecule parseUnchecked(String smiles) {
        try {
            return parse(smiles);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int atomWithAtomicNumber(StereoMolecule molecule, int atomicNumber) {
        for (int atom = 0; atom < molecule.getAllAtoms(); atom++) {
            if (molecule.getAtomicNo(atom) == atomicNumber) return atom;
        }
        throw new IllegalArgumentException("no atom with atomic number " + atomicNumber);
    }
}
