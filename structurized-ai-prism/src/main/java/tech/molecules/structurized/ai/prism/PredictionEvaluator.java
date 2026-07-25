package tech.molecules.structurized.ai.prism;

import java.util.List;

@FunctionalInterface
public interface PredictionEvaluator {
    List<PredictionValue> evaluate(PredictionRequest request);
}
