package tech.molecules.structurized.prismlite.app;

import tech.molecules.structurized.ai.trace.RecordedAgentTrace;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismRowGraph;

import java.util.List;

/** Optional presentation sidecar around the privacy-minimal activity trace. */
record AgentTraceBundle(
        RecordedAgentTrace trace,
        String datasetFingerprint,
        List<PrismRowGraph> graphs,
        List<PrismMoleculeDocument> proposals
) {
    AgentTraceBundle {
        if (trace == null) throw new IllegalArgumentException("trace must not be null");
        datasetFingerprint = datasetFingerprint == null ? "" : datasetFingerprint.trim();
        graphs = graphs == null ? List.of() : List.copyOf(graphs);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    static AgentTraceBundle raw(RecordedAgentTrace trace) {
        return new AgentTraceBundle(trace, "", List.of(), List.of());
    }
}
