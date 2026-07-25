package tech.molecules.structurized.ai.prism;

import java.util.List;
import java.util.Map;

public record PredictionValue(
        String inputId,
        String endpointId,
        Object value,
        Double uncertainty,
        Double applicability,
        PredictionStatus status,
        List<String> warnings,
        Map<String, Object> details
) {
    public PredictionValue {
        if (inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("inputId must not be blank");
        }
        if (endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException("endpointId must not be blank");
        }
        status = status == null ? PredictionStatus.SUCCESS : status;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
