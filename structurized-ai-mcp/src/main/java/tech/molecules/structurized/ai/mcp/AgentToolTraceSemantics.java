package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.trace.AgentActivityType;
import tech.molecules.structurized.ai.trace.AgentAttentionRole;
import tech.molecules.structurized.ai.trace.AgentElementKind;
import tech.molecules.structurized.ai.trace.AgentElementReference;
import tech.molecules.structurized.ai.trace.AgentReferenceSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Curated, payload-safe semantics for MCP activity visualization. */
final class AgentToolTraceSemantics {
    private static final Set<String> INSPECT = Set.of(
            "inspect_structure", "inspect_atom", "inspect_bond", "inspect_atom_environment",
            "inspect_ring_system", "get_decomposition_result");
    private static final Set<String> COMPARE = Set.of("compare_structures", "find_shortest_path", "find_prism_graph_shortest_path");
    private static final Set<String> SEARCH = Set.of(
            "search_exact_structure", "search_substructure", "get_selection_members", "get_cluster_members",
            "get_prism_cluster_members", "get_prism_row_set_members", "get_prism_row_set_structures",
            "get_clustering", "get_cluster", "get_selection", "get_prism_clustering", "get_prism_grouping");
    private static final Set<String> GRAPH_EXPAND = Set.of(
            "inspect_prism_graph_neighborhood", "create_prism_graph_neighborhood_row_set");
    private static final Set<String> PROPOSE = Set.of("add_prism_molecules");
    private static final Set<String> ANALYZE = Set.of(
            "summarize_prism_mmp_transforms", "analyze_prism_graph", "analyze_prism_scaffold",
            "materialize_prism_scaffold_analysis",
            "get_prism_scaffold_projection", "summarize_prism_row_set_by_columns",
            "summarize_prism_grouping_by_columns", "summarize_selection_by_endpoint",
            "summarize_clusters_by_endpoint", "evaluate_decomposition", "get_decomposition_failures",
            "get_decomposition_fragment_summary", "get_decomposition_fragment_histogram",
            "cluster_structures", "cluster_prism_row_set", "evaluate_prism_prediction", "get_prediction_run",
            "get_prism_endpoint_results", "get_decomposition_evaluation");

    private AgentToolTraceSemantics() {}

    static Semantics forTool(String toolName) {
        AgentActivityType type;
        if (INSPECT.contains(toolName)) type = AgentActivityType.INSPECT;
        else if (COMPARE.contains(toolName)) type = AgentActivityType.COMPARE;
        else if (SEARCH.contains(toolName)) type = AgentActivityType.SEARCH;
        else if (GRAPH_EXPAND.contains(toolName)) type = AgentActivityType.GRAPH_EXPAND;
        else if (PROPOSE.contains(toolName)) type = AgentActivityType.PROPOSE;
        else if (ANALYZE.contains(toolName)) type = AgentActivityType.ANALYZE;
        else if (toolName.startsWith("create_") || toolName.startsWith("add_") || toolName.startsWith("open_")
                || toolName.startsWith("reload_") || toolName.startsWith("configure_") || toolName.startsWith("mine_")) {
            type = AgentActivityType.MANAGE;
        } else type = AgentActivityType.OTHER;
        return new Semantics(type, humanLabel(toolName));
    }

    static List<AgentElementReference> requestReferences(String toolName, ObjectNode arguments) {
        if (forTool(toolName).activityType() == AgentActivityType.OTHER) return List.of();
        ReferenceCollector refs = new ReferenceCollector(AgentReferenceSource.REQUEST);
        String repository = text(arguments, "repository_id", "session");
        String prism = text(arguments, "session_id", text(arguments, "dataset_id", null));
        refs.addText(arguments, "structure_id", AgentElementKind.REPOSITORY_STRUCTURE, repository, AgentAttentionRole.FOCUS);
        refs.addArray(arguments, "structure_ids", AgentElementKind.REPOSITORY_STRUCTURE, repository, AgentAttentionRole.TOUCHED);
        refs.addText(arguments, "left_structure_id", AgentElementKind.REPOSITORY_STRUCTURE,
                text(arguments, "left_repository_id", repository), AgentAttentionRole.FOCUS);
        refs.addText(arguments, "right_structure_id", AgentElementKind.REPOSITORY_STRUCTURE,
                text(arguments, "right_repository_id", repository), AgentAttentionRole.FOCUS);
        for (String field : List.of("row_id", "center_row_id", "source_row_id", "target_row_id", "left_row_id", "right_row_id")) {
            refs.addText(arguments, field, AgentElementKind.PRISM_ROW, prism, AgentAttentionRole.FOCUS);
        }
        refs.addArray(arguments, "row_ids", AgentElementKind.PRISM_ROW, prism, AgentAttentionRole.TOUCHED);
        refs.addText(arguments, "subject_id", AgentElementKind.PRISM_SUBJECT, prism, AgentAttentionRole.FOCUS);
        refs.addArray(arguments, "subject_ids", AgentElementKind.PRISM_SUBJECT, prism, AgentAttentionRole.TOUCHED);
        refs.addText(arguments, "document_id", AgentElementKind.PRISM_MOLECULE_DOCUMENT, prism, AgentAttentionRole.FOCUS);
        return refs.values();
    }

