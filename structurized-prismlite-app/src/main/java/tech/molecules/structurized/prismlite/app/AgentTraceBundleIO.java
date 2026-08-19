package tech.molecules.structurized.prismlite.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.mcp.AgentExplorationTraceReader;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentExplorationEvent;
import tech.molecules.structurized.ai.trace.RecordedAgentTrace;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocumentMode;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Codec for raw JSONL traces and opt-in, dataset-external presentation bundles. */
final class AgentTraceBundleIO {
    static final String BUNDLE_FORMAT = "structurized-agent-trace-bundle";
    static final int BUNDLE_VERSION = 1;
    private static final String MANIFEST = "manifest.json";
    private static final String TRACE = "trace.jsonl";
    private static final String GRAPHS = "graphs.json";
    private static final String PROPOSALS = "proposals.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTraceBundleIO() {
    }

    static AgentTraceBundle read(Path path) throws IOException {
        if (!looksLikeZip(path)) return AgentTraceBundle.raw(new AgentExplorationTraceReader().read(path));
        Map<String, byte[]> entries = unzip(path);
        JsonNode manifest = MAPPER.readTree(required(entries, MANIFEST));
        if (!BUNDLE_FORMAT.equals(text(manifest, "format"))) throw new IOException("Unsupported trace bundle format.");
        if (manifest.path("version").asInt(-1) != BUNDLE_VERSION) {
            throw new IOException("Unsupported trace bundle version: " + manifest.path("version").asText());
        }
        List<String> lines = new String(required(entries, TRACE), StandardCharsets.UTF_8).lines().toList();
        RecordedAgentTrace trace = new AgentExplorationTraceReader().readLines(lines);
        if (!trace.traceId().equals(text(manifest, "trace_id"))) throw new IOException("Bundle trace ID mismatch.");
        return new AgentTraceBundle(trace, text(manifest, "dataset_fingerprint"),
                readGraphs(entries.get(GRAPHS)), readProposals(entries.get(PROPOSALS)));
    }

    static void write(Path path, AgentTraceBundle bundle) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(absolute,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            ObjectNode manifest = MAPPER.createObjectNode();
            manifest.put("format", BUNDLE_FORMAT);
            manifest.put("version", BUNDLE_VERSION);
            manifest.put("trace_id", bundle.trace().traceId());
            manifest.put("dataset_fingerprint", bundle.datasetFingerprint());
            manifest.put("dataset_embedded", false);
            manifest.put("graph_count", bundle.graphs().size());
            manifest.put("proposal_count", bundle.proposals().size());
            put(zip, MANIFEST, MAPPER.writeValueAsBytes(manifest));
            put(zip, TRACE, traceJsonl(bundle.trace()).getBytes(StandardCharsets.UTF_8));
            put(zip, GRAPHS, MAPPER.writeValueAsBytes(graphsNode(bundle.graphs())));
            put(zip, PROPOSALS, MAPPER.writeValueAsBytes(proposalsNode(bundle.proposals())));
        }
    }

    private static String traceJsonl(RecordedAgentTrace trace) throws IOException {
        StringBuilder lines = new StringBuilder();
        ObjectNode header = MAPPER.createObjectNode();
        header.put("record_type", "trace_header");
        header.put("format", "structurized-agent-exploration");
        header.put("schema_version", trace.schemaVersion());
        header.put("trace_id", trace.traceId());
        header.put("started_at", trace.startedAt().toString());
        lines.append(MAPPER.writeValueAsString(header)).append('\n');
        for (AgentExplorationEvent event : trace.events()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("record_type", "event");
            node.put("schema_version", event.schemaVersion());
            node.put("trace_id", event.traceId());
            node.put("sequence", event.sequence());
            node.put("invocation_id", event.invocationId());
            node.put("occurred_at", event.occurredAt().toString());
            node.put("elapsed_ms", event.elapsedMillis());
            node.put("phase", lower(event.phase()));
            node.put("tool_name", event.toolName());
            node.put("activity_type", lower(event.activityType()));
            node.put("label", event.label());
            if (event.durationMillis() != null) node.put("duration_ms", event.durationMillis());
            ArrayNode refs = node.putArray("references");
            for (AgentElementReference reference : event.references()) {
                ObjectNode ref = refs.addObject();
                ref.put("kind", lower(reference.kind()));
                ref.put("context_id", reference.contextId());
                ref.put("element_id", reference.elementId());
                ref.put("role", lower(reference.role()));
                ref.put("source", lower(reference.source()));
            }
            if (event.errorCode() != null) node.put("error_code", event.errorCode());
            if (event.errorMessage() != null) node.put("error_message", event.errorMessage());
            lines.append(MAPPER.writeValueAsString(node)).append('\n');
        }
        return lines.toString();
    }

    private static ArrayNode graphsNode(List<PrismRowGraph> graphs) {
        ArrayNode nodes = MAPPER.createArrayNode();
        for (PrismRowGraph graph : graphs) {
            ObjectNode node = nodes.addObject();
            node.put("id", graph.id());
            node.put("title", graph.title());
            node.put("description", graph.description());
            node.put("graph_type", graph.graphType());
            if (graph.pluginId() != null) node.put("plugin_id", graph.pluginId());
            node.put("schema_version", graph.schemaVersion());
            node.put("directed", graph.directed());
            if (graph.sourceRowSetId() != null) node.put("source_row_set_id", graph.sourceRowSetId());
            node.set("metadata", MAPPER.valueToTree(graph.metadata()));
            node.set("provenance", MAPPER.valueToTree(graph.provenance()));
            ArrayNode edges = node.putArray("edges");
            for (PrismRowGraphEdge edge : graph.edges()) {
                ObjectNode edgeNode = edges.addObject();
                edgeNode.put("id", edge.id());
                edgeNode.put("source", edge.sourceRowId());
                edgeNode.put("target", edge.targetRowId());
                edgeNode.put("label", edge.label());
                edgeNode.set("properties", MAPPER.valueToTree(edge.properties()));
            }
        }
        return nodes;
    }

    private static List<PrismRowGraph> readGraphs(byte[] bytes) throws IOException {
        if (bytes == null) return List.of();
        JsonNode root = MAPPER.readTree(bytes);
        if (!root.isArray()) throw new IOException("Bundle graphs.json is not an array.");
        ArrayList<PrismRowGraph> graphs = new ArrayList<>();
        for (JsonNode node : root) {
            ArrayList<PrismRowGraphEdge> edges = new ArrayList<>();
            for (JsonNode edge : node.path("edges")) {
                edges.add(new PrismRowGraphEdge(requiredText(edge, "id"), requiredText(edge, "source"),
                        requiredText(edge, "target"), text(edge, "label"), objectMap(edge.get("properties"))));
            }
            graphs.add(new PrismRowGraph(requiredText(node, "id"), text(node, "title"), text(node, "description"),
                    text(node, "graph_type"), nullableText(node, "plugin_id"), node.path("schema_version").asInt(1),
                    node.path("directed").asBoolean(false), nullableText(node, "source_row_set_id"), edges,
                    objectMap(node.get("metadata")), objectMap(node.get("provenance"))));
        }
        return List.copyOf(graphs);
    }

    private static ArrayNode proposalsNode(List<PrismMoleculeDocument> proposals) {
        ArrayNode nodes = MAPPER.createArrayNode();
        for (PrismMoleculeDocument proposal : proposals) {
            ObjectNode node = nodes.addObject();
            node.put("id", proposal.id());
            node.put("title", proposal.title());
            node.put("mode", proposal.mode().name().toLowerCase(Locale.ROOT));
            node.put("idcode", proposal.idcode());
            node.put("coordinates", proposal.coordinates());
        }
        return nodes;
    }

    private static List<PrismMoleculeDocument> readProposals(byte[] bytes) throws IOException {
        if (bytes == null) return List.of();
        JsonNode root = MAPPER.readTree(bytes);
        if (!root.isArray()) throw new IOException("Bundle proposals.json is not an array.");
        ArrayList<PrismMoleculeDocument> proposals = new ArrayList<>();
        for (JsonNode node : root) {
            PrismMoleculeDocumentMode mode;
            try { mode = PrismMoleculeDocumentMode.valueOf(requiredText(node, "mode").toUpperCase(Locale.ROOT)); }
            catch (RuntimeException exception) { throw new IOException("Unknown proposal mode.", exception); }
            proposals.add(new PrismMoleculeDocument(requiredText(node, "id"), text(node, "title"), mode,
                    text(node, "idcode"), text(node, "coordinates"), 1));
        }
        return List.copyOf(proposals);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(JsonNode node) {
        return node == null || !node.isObject() ? Map.of() : MAPPER.convertValue(node, Map.class);
    }

    private static Map<String, byte[]> unzip(Path path) throws IOException {
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                if (entry.getName().contains("..") || entry.getName().startsWith("/")) throw new IOException("Unsafe bundle entry.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }
        return entries;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] required(Map<String, byte[]> entries, String name) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null) throw new IOException("Bundle is missing " + name + ".");
        return bytes;
    }

    private static boolean looksLikeZip(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return input.read() == 'P' && input.read() == 'K';
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        String value = text(node, field);
        if (value.isBlank()) throw new IOException("Missing bundle field: " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isBlank() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
