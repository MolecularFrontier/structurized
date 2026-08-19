package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tech.molecules.structurized.ai.trace.AgentActivityType;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationPhase;
import tech.molecules.structurized.ai.trace.AgentReferenceSource;
import tech.molecules.structurized.ai.trace.RecordedAgentTrace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict reader for versioned Structurized exploration JSONL recordings. */
public final class AgentExplorationTraceReader {
    private final ObjectMapper mapper;

    public AgentExplorationTraceReader() {
        this(new ObjectMapper());
    }

    AgentExplorationTraceReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RecordedAgentTrace read(Path path) throws IOException {
        return readLines(Files.readAllLines(path));
    }

    public RecordedAgentTrace readLines(List<String> lines) throws IOException {
        if (lines == null || lines.isEmpty()) throw new IOException("Trace is empty.");
        JsonNode header = parse(lines.getFirst(), 1);
        if (!"trace_header".equals(text(header, "record_type"))) throw new IOException("First record is not a trace header.");
        if (!"structurized-agent-exploration".equals(text(header, "format"))) throw new IOException("Unsupported trace format.");
        int version = integer(header, "schema_version");
        if (version != 1) throw new IOException("Unsupported trace schema version: " + version);
        String traceId = requiredText(header, "trace_id");
        Instant startedAt = instant(header, "started_at");
        ArrayList<AgentExplorationEvent> events = new ArrayList<>();
        long previousSequence = 0;
        boolean truncated = false;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line == null || line.isBlank()) continue;
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception exception) {
                if (index == lines.size() - 1) {
                    truncated = true;
                    break;
                }
                throw new IOException("Invalid JSON at trace line " + (index + 1), exception);
            }
            if (!"event".equals(text(node, "record_type"))) throw new IOException("Unexpected record at trace line " + (index + 1));
            AgentExplorationEvent event = event(node);
            if (!traceId.equals(event.traceId())) throw new IOException("Mixed trace IDs at line " + (index + 1));
            if (event.sequence() <= previousSequence) throw new IOException("Trace sequence is not strictly increasing at line " + (index + 1));
            previousSequence = event.sequence();
            events.add(event);
        }
        validateLifecycle(events);
        return new RecordedAgentTrace(version, traceId, startedAt, events, truncated);
    }

    private AgentExplorationEvent event(JsonNode node) throws IOException {
        ArrayList<AgentElementReference> refs = new ArrayList<>();
        JsonNode references = node.get("references");
        if (references != null && references.isArray()) {
            for (JsonNode ref : references) {
                refs.add(new AgentElementReference(
                        value(AgentElementKind.class, requiredText(ref, "kind")),
                        requiredText(ref, "context_id"), requiredText(ref, "element_id"),
                        value(AgentAttentionRole.class, requiredText(ref, "role")),
                        value(AgentReferenceSource.class, requiredText(ref, "source"))));
            }
        }
        return new AgentExplorationEvent(
                integer(node, "schema_version"), requiredText(node, "trace_id"), longValue(node, "sequence"),
                requiredText(node, "invocation_id"), instant(node, "occurred_at"), longValue(node, "elapsed_ms"),
                value(AgentExplorationPhase.class, requiredText(node, "phase")), requiredText(node, "tool_name"),
                value(AgentActivityType.class, requiredText(node, "activity_type")), requiredText(node, "label"),
                nullableLong(node, "duration_ms"), refs, text(node, "error_code"), text(node, "error_message"));
    }

    private static void validateLifecycle(List<AgentExplorationEvent> events) throws IOException {
        Set<String> active = new HashSet<>();
        Set<String> terminal = new HashSet<>();
        for (AgentExplorationEvent event : events) {
            if (event.phase() == AgentExplorationPhase.STARTED) {
                if (!active.add(event.invocationId()) || terminal.contains(event.invocationId())) {
                    throw new IOException("Duplicate STARTED event for invocation " + event.invocationId());
                }
            } else {
                if (!active.remove(event.invocationId()) || !terminal.add(event.invocationId())) {
                    throw new IOException("Terminal event without one STARTED event for invocation " + event.invocationId());
                }
            }
        }
        // Active invocations are allowed: a process may stop while a tool is running.
    }

    private JsonNode parse(String line, int lineNumber) throws IOException {
        try {
            return mapper.readTree(line);
        } catch (Exception exception) {
            throw new IOException("Invalid JSON at trace line " + lineNumber, exception);
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IOException("Missing trace field: " + field);
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static int integer(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) throw new IOException("Missing integer trace field: " + field);
        return value.asInt();
    }

    private static long longValue(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) throw new IOException("Missing integer trace field: " + field);
        return value.asLong();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.canConvertToLong() ? null : value.asLong();
    }

    private static Instant instant(JsonNode node, String field) throws IOException {
        try {
            return Instant.parse(requiredText(node, field));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid timestamp in trace field: " + field, exception);
        }
    }

    private static <E extends Enum<E>> E value(Class<E> type, String value) throws IOException {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IOException("Unknown " + type.getSimpleName() + " value: " + value, exception);
        }
    }
}
