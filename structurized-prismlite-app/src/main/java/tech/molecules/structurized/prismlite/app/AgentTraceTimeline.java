package tech.molecules.structurized.prismlite.app;

import tech.molecules.chemflow.canvas.CanvasActivityRole;
import tech.molecules.chemflow.canvas.CanvasActivityState;
import tech.molecules.chemflow.canvas.CanvasActivityTarget;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

final class AgentTraceTimeline {
    static final long PRESENTATION_GAP_CAP_MS = 1_200;

    private final List<ScheduledEvent> scheduled;
    private final Map<String, Long> terminalTimeByInvocation;
    private final long durationMillis;

    AgentTraceTimeline(List<AgentExplorationEvent> events, boolean presentationTiming) {
        ArrayList<AgentExplorationEvent> ordered = new ArrayList<>(events == null ? List.of() : events);
        ordered.sort(Comparator.comparingLong(AgentExplorationEvent::sequence));
        ArrayList<ScheduledEvent> schedule = new ArrayList<>();
        long previousSource = 0;
        long previousScheduled = 0;
        for (AgentExplorationEvent event : ordered) {
            long source = Math.max(0, event.elapsedMillis());
            long scheduledTime;
            if (schedule.isEmpty()) {
                scheduledTime = presentationTiming ? 0 : source;
            } else if (presentationTiming) {
                scheduledTime = previousScheduled + Math.min(PRESENTATION_GAP_CAP_MS, Math.max(0, source - previousSource));
            } else {
                scheduledTime = source;
            }
            schedule.add(new ScheduledEvent(event, scheduledTime));
            previousSource = source;
            previousScheduled = scheduledTime;
        }
        scheduled = List.copyOf(schedule);
        HashMap<String, Long> terminals = new HashMap<>();
        for (ScheduledEvent item : scheduled) {
            if (item.event().phase() != AgentExplorationPhase.STARTED) {
                terminals.put(item.event().invocationId(), item.timeMillis());
            }
        }
        terminalTimeByInvocation = Map.copyOf(terminals);
        durationMillis = scheduled.isEmpty() ? 0 : scheduled.getLast().timeMillis() + 1_800;
    }

    long durationMillis() {
        return durationMillis;
    }

    int eventCount() {
        return scheduled.size();
    }

    long eventTime(int index) {
        return scheduled.get(Math.max(0, Math.min(index, scheduled.size() - 1))).timeMillis();
    }

    Snapshot snapshot(long playheadMillis, UnaryOperator<String> prismContextMapper) {
        long playhead = Math.max(0, playheadMillis);
        LinkedHashMap<CanvasActivityTarget, RankedState> states = new LinkedHashMap<>();
        LinkedHashMap<String, InvocationBuilder> invocations = new LinkedHashMap<>();
        String focusedRow = null;
        int cursor = -1;
        for (int index = 0; index < scheduled.size(); index++) {
            ScheduledEvent item = scheduled.get(index);
            if (item.timeMillis() > playhead) break;
            cursor = index;
            AgentExplorationEvent event = item.event();
            InvocationBuilder invocation = invocations.computeIfAbsent(event.invocationId(), ignored ->
                    new InvocationBuilder(event.invocationId(), event.toolName(), event.label(), item.timeMillis()));
            invocation.accept(event, item.timeMillis());
            for (AgentElementReference reference : event.references()) {
                CanvasActivityTarget target = target(reference, prismContextMapper);
                long end = event.phase() == AgentExplorationPhase.STARTED
                        ? terminalTimeByInvocation.getOrDefault(event.invocationId(), item.timeMillis() + 1_200)
                        : item.timeMillis();
                long age = Math.max(0, playhead - end);
                long fade = fadeMillis(reference.role());
                boolean active = event.phase() == AgentExplorationPhase.STARTED && playhead <= end;
                double intensity = reference.role() == AgentAttentionRole.PROPOSED
                        ? 1.0
                        : active ? 1.0 : Math.max(0.0, 1.0 - age / (double) fade);
                if (intensity <= 0.0) continue;
                CanvasActivityState state = new CanvasActivityState(role(reference.role()), intensity, active);
                int rank = rank(reference.role(), active);
                RankedState previous = states.get(target);
                if (previous == null || rank > previous.rank() || rank == previous.rank() && intensity > previous.state().intensity()) {
                    states.put(target, new RankedState(state, rank));
                }
                if (reference.kind() == AgentElementKind.PRISM_ROW
                        && reference.role() == AgentAttentionRole.FOCUS) {
                    focusedRow = reference.elementId();
                }
            }
        }
        List<InvocationView> log = invocations.values().stream()
                .map(InvocationBuilder::view)
                .sorted(Comparator.comparingLong(InvocationView::startedMillis).reversed())
                .limit(10)
                .toList();
        LinkedHashMap<CanvasActivityTarget, CanvasActivityState> activities = new LinkedHashMap<>();
        states.forEach((target, state) -> activities.put(target, state.state()));
        return new Snapshot(Map.copyOf(activities), log, focusedRow, cursor);
    }

