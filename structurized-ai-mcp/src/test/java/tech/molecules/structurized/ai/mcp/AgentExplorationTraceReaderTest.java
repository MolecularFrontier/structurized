package tech.molecules.structurized.ai.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExplorationTraceReaderTest {
    private static final String HEADER = """
            {"record_type":"trace_header","format":"structurized-agent-exploration","schema_version":1,"trace_id":"trace-1","started_at":"2026-08-19T10:00:00Z"}""";
    private static final String START = """
            {"record_type":"event","schema_version":1,"trace_id":"trace-1","sequence":1,"invocation_id":"call-1","occurred_at":"2026-08-19T10:00:01Z","elapsed_ms":1000,"phase":"started","tool_name":"inspect_compounds","activity_type":"inspect","label":"Inspecting compounds","references":[{"kind":"prism_row","context_id":"workspace","element_id":"A17","role":"focus","source":"request"}]}""";
    private static final String END = """
            {"record_type":"event","schema_version":1,"trace_id":"trace-1","sequence":2,"invocation_id":"call-1","occurred_at":"2026-08-19T10:00:01.100Z","elapsed_ms":1100,"phase":"completed","tool_name":"inspect_compounds","activity_type":"inspect","label":"Inspected compounds","duration_ms":100,"references":[]}""";

    @Test
    void readsVersionedLifecycle() throws Exception {
        var trace = new AgentExplorationTraceReader().readLines(List.of(HEADER, START, END));
        assertEquals("trace-1", trace.traceId());
        assertEquals(2, trace.events().size());
        assertEquals("A17", trace.events().getFirst().references().getFirst().elementId());
    }

    @Test
    void toleratesOnlyAMalformedFinalLine() throws Exception {
        var trace = new AgentExplorationTraceReader().readLines(List.of(HEADER, START, "{unfinished"));
        assertTrue(trace.truncatedFinalLine());
        assertEquals(1, trace.events().size());
        assertThrows(IOException.class, () -> new AgentExplorationTraceReader()
                .readLines(List.of(HEADER, "{broken", START)));
    }
}
