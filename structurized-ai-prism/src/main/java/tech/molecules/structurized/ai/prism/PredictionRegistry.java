package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.List;
import java.util.Optional;

public interface PredictionRegistry {
    List<PredictionCapability> capabilities(PredictionContext context);

    Optional<PredictionCapability> describeCapability(String capabilityId, PredictionContext context);

    List<PredictionValue> evaluate(PredictionRequest request);
}
