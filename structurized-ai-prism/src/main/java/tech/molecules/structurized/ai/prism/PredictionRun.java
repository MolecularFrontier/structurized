package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.List;
import java.util.Map;

public record PredictionRun(
        PrismAnalysisSummary summary,
        PredictionCapability capability,
        List<PredictionInput> inputs,
        List<PredictionValue> values,
        Map<String, Object> options
) implements PrismAnalysis {
    public PredictionRun {
        if (summary == null) {
            throw new IllegalArgumentException("summary must not be null");
        }
        if (capability == null) {
            throw new IllegalArgumentException("capability must not be null");
        }
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        values = values == null ? List.of() : List.copyOf(values);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
