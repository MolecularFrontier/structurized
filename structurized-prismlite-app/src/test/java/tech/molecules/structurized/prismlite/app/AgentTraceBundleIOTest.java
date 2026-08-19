package tech.molecules.structurized.prismlite.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.trace.AgentActivityType;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;
import tech.molecules.structurized.ai.trace.RecordedAgentTrace;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocumentMode;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTraceBundleIOTest {
    @TempDir Path temp;

    @Test
    void roundTripsTraceGraphAndOptInProposalWithoutDataset() throws Exception {
        Instant started = Instant.parse("2026-08-19T10:00:00Z");
        List<AgentExplorationEvent> events = List.of(
                new AgentExplorationEvent(1, "trace-1", 1, "call-1", started, 0,
                        AgentExplorationPhase.STARTED, "inspect", AgentActivityType.INSPECT, "Inspect", null,
                        List.of(), null, null),
                new AgentExplorationEvent(1, "trace-1", 2, "call-1", started.plusMillis(20), 20,
                        AgentExplorationPhase.COMPLETED, "inspect", AgentActivityType.INSPECT, "Inspected", 20L,
                        List.of(), null, null));
        PrismRowGraph graph = new PrismRowGraph("mmp", "MMP", "", "chemistry.mmp", "test", 1, true, "all",
                List.of(new PrismRowGraphEdge("e1", "A", "B", "A to B", Map.of("delta", 1.2))),
                Map.of("structureColumnId", "smiles"), Map.of("source", "test"));
        PrismMoleculeDocument proposal = new PrismMoleculeDocument("p1", "Proposal 1", PrismMoleculeDocumentMode.MOLECULE,
                "idcode", "coords", 1);
        AgentTraceBundle bundle = new AgentTraceBundle(
                new RecordedAgentTrace(1, "trace-1", started, events, false), "sha256-value", List.of(graph), List.of(proposal));
        Path file = temp.resolve("session.agenttrace.zip");

        AgentTraceBundleIO.write(file, bundle);
        AgentTraceBundle restored = AgentTraceBundleIO.read(file);

        assertEquals("trace-1", restored.trace().traceId());
        assertEquals("sha256-value", restored.datasetFingerprint());
        assertEquals("B", restored.graphs().getFirst().edges().getFirst().targetRowId());
        assertEquals("idcode", restored.proposals().getFirst().idcode());
        assertFalse(restored.trace().truncatedFinalLine());
    }
}
