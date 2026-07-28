package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.prism.PrismCollapsedGraphNeighborhood;
import tech.molecules.structurized.ai.prism.PrismGraphAnalysis;
import tech.molecules.structurized.ai.prism.PrismGraphEdgeView;
import tech.molecules.structurized.ai.prism.PrismGraphNeighborhood;
import tech.molecules.structurized.ai.prism.PrismGraphPathStep;
import tech.molecules.structurized.ai.prism.PrismGraphShortestPath;
import tech.molecules.structurized.ai.prism.PrismGraphTsvExport;
import tech.molecules.structurized.ai.prism.PrismMmpTransformSummary;
import tech.molecules.structurized.ai.prism.PrismMmpTransformText;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PrismGraphMcpTool {
    private final PrismBridgeService prism;
    private final McpArtifactService artifacts;
    private final McpToolOutputSupport output;

    PrismGraphMcpTool(PrismBridgeService prism, McpArtifactService artifacts, McpToolOutputSupport output) {
        this.prism = Objects.requireNonNull(prism, "prism");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.output = Objects.requireNonNull(output, "output");
    }

    Object inspectPrismGraphNeighborhood(ObjectNode args) {
        String outputMode = normalizeGraphNeighborhoodOutputMode(optionalString(args, "output_mode", "stats"));
        int defaultLimit = "full".equals(outputMode) ? 50 : 10;
        int limit = "stats".equals(outputMode) ? 1 : Math.max(1, optionalInt(args, "limit", defaultLimit));
        String sessionId = requiredString(args, "session_id");
        String graphId = requiredString(args, "graph_id");
        String centerRowId = requiredString(args, "center_row_id");
        Object response;
        int neighborCount;
        int edgeCount;
        int returnedNeighbors;
        if ("collapsed".equals(outputMode)) {
            PrismCollapsedGraphNeighborhood collapsed = prism.inspectCollapsedGraphNeighborhood(
                    sessionId,
                    graphId,
                    centerRowId,
                    limit,
                    optionalInt(args, "transform_example_limit", 3));
            response = collapsed;
            neighborCount = collapsed.neighborCount();
            edgeCount = collapsed.edgeCount();
            returnedNeighbors = collapsed.returnedNeighbors();
        } else {
            PrismGraphNeighborhood raw = prism.inspectGraphNeighborhood(sessionId, graphId, centerRowId, limit);
            response = switch (outputMode) {
                case "stats" -> new PrismGraphNeighborhoodStats(
                        raw.graph(),
                        raw.center(),
                        raw.neighborCount(),
                        raw.edgeCount(),
                        outputMode);
                case "compact" -> compactGraphNeighborhood(raw, outputMode);
                case "full" -> raw;
                default -> throw new IllegalStateException("Unexpected graph neighborhood output mode: " + outputMode);
            };
            neighborCount = raw.neighborCount();
            edgeCount = raw.edgeCount();
            returnedNeighbors = "stats".equals(outputMode) ? 0 : raw.neighbors().size();
        }
        GraphNeighborhoodArtifactSummary summary = new GraphNeighborhoodArtifactSummary(
                sessionId,
                graphId,
                centerRowId,
                outputMode,
                neighborCount,
                edgeCount,
                returnedNeighbors);
        return output.maybeFile(args, "inspect_prism_graph_neighborhood", response, summary, returnedNeighbors);
    }

    Object findPrismGraphShortestPath(ObjectNode args) {
        String outputMode = normalizeGraphShortestPathOutputMode(optionalString(args, "output_mode", "stats"));
        boolean includePath = optionalBoolean(args, "include_path", false);
        PrismGraphShortestPath response = prism.findGraphShortestPath(
                requiredString(args, "session_id"),
                requiredString(args, "graph_id"),
                requiredString(args, "source_row_id"),
                requiredString(args, "target_row_id"),
                includePath,
                Math.max(0, optionalInt(args, "max_depth", 0)),
                Math.max(1, optionalInt(args, "transform_example_limit", 2)));
        Object responsePayload = switch (outputMode) {
            case "stats" -> compactShortestPath(response, outputMode, false);
            case "compact" -> compactShortestPath(response, outputMode, includePath);
            case "full" -> response;
            default -> throw new IllegalStateException("Unexpected graph shortest path output mode: " + outputMode);
        };
        return output.maybeFile(
                args,
                "find_prism_graph_shortest_path",
                response,
                new PrismGraphShortestPathArtifactSummary(
                        response.graph().sessionId(),
                        response.graph().graphId(),
                        response.source().rowId(),
                        response.target().rowId(),
                        response.connected(),
                        response.distance(),
                        response.steps().size()),
                response.steps().size(),
                responsePayload);
    }

    Object summarizePrismMmpTransforms(ObjectNode args) {
        PrismMmpTransformSummary response = prism.summarizeMmpTransforms(
                requiredString(args, "session_id"),
                requiredString(args, "graph_id"),
                optionalInt(args, "min_support", 1),
                optionalString(args, "sort_by", "support_desc"),
                Math.max(0, optionalInt(args, "offset", 0)),
                Math.max(1, optionalInt(args, "limit", 50)),
                Math.max(1, optionalInt(args, "example_limit", 3)));
        return output.maybeFile(
                args,
                "summarize_prism_mmp_transforms",
                response,
                new PrismMmpTransformArtifactSummary(
                        response.graph().sessionId(),
                        response.graph().graphId(),
                        response.sortBy(),
                        response.totalTransforms(),
                        response.returnedTransforms(),
                        response.offset(),
                        response.limit()),
                response.returnedTransforms());
    }

    Object analyzePrismGraph(ObjectNode args) {
        PrismGraphAnalysis analysis = prism.analyzeGraph(
                requiredString(args, "session_id"),
                requiredString(args, "graph_id"),
                optionalInt(args, "limit", 20));
        return output.maybeFile(
                args,
                "analyze_prism_graph",
                analysis,
                new PrismGraphAnalysisArtifactSummary(
                        analysis.graph().sessionId(),
                        analysis.graph().graphId(),
                        analysis.graph().nodeCount(),
                        analysis.graph().edgeCount(),
                        analysis.topDegreeRows().size()),
                analysis.topDegreeRows().size());
    }

    Object exportPrismGraph(ObjectNode args) {
        PrismGraphTsvExport export = prism.exportGraph(
                requiredString(args, "session_id"),
                requiredString(args, "graph_id"),
                optionalString(args, "format", "edges_tsv"));
        McpArtifactService.ArtifactRecord artifact = artifacts.writeText(
                "export_prism_graph",
                optionalString(args, "output_name", null),
                optionalBoolean(args, "overwrite", false),
                "tsv",
                "text/tab-separated-values",
                export.tsv(),
                export.rowCount());
        return new ExportPrismGraphResult(
                new ExportPrismGraphSummary(export.graph(), export.format(), export.rowCount()),
                artifact);
    }

    private static CompactPrismGraphNeighborhood compactGraphNeighborhood(PrismGraphNeighborhood raw, String outputMode) {
        List<CompactPrismGraphNeighbor> neighbors = raw.neighbors().stream()
                .map(neighbor -> new CompactPrismGraphNeighbor(
                        neighbor.row().rowId(),
                        neighbor.row().subjectId(),
                        neighbor.row().structureId(),
                        neighbor.row().smiles(),
                        neighbor.degree(),
                        neighbor.edges().size(),
                        neighbor.edges().stream()
                                .map(PrismGraphMcpTool::compactGraphEdge)
                                .toList()))
                .toList();
        return new CompactPrismGraphNeighborhood(
                raw.graph(),
                raw.center(),
                raw.neighborCount(),
                raw.edgeCount(),
                outputMode,
                neighbors.size(),
                neighbors);
    }

    private static CompactPrismGraphEdge compactGraphEdge(PrismGraphEdgeView edge) {
        Map<String, Object> properties = edge.properties();
        return new CompactPrismGraphEdge(
                edge.edgeId(),
                edge.sourceRowId(),
                edge.targetRowId(),
                propertyString(properties, "transformId"),
                propertyString(properties, "transformText"),
                propertyString(properties, "fromFragment"),
                propertyString(properties, "toFragment"),
                propertyInteger(properties, "cutCount"),
                propertyDouble(properties, "delta"));
    }

    private static CompactPrismGraphShortestPath compactShortestPath(PrismGraphShortestPath path, String outputMode, boolean includePath) {
        return new CompactPrismGraphShortestPath(
                path.graph().sessionId(),
                path.graph().graphId(),
                path.source().rowId(),
                path.target().rowId(),
                path.connected(),
                path.distance(),
                path.searchedDepth(),
                path.reason(),
                outputMode,
                includePath ? path.pathRows().stream()
                        .map(row -> new CompactPrismGraphPathRow(row.rowId(), row.subjectId(), row.structureId(), row.smiles()))
                        .toList() : List.of(),
                includePath ? path.steps().stream().map(PrismGraphMcpTool::compactPathStep).toList() : List.of());
    }

    private static CompactPrismGraphPathStep compactPathStep(PrismGraphPathStep step) {
        return new CompactPrismGraphPathStep(
                step.fromRowId(),
                step.toRowId(),
                step.rawEdgeCount(),
                step.exampleTransforms().stream().map(PrismGraphMcpTool::compactPathTransform).toList());
    }

    private static CompactPrismGraphPathTransform compactPathTransform(PrismMmpTransformText transform) {
        return new CompactPrismGraphPathTransform(
                transform.transformId(),
                transform.transformText(),
                transform.keyFragment(),
                transform.fromFragment(),
                transform.toFragment(),
                transform.cutCount());
    }

    private static String normalizeGraphNeighborhoodOutputMode(String value) {
        String normalized = value == null || value.isBlank() ? "stats" : value.trim().toLowerCase();
        if (!"stats".equals(normalized) && !"collapsed".equals(normalized) && !"compact".equals(normalized) && !"full".equals(normalized)) {
            throw new ChemOperationException("invalid_graph_neighborhood_output_mode", "output_mode must be stats, collapsed, compact, or full.");
        }
        return normalized;
    }

    private static String normalizeGraphShortestPathOutputMode(String value) {
        String normalized = value == null || value.isBlank() ? "stats" : value.trim().toLowerCase();
        if (!"stats".equals(normalized) && !"compact".equals(normalized) && !"full".equals(normalized)) {
            throw new ChemOperationException("invalid_graph_shortest_path_output_mode", "output_mode must be stats, compact, or full.");
        }
        return normalized;
    }

    private static String propertyString(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer propertyInteger(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double propertyDouble(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String requiredString(ObjectNode args, String name) {
        String value = optionalString(args, name, null);
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        return value;
    }

    private static String optionalString(ObjectNode args, String name, String defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isTextual()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a string.");
        }
        return node.asText();
    }

    private static int optionalInt(ObjectNode args, String name, int defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.canConvertToInt()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asInt();
    }

    private static boolean optionalBoolean(ObjectNode args, String name, boolean defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isBoolean()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private record PrismGraphNeighborhoodStats(
            Object graph,
            Object center,
            int neighborCount,
            int edgeCount,
            String outputMode
    ) {}

    private record CompactPrismGraphNeighborhood(
            Object graph,
            Object center,
            int neighborCount,
            int edgeCount,
            String outputMode,
            int returnedNeighbors,
            List<CompactPrismGraphNeighbor> neighbors
    ) {
        private CompactPrismGraphNeighborhood {
            neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
        }
    }

    private record CompactPrismGraphNeighbor(
            String rowId,
            String subjectId,
            String structureId,
            String smiles,
            int degree,
            int edgeCount,
            List<CompactPrismGraphEdge> edges
    ) {
        private CompactPrismGraphNeighbor {
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    private record CompactPrismGraphEdge(
            String edgeId,
            String sourceRowId,
            String targetRowId,
            String transformId,
            String transformText,
            String fromFragment,
            String toFragment,
            Integer cutCount,
            Double delta
    ) {}

    private record CompactPrismGraphShortestPath(
            String sessionId,
            String graphId,
            String sourceRowId,
            String targetRowId,
            boolean connected,
            Integer distance,
            int searchedDepth,
            String reason,
            String outputMode,
            List<CompactPrismGraphPathRow> pathRows,
            List<CompactPrismGraphPathStep> steps
    ) {
        private CompactPrismGraphShortestPath {
            pathRows = pathRows == null ? List.of() : List.copyOf(pathRows);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    private record CompactPrismGraphPathRow(
            String rowId,
            String subjectId,
            String structureId,
            String smiles
    ) {}

    private record CompactPrismGraphPathStep(
            String fromRowId,
            String toRowId,
            int rawEdgeCount,
            List<CompactPrismGraphPathTransform> exampleTransforms
    ) {
        private CompactPrismGraphPathStep {
            exampleTransforms = exampleTransforms == null ? List.of() : List.copyOf(exampleTransforms);
        }
    }

    private record CompactPrismGraphPathTransform(
            String transformId,
            String transformText,
            String keyFragment,
            String fromFragment,
            String toFragment,
            Integer cutCount
    ) {}

    private record GraphNeighborhoodArtifactSummary(
            String sessionId,
            String graphId,
            String centerRowId,
            String outputMode,
            int neighborCount,
            int edgeCount,
            int returnedNeighbors
    ) {}

    private record PrismGraphAnalysisArtifactSummary(
            String sessionId,
            String graphId,
            int nodeCount,
            int edgeCount,
            int returnedTopRows
    ) {}

    private record PrismMmpTransformArtifactSummary(
            String sessionId,
            String graphId,
            String sortBy,
            int totalTransforms,
            int returnedTransforms,
            int offset,
            int limit
    ) {}

    private record PrismGraphShortestPathArtifactSummary(
            String sessionId,
            String graphId,
            String sourceRowId,
            String targetRowId,
            boolean connected,
            Integer distance,
            int returnedSteps
    ) {}

    private record ExportPrismGraphResult(ExportPrismGraphSummary summary, McpArtifactService.ArtifactRecord artifact) {}

    private record ExportPrismGraphSummary(Object graph, String format, int rowCount) {}
}
