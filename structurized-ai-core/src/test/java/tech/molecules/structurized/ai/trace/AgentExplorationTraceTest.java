package tech.molecules.structurized.ai.trace;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExplorationTraceTest {
    @Test
    void publishesOrderedEventsAndIsolatesListenerFailures() {
        AgentExplorationTrace trace = new AgentExplorationTrace(
                "trace-test", Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC));
        List<AgentExplorationEvent> received = new ArrayList<>();
        trace.subscribe(event -> { throw new IllegalStateException("observer failure"); });
        AgentExplorationSubscription subscription = trace.subscribe(received::add);

        trace.publish("call-1", AgentExplorationPhase.STARTED, "inspect_structure",
                AgentActivityType.INSPECT, "Inspect structure", null, List.of(), null, null);
        trace.publish("call-1", AgentExplorationPhase.COMPLETED, "inspect_structure",
                AgentActivityType.INSPECT, "Inspect structure", 12L, List.of(), null, null);

        assertEquals(2, received.size());
        assertEquals(1, received.get(0).sequence());
        assertEquals(2, received.get(1).sequence());
        assertEquals(received.get(0).invocationId(), received.get(1).invocationId());
        assertEquals(0, received.get(0).elapsedMillis());
        assertEquals(12L, received.get(1).durationMillis());

        subscription.close();
        trace.publish("call-2", AgentExplorationPhase.STARTED, "list_repositories",
                AgentActivityType.OTHER, "List repositories", null, List.of(), null, null);
        assertEquals(2, received.size());
    }

    @Test
    void eventAndReferenceDefensivelyCopyInput() {
        ArrayList<AgentElementReference> references = new ArrayList<>();
        references.add(new AgentElementReference(AgentElementKind.PRISM_ROW, "project", "A17",
                AgentAttentionRole.FOCUS, AgentReferenceSource.REQUEST));
        AgentExplorationTrace trace = new AgentExplorationTrace();
        AgentExplorationEvent event = trace.publish("call", AgentExplorationPhase.STARTED, "inspect",
                AgentActivityType.INSPECT, null, null, references, null, null);
        references.clear();

        assertEquals(1, event.references().size());
        assertEquals("inspect", event.label());
        assertTrue(event.elapsedMillis() >= 0);
    }
}
