package tech.molecules.structurized.ai.trace;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentExplorationEvent(
        int schemaVersion,
        String traceId,
        long sequence,
        String invocationId,
        Instant occurredAt,
        long elapsedMillis,
        AgentExplorationPhase phase,
        String toolName,
        AgentActivityType activityType,
        String label,
        Long durationMillis,
        List<AgentElementReference> references,
        String errorCode,
        String errorMessage
) {
    public AgentExplorationEvent {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        traceId = requireText(traceId, "traceId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (elapsedMillis < 0) throw new IllegalArgumentException("elapsedMillis must not be negative");
        Objects.requireNonNull(phase, "phase");
        toolName = requireText(toolName, "toolName");
        Objects.requireNonNull(activityType, "activityType");
        label = label == null || label.isBlank() ? toolName : label.trim();
        if (durationMillis != null && durationMillis < 0) throw new IllegalArgumentException("durationMillis must not be negative");
        references = references == null ? List.of() : List.copyOf(references);
        errorCode = blankToNull(errorCode);
        errorMessage = blankToNull(errorMessage);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