    private static CanvasActivityTarget target(AgentElementReference reference, UnaryOperator<String> mapper) {
        String namespace = switch (reference.kind()) {
            case PRISM_ROW -> "prism_row";
            case PRISM_SUBJECT -> "prism_subject";
            case REPOSITORY_STRUCTURE -> "repository_structure";
            case PRISM_MOLECULE_DOCUMENT -> "prism_molecule_document";
        };
        String context = reference.kind() == AgentElementKind.PRISM_ROW
                || reference.kind() == AgentElementKind.PRISM_SUBJECT
                || reference.kind() == AgentElementKind.PRISM_MOLECULE_DOCUMENT
                ? mapper.apply(reference.contextId()) : reference.contextId();
        return new CanvasActivityTarget(namespace, context, reference.elementId());
    }

    private static long fadeMillis(AgentAttentionRole role) {
        return switch (role) {
            case FOCUS -> 3_500;
            case TOUCHED -> 3_000;
            case RETURNED -> 1_800;
            case PROPOSED -> Long.MAX_VALUE;
        };
    }

    private static CanvasActivityRole role(AgentAttentionRole role) {
        return CanvasActivityRole.valueOf(role.name());
    }

    private static int rank(AgentAttentionRole role, boolean active) {
        return (active ? 10 : 0) + switch (role) {
            case RETURNED -> 0;
            case TOUCHED -> 1;
            case FOCUS -> 2;
            case PROPOSED -> 3;
        };
    }

    record Snapshot(Map<CanvasActivityTarget, CanvasActivityState> activities,
                    List<InvocationView> log,
                    String focusedRowId,
                    int cursor) {}

    record InvocationView(String invocationId,
                          String toolName,
                          String label,
                          AgentExplorationPhase phase,
                          long startedMillis,
                          List<AgentElementReference> references,
                          String errorCode) {}

    private record ScheduledEvent(AgentExplorationEvent event, long timeMillis) {}
    private record RankedState(CanvasActivityState state, int rank) {}

    private static final class InvocationBuilder {
        private final String id;
        private final String tool;
        private final String label;
        private final long started;
        private AgentExplorationPhase phase = AgentExplorationPhase.STARTED;
        private final ArrayList<AgentElementReference> references = new ArrayList<>();
        private String errorCode;

        private InvocationBuilder(String id, String tool, String label, long started) {
            this.id = id;
            this.tool = tool;
            this.label = label;
            this.started = started;
        }

        private void accept(AgentExplorationEvent event, long ignored) {
            phase = event.phase();
            references.addAll(event.references());
            if (event.errorCode() != null) errorCode = event.errorCode();
        }

        private InvocationView view() {
            return new InvocationView(id, tool, label, phase, started, List.copyOf(references), errorCode);
        }
    }
}
