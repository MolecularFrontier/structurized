package tech.molecules.structurized.ai.prism;

import java.util.Map;

public record PredictionInput(
        String inputId,
        String structure,
        Map<String, Object> features
) {
    public PredictionInput {
        if (inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("inputId must not be blank");
        }
        structure = structure == null ? "" : structure;
        features = features == null ? Map.of() : Map.copyOf(features);
    }
}
