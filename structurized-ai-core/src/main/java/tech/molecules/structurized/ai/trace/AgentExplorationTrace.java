package tech.molecules.structurized.ai.trace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Session-scoped, best-effort publication of observable agent tool activity. */
public final class AgentExplorationTrace {
    public static final int SCHEMA_VERSION = 1;

    private final String traceId;
    private final Clock clock;
    private final Instant startedAt;
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<AgentExplorationEvent>> listeners = new CopyOnWriteArrayList<>();

    public AgentExplorationTrace() {
        this(UUID.randomUUID().toString(), Clock.systemUTC());
    }

    public AgentExplorationTrace(String traceId, Clock clock) {
        if (traceId == null || traceId.isBlank()) throw new IllegalArgumentException("traceId must not be blank");
        this.traceId = traceId.trim();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.startedAt = clock.instant();
    }

    public String traceId() {
        return traceId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public AgentExplorationSubscription subscribe(Consumer<AgentExplorationEvent> listener) {
        Consumer<AgentExplorationEvent> registered = Objects.requireNonNull(listener, "listener");
        listeners.add(registered);
        return () -> listeners.remove(registered);
    }

    public synchronized AgentExplorationEvent publish(String invocationId,
                                         AgentExplorationPhase phase,
                                         String toolName,
                                         AgentActivityType activityType,
                                         String label,
                                         Long durationMillis,
                                         List<AgentElementReference> references,
                                         String errorCode,
                                         String errorMessage) {
        Instant now = clock.instant();
        AgentExplorationEvent event = new AgentExplorationEvent(
                SCHEMA_VERSION, traceId, sequence.incrementAndGet(), invocationId, now,
                Math.max(0, Duration.between(startedAt, now).toMillis()), phase, toolName,
                activityType, label, durationMillis, references, errorCode, errorMessage);
        for (Consumer<AgentExplorationEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException ignored) {
                // Observability must never alter the chemistry operation being observed.
            }
        }
        return event;
    }
}
