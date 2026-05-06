package tech.molecules.structurized.workbench.prism;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MmpWorkbenchPanelTest {
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
