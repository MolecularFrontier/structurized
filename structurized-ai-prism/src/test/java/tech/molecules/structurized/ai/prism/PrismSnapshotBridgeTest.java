package tech.molecules.structurized.ai.prism;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.prism.io.PrismSnapshotDescriptor;
import tech.molecules.structurized.prism.io.PrismSnapshotEndpoint;
import tech.molecules.structurized.prism.io.PrismSnapshotSelection;
import tech.molecules.structurized.prism.io.PrismTsvDatasetWriter;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.EndpointType;
import tech.molecules.structurized.prism.model.EvaluationMode;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PrismSnapshotBridgeTest {
    @Test
    void opensValidSnapshotButRejectsTamperedSnapshot(@TempDir Path tempDir) throws Exception {
        Path valid = tempDir.resolve("valid");
        PrismTsvDatasetWriter.writeSnapshot(valid, dataset(), descriptor());

        InMemoryPrismBridgeService bridge = new InMemoryPrismBridgeService(new InMemoryStructureRepositoryService());
        PrismDatasetSummary summary = bridge.openDataset(new OpenPrismDatasetRequest(valid, "valid", "Valid snapshot"));
        assertEquals(1, summary.subjectCount());
        assertEquals(1, summary.endpointCount());

        Path tampered = tempDir.resolve("tampered");
        PrismTsvDatasetWriter.writeSnapshot(tampered, dataset(), descriptor());
        Files.writeString(tampered.resolve("values.prism.tsv"), "tampered", StandardOpenOption.APPEND);
        assertThrows(ChemOperationException.class,
                () -> new InMemoryPrismBridgeService(new InMemoryStructureRepositoryService())
                        .openDataset(new OpenPrismDatasetRequest(tampered, "tampered", "Tampered snapshot")));
    }

    private static InMemoryPrismDataset dataset() {
        EndpointDefinition endpoint = EndpointDefinition.builder().id("clearance").name("Clearance")
                .path("shared/dmpk/clearance").datatype(EndpointDataType.NUMERIC)
                .endpointType(EndpointType.MEASURED).evaluationMode(EvaluationMode.IMMEDIATE).build();
        SubjectRecord subject = SubjectRecord.builder().subjectId("cmp-1").smiles("CCO").build();
        NumericResult result = NumericResult.builder().state(NumericState.VALUE).mean(3.5).n(1).build();
        return InMemoryPrismDataset.builder().addEndpointDefinition(endpoint).addSubjectRecord(subject)
                .addEndpointValue(EndpointValueRecord.builder().subjectId("cmp-1").endpointId("clearance")
                        .result(result).build()).build();
    }

    private static PrismSnapshotDescriptor descriptor() {
        return new PrismSnapshotDescriptor("2026-08-04T10:00:00Z", "2026-08-04T10:01:00Z",
                "test", "1", "test:source", "STRUCTURE", "smiles", "smiles", "as-supplied",
                new PrismSnapshotSelection("global", null, "subjects-1"),
                List.of(new PrismSnapshotEndpoint("clearance", "endpoint-1", Map.of())),
                Map.of(), Map.of());
    }
}
