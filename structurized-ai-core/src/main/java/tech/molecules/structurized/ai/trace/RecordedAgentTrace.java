package tech.molecules.structurized.ai.trace;

import java.time.Instant;
import java.util.List;

public record RecordedAgentTrace(
        int schemaVersion,
        String traceId,
        Instant startedAt,
        List<AgentExplorationEvent> events,
        boolean truncatedFinalLine
) {
    public RecordedAgentTrace {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId must not be blank");
        if (startedAt == null) throw new IllegalArgumentException("startedAt must not be null");
        events = events == null ? List.of() : List.copyOf(events);
    }
}
