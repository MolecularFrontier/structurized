package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.AgentExplorationSubscription;
import tech.molecules.structurized.ai.trace.AgentExplorationTrace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Crash-tolerant JSONL persistence for one exploration trace. */
public final class JsonlAgentExplorationRecorder implements AutoCloseable {
    private final ObjectMapper mapper;
    private final BufferedWriter writer;
    private final AgentExplorationSubscription subscription;
    private IOException failure;
    private boolean closed;

    public static JsonlAgentExplorationRecorder open(Path path, AgentExplorationTrace trace) throws IOException {
        return new JsonlAgentExplorationRecorder(path, trace, new ObjectMapper());
    }

    JsonlAgentExplorationRecorder(Path path, AgentExplorationTrace trace, ObjectMapper mapper) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(trace, "trace");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        this.writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeHeader(trace);
        this.subscription = trace.subscribe(this::record);
    }

    public synchronized Optional<IOException> failure() {
        return Optional.ofNullable(failure);
    }

    private synchronized void record(AgentExplorationEvent event) {
        if (closed || failure != null) return;
        try {
            writeLine(eventNode(event));
        } catch (IOException e) {
            failure = e;
        }
    }

    private void writeHeader(AgentExplorationTrace trace) throws IOException {
        ObjectNode header = mapper.createObjectNode();
        header.put("record_type", "trace_header");
        header.put("format", "structurized-agent-exploration");
        header.put("schema_version", AgentExplorationTrace.SCHEMA_VERSION);
        header.put("trace_id", trace.traceId());
        header.put("started_at", trace.startedAt().toString());
        writeLine(header);
    }

    private ObjectNode eventNode(AgentExplorationEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("record_type", "event");
        node.put("schema_version", event.schemaVersion());
        node.put("trace_id", event.traceId());
        node.put("sequence", event.sequence());
        node.put("invocation_id", event.invocationId());
        node.put("occurred_at", event.occurredAt().toString());
        node.put("elapsed_ms", event.elapsedMillis());
        node.put("phase", event.phase().name().toLowerCase(Locale.ROOT));
        node.put("tool_name", event.toolName());
        node.put("activity_type", event.activityType().name().toLowerCase(Locale.ROOT));
        node.put("label", event.label());
        if (event.durationMillis() != null) node.put("duration_ms", event.durationMillis());
        ArrayNode references = node.putArray("references");
        for (AgentElementReference reference : event.references()) {
            ObjectNode ref = references.addObject();
            ref.put("kind", reference.kind().name().toLowerCase(Locale.ROOT));
            ref.put("context_id", reference.contextId());
            ref.put("element_id", reference.elementId());
            ref.put("role", reference.role().name().toLowerCase(Locale.ROOT));
            ref.put("source", reference.source().name().toLowerCase(Locale.ROOT));
        }
        if (event.errorCode() != null) node.put("error_code", event.errorCode());
        if (event.errorMessage() != null) node.put("error_message", event.errorMessage());
        return node;
    }

    private void writeLine(ObjectNode node) throws IOException {
        writer.write(mapper.writeValueAsString(node));
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        subscription.close();
        writer.close();
    }
}
