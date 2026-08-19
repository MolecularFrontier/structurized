package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import tech.molecules.chemflow.canvas.CanvasActivityRole;
import tech.molecules.chemflow.canvas.CanvasActivityTarget;
import tech.molecules.structurized.ai.trace.AgentActivityType;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;
import tech.molecules.structurized.ai.trace.AgentReferenceSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTraceTimelineTest {
    @Test
    void compressesIdleGapsAndKeepsFocusActiveUntilTerminalEvent() {
        AgentElementReference focus = new AgentElementReference(AgentElementKind.PRISM_ROW, "source-session", "A17",
                AgentAttentionRole.FOCUS, AgentReferenceSource.REQUEST);
        AgentTraceTimeline timeline = new AgentTraceTimeline(List.of(
                event(1, 0, AgentExplorationPhase.STARTED, List.of(focus)),
                event(2, 20_000, AgentExplorationPhase.COMPLETED, List.of())
        ), true);

        assertEquals(AgentTraceTimeline.PRESENTATION_GAP_CAP_MS, timeline.eventTime(1));
        var running = timeline.snapshot(500, ignored -> "viewer-session");
        var state = running.activities().get(new CanvasActivityTarget("prism_row", "viewer-session", "A17"));
        assertEquals(CanvasActivityRole.FOCUS, state.role());
        assertTrue(state.active());

        var after = timeline.snapshot(1_300, ignored -> "viewer-session");
        assertFalse(after.activities().values().iterator().next().active());
    }

    @Test
    void proposedElementsPersistWhileReturnedElementsFade() {
        AgentElementReference returned = new AgentElementReference(AgentElementKind.PRISM_ROW, "s", "A18",
                AgentAttentionRole.RETURNED, AgentReferenceSource.RESULT);
        AgentElementReference proposed = new AgentElementReference(AgentElementKind.PRISM_MOLECULE_DOCUMENT, "s", "proposal-1",
                AgentAttentionRole.PROPOSED, AgentReferenceSource.RESULT);
        AgentTraceTimeline timeline = new AgentTraceTimeline(List.of(
                event(1, 0, AgentExplorationPhase.STARTED, List.of()),
                event(2, 100, AgentExplorationPhase.COMPLETED, List.of(returned, proposed))
        ), false);

        var late = timeline.snapshot(10_000, value -> value);
        assertFalse(late.activities().containsKey(new CanvasActivityTarget("prism_row", "s", "A18")));
        assertTrue(late.activities().containsKey(new CanvasActivityTarget("prism_molecule_document", "s", "proposal-1")));
    }

    private static AgentExplorationEvent event(long sequence, long elapsed, AgentExplorationPhase phase,
                                                List<AgentElementReference> references) {
        return new AgentExplorationEvent(1, "trace", sequence, "call", Instant.EPOCH.plusMillis(elapsed), elapsed,
                phase, "inspect_compounds", AgentActivityType.INSPECT, "Inspect compounds",
                phase == AgentExplorationPhase.STARTED ? null : elapsed, references, null, null);
    }
}
