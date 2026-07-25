package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.List;
import java.util.Map;

public record PredictionRequest(
        PredictionContext context,
        PredictionCapability capability,
        String endpointId,
        List<PredictionInput> inputs,
        Map<String, Object> options
) {
    public PredictionRequest {
        if (capability == null) {
            throw new IllegalArgumentException("capability must not be null");
        }
        endpointId = endpointId == null || endpointId.isBlank() ? capability.endpointId() : endpointId.trim();
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
