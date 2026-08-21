package tech.molecules.structurized.workbench.prism;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.analytics.mmp.MmpEndpointStatsRun;
import tech.molecules.structurized.analytics.mmp.SqliteMmpAnalyticsRepository;
import tech.molecules.structurized.mmp.MmpPair;
import tech.molecules.structurized.mmp.MmpTransformStats;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.EndpointType;
import tech.molecules.structurized.prism.model.EvaluationMode;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.workbench.model.PrismStructureProvider;
import tech.molecules.structurized.workbench.model.PrismWorkbenchModel;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class MmpWorkbenchPanelTest {
    @BeforeEach
    void requireGraphicsEnvironment() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing editor requires a graphics environment");
    }

    @Test
    void preflightEnablesRunForMappedNumericEndpoint(@TempDir Path tempDir) throws Exception {
        InMemoryPrismDataset dataset = dataset("assay:ic50:measured", "ASSAY_MEASURED", "ASSAYS");
        MmpWorkbenchPanel panel = panelFor(tempDir, dataset);

        assertTrue(panel.isRunEnabled());
        assertTrue(panel.getPreflightText().contains("Selected endpoints: 1"));
        assertTrue(panel.getPreflightText().contains("Subjects with parsed structures: 2"));
    }

    @Test
    void preflightInfersNeonEndpointMeasuredSubjectSet(@TempDir Path tempDir) throws Exception {
        InMemoryPrismDataset dataset = dataset(
                "/prism/endpoints/ic50/measured-subjects",
                "endpoint_measured_subjects",
                "neon2-prism"
        );
        MmpWorkbenchPanel panel = panelFor(tempDir, dataset);

        assertTrue(panel.isRunEnabled());
        assertTrue(panel.getPreflightText().contains("ic50 -> /prism/endpoints/ic50/measured-subjects subjects=2"));
    }

    @Test
    void persistedResultsFilterByCutCountAndDriveChemistryDetail(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("persisted.sqlite");
        MmpPair oneCut = MmpChemistryDetailPanelTest.pair(1,
                MmpTestFragments.idcode(6, 1),
                MmpTestFragments.idcode(7, 1),
                MmpTestFragments.idcode(8, 1));
        MmpPair twoCut = MmpChemistryDetailPanelTest.pair(2,
                MmpTestFragments.idcode(6, 2),
                MmpTestFragments.idcode(7, 2),
                MmpTestFragments.idcode(8, 2));
        List<MmpTransformStats> stats = List.of(
                MmpChemistryDetailPanelTest.stats(oneCut),
                MmpChemistryDetailPanelTest.stats(twoCut));
        try (SqliteMmpAnalyticsRepository repository = SqliteMmpAnalyticsRepository.open(database)) {
            repository.saveStatsRun(new MmpEndpointStatsRun(
                    "run-1", "solubility", "measured", "global", "mmp-hash", "stats-hash",
                    Instant.parse("2026-08-06T10:00:00Z"), 12, 10, 2, 2, null), stats);
        }

        AtomicReference<MmpWorkbenchPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MmpWorkbenchPanel panel = new MmpWorkbenchPanel();
            panel.setDatabasePath(database);
            panel.selectRunRow(0);
            assertEquals(2, panel.visibleTransformCount());

            panel.setCutFilter(1);
            assertEquals(1, panel.visibleTransformCount());
            panel.setCutFilter(2);
            assertEquals(1, panel.visibleTransformCount());

            panel.selectTransformRow(0);
            assertEquals(2, panel.chemistryDetail().displayedCutCount());
            assertNotNull(panel.chemistryDetail().displayedKey());
            panelRef.set(panel);
        });
        assertNotNull(panelRef.get());
    }

    private static MmpWorkbenchPanel panelFor(Path tempDir, InMemoryPrismDataset dataset) throws Exception {
        AtomicReference<MmpWorkbenchPanel> panelRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            MmpWorkbenchPanel panel = new MmpWorkbenchPanel();
            panel.setModel(PrismWorkbenchModel.of(tempDir, dataset), PrismStructureProvider.from(dataset));
            panel.setDatabasePath(tempDir.resolve("mmp.db"));
            panelRef.set(panel);
        });
        return panelRef.get();
    }

    private static InMemoryPrismDataset dataset(String setId, String setType, String scope) {
        return InMemoryPrismDataset.builder()
                .addEndpointDefinition(EndpointDefinition.builder()
                        .id("ic50")
                        .name("IC50")
                        .path("assay/ic50")
                        .datatype(EndpointDataType.NUMERIC)
                        .endpointType(EndpointType.MEASURED)
                        .evaluationMode(EvaluationMode.IMMEDIATE)
                        .build())
                .addSubjectRecord(SubjectRecord.builder().subjectId("cmp-1").smiles("Cc1ccccc1").build())
                .addSubjectRecord(SubjectRecord.builder().subjectId("cmp-2").smiles("CCc1ccccc1").build())
                .addSubjectSet(SubjectSet.builder()
                        .id(setId)
                        .name("IC50 measured")
                        .setType(setType)
                        .subjectSetScope(scope)
                        .build())
                .addSubjectMembership(setId, "cmp-1")
                .addSubjectMembership(setId, "cmp-2")
                .addEndpointValue(value("cmp-1", "ic50", 1.0))
                .addEndpointValue(value("cmp-2", "ic50", 2.0))
                .build();
    }

    private static EndpointValueRecord value(String subjectId, String endpointId, double value) {
        return EndpointValueRecord.builder()
                .subjectId(subjectId)
                .endpointId(endpointId)
                .result(NumericResult.builder().mean(value).build())
                .build();
    }
}