    static List<AgentElementReference> resultReferences(String toolName, ObjectNode arguments, JsonNode result) {
        AgentActivityType type = forTool(toolName).activityType();
        if (type == AgentActivityType.OTHER || result == null || result.isNull()) return List.of();
        String repository = text(arguments, "repository_id", "session");
        String prism = text(arguments, "session_id", text(arguments, "dataset_id", null));
        AgentAttentionRole role = type == AgentActivityType.PROPOSE
                ? AgentAttentionRole.PROPOSED
                : toolName.contains("shortest_path") ? AgentAttentionRole.TOUCHED : AgentAttentionRole.RETURNED;
        ReferenceCollector refs = new ReferenceCollector(AgentReferenceSource.RESULT);
        collectResultFields(result, repository, prism, role, refs);
        return refs.values();
    }

    private static void collectResultFields(JsonNode node, String repository, String prism,
                                            AgentAttentionRole role, ReferenceCollector refs) {
        if (node.isArray()) {
            node.forEach(child -> collectResultFields(child, repository, prism, role, refs));
            return;
        }
        if (!node.isObject()) return;
        String localRepository = text(node, "repositoryId", repository);
        String localPrism = text(node, "sessionId", text(node, "datasetId", prism));
        refs.addText(node, "rowId", AgentElementKind.PRISM_ROW, localPrism, role);
        refs.addArray(node, "rowIds", AgentElementKind.PRISM_ROW, localPrism, role);
        refs.addText(node, "structureId", AgentElementKind.REPOSITORY_STRUCTURE, localRepository, role);
        refs.addArray(node, "structureIds", AgentElementKind.REPOSITORY_STRUCTURE, localRepository, role);
        refs.addText(node, "subjectId", AgentElementKind.PRISM_SUBJECT, localPrism, role);
        refs.addArray(node, "subjectIds", AgentElementKind.PRISM_SUBJECT, localPrism, role);
        refs.addText(node, "documentId", AgentElementKind.PRISM_MOLECULE_DOCUMENT, localPrism, role);
        refs.addArray(node, "documentIds", AgentElementKind.PRISM_MOLECULE_DOCUMENT, localPrism, role);
        node.fields().forEachRemaining(entry -> collectResultFields(entry.getValue(), localRepository, localPrism, role, refs));
    }

    private static String humanLabel(String toolName) {
        StringBuilder label = new StringBuilder(toolName.replace('_', ' '));
        if (!label.isEmpty()) label.setCharAt(0, Character.toUpperCase(label.charAt(0)));
        return label.toString();
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : fallback;
    }

    record Semantics(AgentActivityType activityType, String label) {}

    private static final class ReferenceCollector {
        private final AgentReferenceSource source;
        private final Map<Key, AgentElementReference> references = new LinkedHashMap<>();

        private ReferenceCollector(AgentReferenceSource source) { this.source = source; }

        private void addText(JsonNode node, String field, AgentElementKind kind, String context, AgentAttentionRole role) {
            JsonNode value = node == null ? null : node.get(field);
            if (value != null && value.isTextual()) add(kind, context, value.asText(), role);
        }

        private void addArray(JsonNode node, String field, AgentElementKind kind, String context, AgentAttentionRole role) {
            JsonNode values = node == null ? null : node.get(field);
            if (values == null || !values.isArray()) return;
            values.forEach(value -> { if (value.isTextual()) add(kind, context, value.asText(), role); });
        }

        private void add(AgentElementKind kind, String context, String element, AgentAttentionRole role) {
            if (context == null || context.isBlank() || element == null || element.isBlank()) return;
            Key key = new Key(kind, context.trim(), element.trim());
            AgentElementReference existing = references.get(key);
            if (existing == null || strength(role) > strength(existing.role())) {
                references.put(key, new AgentElementReference(kind, key.context(), key.element(), role, source));
            }
        }

        private List<AgentElementReference> values() { return List.copyOf(new ArrayList<>(references.values())); }

        private static int strength(AgentAttentionRole role) {
            return switch (role) {
                case RETURNED -> 0;
                case TOUCHED -> 1;
                case FOCUS -> 2;
                case PROPOSED -> 3;
            };
        }

        private record Key(AgentElementKind kind, String context, String element) {}
    }
}
