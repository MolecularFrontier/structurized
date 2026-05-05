package tech.molecules.structurized.workbench;

import org.junit.jupiter.api.Test;
import tech.molecules.structurized.prism.model.EndpointDataType;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.EndpointType;
import tech.molecules.structurized.prism.model.EvaluationMode;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.workbench.model.NumericEndpointAnalysis;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumericEndpointAnalysisTest {

    @Test
    void computesNumericSummaryAndMissingCounts() {
        EndpointDefinition endpoint = numericEndpoint();
        InMemoryPrismDataset dataset = InMemoryPrismDataset.builder()
                .addEndpointDefinition(endpoint)
                .addSubjectRecord(subject("cmp-1"))
                .addSubjectRecord(subject("cmp-2"))
                .addSubjectRecord(subject("cmp-3"))
                .addSubjectRecord(subject("cmp-4"))
                .addEndpointValue(value("cmp-1", 1.0, 2, "2026-01-01", "2026-01-03"))
                .addEndpointValue(value("cmp-2", 3.0, 1, "2026-01-02", "2026-01-02"))
                .addEndpointValue(notMeasured("cmp-3"))
                .build();

        NumericEndpointAnalysis analysis = NumericEndpointAnalysis.analyze(
                dataset,
                endpoint,
                null,
                List.of("cmp-1", "cmp-2", "cmp-3", "cmp-4")
        );

        assertEquals(4, analysis.subjectCount());
        assertEquals(2, analysis.measuredCount());
        assertEquals(1, analysis.notMeasuredCount());
        assertEquals(1, analysis.missingRecordCount());
        assertEquals(2.0, analysis.summary().mean(), 1.0e-12);
        assertEquals(2.0, analysis.summary().median(), 1.0e-12);
        assertEquals(2, analysis.valueRows().getFirst().rawValueCount());
    }

    private static EndpointDefinition numericEndpoint() {
        return EndpointDefinition.builder()
                .id("ic50")
                .name("IC50")
                .path("assay/ic50")
                .datatype(EndpointDataType.NUMERIC)
                .endpointType(EndpointType.MEASURED)
                .evaluationMode(EvaluationMode.IMMEDIATE)
                .build();
    }

    private static SubjectRecord subject(String subjectId) {
        return SubjectRecord.builder().subjectId(subjectId).build();
    }

    private static EndpointValueRecord value(String subjectId, double mean, int rawCount, String first, String last) {
        NumericResult.Builder builder = NumericResult.builder()
                .mean(mean)
                .n(rawCount)
                .firstMeasurement(first)
                .lastMeasurement(last);
        for (int i = 0; i < rawCount; i++) {
            builder.addRawValue(mean + i).addRawValueId(subjectId + "-raw-" + i);
        }
        return EndpointValueRecord.builder()
                .subjectId(subjectId)
                .endpointId("ic50")
                .result(builder.build())
                .build();
    }

    private static EndpointValueRecord notMeasured(String subjectId) {
        return EndpointValueRecord.builder()
                .subjectId(subjectId)
                .endpointId("ic50")
                .result(NumericResult.builder().state(NumericState.NOT_MEASURED).build())
                .build();
    }
}
