package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.List;

public record PredictionRunView(
        PredictionRunSummary summary,
        PredictionCapability capability,
        int totalValues,
        int offset,
        int limit,
        List<PredictionValue> values
) {
    public PredictionRunView {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
