package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;
import tech.molecules.structurized.ai.trace.AgentExplorationTrace;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExplorationTraceIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void recordsCorrelatedFocusedInspectionWithoutChemicalPayloads() throws Exception {
        AgentExplorationTrace trace = new AgentExplorationTrace(
                "trace-inspect", Clock.fixed(Instant.parse("2026-08-19T12:04:00Z"), ZoneOffset.UTC));
        List<AgentExplorationEvent> events = new ArrayList<>();
        trace.subscribe(events::add);
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault(trace);
        call(handler, 1, "register_structure", "{\"smiles\":\"CCO\",\"structure_id\":\"ethanol\"}");
        events.clear();

        call(handler, 2, "inspect_structure",
                "{\"repository_id\":\"session\",\"structure_id\":\"ethanol\"}");

        assertEquals(2, events.size());
        AgentExplorationEvent started = events.get(0);
        AgentExplorationEvent completed = events.get(1);
        assertEquals(AgentExplorationPhase.STARTED, started.phase());
        assertEquals(AgentExplorationPhase.COMPLETED, completed.phase());
        assertEquals(started.invocationId(), completed.invocationId());
        assertTrue(started.references().stream().anyMatch(ref ->
                ref.kind() == AgentElementKind.REPOSITORY_STRUCTURE
                        && ref.role() == AgentAttentionRole.FOCUS
                        && ref.contextId().equals("session")
                        && ref.elementId().equals("ethanol")));
    }

    @Test
    void recordsFailuresAndUnclassifiedCalls() throws Exception {
        AgentExplorationTrace trace = new AgentExplorationTrace();
        List<AgentExplorationEvent> events = new ArrayList<>();
        trace.subscribe(events::add);
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault(trace);

        call(handler, 1, "list_repositories", "{}");
        call(handler, 2, "not_a_tool", "{}");

        assertEquals(4, events.size());
        assertEquals(AgentExplorationPhase.COMPLETED, events.get(1).phase());
        assertTrue(events.get(1).references().isEmpty());
        assertEquals(AgentExplorationPhase.FAILED, events.get(3).phase());
        assertEquals("tool_not_found", events.get(3).errorCode());
        assertTrue(events.get(3).references().isEmpty());
    }

    @Test
    void jsonlRecorderWritesVersionedSafeRecordsAndProtectsExistingFiles() throws Exception {
        Path file = tempDir.resolve("session.jsonl");
        AgentExplorationTrace trace = new AgentExplorationTrace(
                "trace-jsonl", Clock.fixed(Instant.parse("2026-08-19T12:04:00Z"), ZoneOffset.UTC));
        try (JsonlAgentExplorationRecorder recorder = JsonlAgentExplorationRecorder.open(file, trace)) {
            McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault(trace);
            call(handler, 1, "register_structure",
                    "{\"smiles\":\"CCO\",\"structure_id\":\"candidate-1\"}");
            assertFalse(recorder.failure().isPresent());
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size());
        JsonNode header = mapper.readTree(lines.get(0));
        JsonNode started = mapper.readTree(lines.get(1));
        JsonNode terminal = mapper.readTree(lines.get(2));
        assertEquals("trace_header", header.get("record_type").asText());
        assertEquals(1, header.get("schema_version").asInt());
        assertEquals("started", started.get("phase").asText());
        assertEquals("completed", terminal.get("phase").asText());
        assertEquals(started.get("invocation_id").asText(), terminal.get("invocation_id").asText());
        assertFalse(String.join("\n", lines).contains("\"smiles\""));

        assertThrows(FileAlreadyExistsException.class,
                () -> JsonlAgentExplorationRecorder.open(file, new AgentExplorationTrace()));
    }

    private JsonNode call(McpJsonRpcHandler handler, int id, String tool, String arguments) throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
        return mapper.readTree(handler.handleJson(request));
    }
}
