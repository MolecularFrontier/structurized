package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.prism.prediction.PredictionCapability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryPredictionRegistry implements PredictionRegistry {
    private final Map<String, PredictionCapability> capabilities = new LinkedHashMap<>();
    private final Map<String, PredictionEvaluator> evaluators = new LinkedHashMap<>();

    public static InMemoryPredictionRegistry referenceRegistry() {
        InMemoryPredictionRegistry registry = new InMemoryPredictionRegistry();
        PredictionCapability potency = new PredictionCapability(
                "reference/pic50",
                "pIC50",
                "pIC50.predicted",
                "Reference pIC50 predictor",
                "reference",
                "reference/pic50",
                "1",
                "available",
                100,
                "smiles",
                "smiles",
                Map.of(
                        "description", "Deterministic local reference workflow for tests and demos.",
                        "apyCompatibleOutput", "numericPrediction, variance, confidence",
                        "outputType", "numeric",
                        "unit", "pIC50"
                )
        );
        registry.register(potency, InMemoryPredictionRegistry::referenceNumericPrediction);
        return registry;
    }

    public synchronized void register(PredictionCapability capability, PredictionEvaluator evaluator) {
        if (capability == null) {
            throw new IllegalArgumentException("capability must not be null");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("evaluator must not be null");
        }
        capabilities.put(capability.capabilityId(), capability);
        evaluators.put(capability.capabilityId(), evaluator);
    }

    @Override
    public synchronized List<PredictionCapability> capabilities(PredictionContext context) {
        return capabilities.values().stream()
                .sorted(Comparator.comparingInt(PredictionCapability::priority).reversed()
                        .thenComparing(PredictionCapability::capabilityId))
                .toList();
    }

    @Override
    public synchronized Optional<PredictionCapability> describeCapability(String capabilityId, PredictionContext context) {
        if (capabilityId == null || capabilityId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(capabilities.get(capabilityId.trim()));
    }

    @Override
    public synchronized List<PredictionValue> evaluate(PredictionRequest request) {
        PredictionEvaluator evaluator = evaluators.get(request.capability().capabilityId());
        if (evaluator == null) {
            throw new ChemOperationException(
                    "prediction_capability_not_executable",
                    "Prediction capability " + request.capability().capabilityId() + " is not executable in this registry."
            );
        }
        return evaluator.evaluate(request);
    }

    private static List<PredictionValue> referenceNumericPrediction(PredictionRequest request) {
        ArrayList<PredictionValue> values = new ArrayList<>();
        String outputEndpointId = request.capability().predictedEndpointId();
        for (PredictionInput input : request.inputs()) {
            if (input.structure() == null || input.structure().isBlank()) {
                values.add(new PredictionValue(
                        input.inputId(),
                        outputEndpointId,
                        null,
                        null,
                        null,
                        PredictionStatus.MISSING_FEATURES,
                        List.of("No structure was supplied for prediction."),
                        Map.of()
                ));
                continue;
            }
            double score = 5.0 + Math.floorMod(input.structure().hashCode(), 350) / 100.0;
            double uncertainty = 0.15 + Math.floorMod(input.inputId().hashCode(), 25) / 100.0;
            double applicability = Math.min(0.98, 0.55 + Math.min(input.structure().length(), 20) / 50.0);
            PredictionStatus status = applicability < 0.6 ? PredictionStatus.OUT_OF_DOMAIN : PredictionStatus.SUCCESS;
            values.add(new PredictionValue(
                    input.inputId(),
                    outputEndpointId,
                    score,
                    uncertainty,
                    applicability,
                    status,
                    status == PredictionStatus.OUT_OF_DOMAIN
                            ? List.of("Reference workflow marks this input as low applicability.")
                            : List.of(),
                    Map.of(
                            "numericPrediction", score,
                            "variance", uncertainty * uncertainty,
                            "confidence", applicability
                    )
            ));
        }
        return List.copyOf(values);
    }
}
