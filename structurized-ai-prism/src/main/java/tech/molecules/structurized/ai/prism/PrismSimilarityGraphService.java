package tech.molecules.structurized.ai.prism;

import com.actelion.research.chem.Molecule;
import com.actelion.research.chem.StereoMolecule;
import com.actelion.research.chem.descriptor.DescriptorHandlerSkeletonSpheres;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.clustering.SimilarityClusteringConfig;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismOperationResult;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.ocl.OclStructureFormat;
import tech.molecules.structurized.prism.engine.ocl.OclStructureParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

final class PrismSimilarityGraphService {
    static final String GRAPH_TYPE = "chemistry.similarity";
    static final String PLUGIN_ID = "structurized-similarity";
    private static final String MODE_KNN = "knn";
    private static final String MODE_THRESHOLD = "threshold";
    private static final String MODE_HYBRID = "hybrid";
    private static final int DEFAULT_NEIGHBOR_COUNT = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.85;

    PrismSimilarityGraphSummary mine(ManagedPrismSession session,
                                     PrismRowSet sourceRowSet,
                                     MinePrismSimilarityGraphRequest request) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(sourceRowSet, "sourceRowSet");
        Objects.requireNonNull(request, "request");
        PrismSession workspace = session.workspace();
        PrismColumn structureColumn = structureColumn(workspace, request.structureColumnId());
        SimilarityConfig config = config(request);
        String graphId = resolveGraphId(workspace, request.graphId());
        String label = request.label() == null || request.label().isBlank() ? graphId : request.label().trim();
        long sourceRevision = session.revision();

        PreparedRows prepared = prepareRows(workspace, sourceRowSet, structureColumn);
        if (prepared.rows().isEmpty()) {
            throw new ChemOperationException(
                    "no_similarity_structure_rows",
                    "Prism row set " + sourceRowSet.id() + " contains no valid structures for similarity graph mining."
            );
        }

        MinedSimilarityGraph mined = mineEdges(prepared.rows(), config);
        Map<String, Object> configMap = configMap(config);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceRowCount", sourceRowSet.rowIds().size());
        metadata.put("validStructureCount", prepared.rows().size());
        metadata.put("skippedRowCount", prepared.skippedRows().size());
        metadata.put("edgeCount", mined.edges().size());
        metadata.put("structureColumnId", structureColumn.id());
        metadata.put("configuration", configMap);
        metadata.put("similarity", statsMap(mined.stats()));

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "structurized_similarity_graph_mining");
        provenance.put("sessionId", session.sessionId());
        provenance.put("sourceRevision", sourceRevision);
        provenance.put("sourceRowSetId", sourceRowSet.id());
        provenance.put("structureColumnId", structureColumn.id());
        provenance.put("createdAt", Instant.now().toString());
        provenance.put("configuration", configMap);

        PrismRowGraph graph = new PrismRowGraph(
                graphId,
                label,
                "Chemical similarity graph mined by Structurized.",
                GRAPH_TYPE,
                PLUGIN_ID,
                1,
                false,
                sourceRowSet.id(),
                mined.edges(),
                metadata,
                provenance
        );
        PrismOperationResult publication = PrismOperationResult.builder()
                .addGraph(graph)
                .provenance("graphId", graphId)
                .provenance("analysisType", GRAPH_TYPE)
                .output("graphId", graphId)
                .output("edgeCount", mined.edges().size())
                .build();
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> workspace.applyOperationResult(publication));
        return new PrismSimilarityGraphSummary(
                PrismMmpGraphService.graphSummary(session, graph),
                structureColumn.id(),
                sourceRowSet.rowIds().size(),
                prepared.rows().size(),
                prepared.skippedRows().size(),
                mined.edges().size(),
                configMap,
                mined.stats(),
                prepared.skippedRows()
        );
    }

    private static PreparedRows prepareRows(PrismSession workspace, PrismRowSet sourceRowSet, PrismColumn structureColumn) {
        ArrayList<PreparedRow> rows = new ArrayList<>();
        ArrayList<PrismSkippedAnalysisRow> skipped = new ArrayList<>();
        DescriptorHandlerSkeletonSpheres descriptorHandler = DescriptorHandlerSkeletonSpheres.getDefaultInstance();
        OclStructureParser parser = new OclStructureParser();
        OclStructureFormat format = OclStructureFormat.fromMetadata(structureColumn.schema().structureFormat());
        for (String rowId : sourceRowSet.rowIds()) {
            int physicalRow = workspace.physicalRowForRowId(rowId)
                    .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist."));
            if (structureColumn.isMissing(physicalRow)) {
                skipped.add(new PrismSkippedAnalysisRow(rowId, "missing_structure", "Row has no structure value."));
                continue;
            }
            try {
                StereoMolecule molecule = parser.parse(structureColumn.formattedValueAt(physicalRow), null, format);
                if (molecule == null) {
                    skipped.add(new PrismSkippedAnalysisRow(rowId, "missing_structure", "Row has no usable structure value."));
                    continue;
                }
                molecule.ensureHelperArrays(Molecule.cHelperSymmetrySimple);
                byte[] descriptor = descriptorHandler.createDescriptor(molecule);
                if (descriptorHandler.calculationFailed(descriptor)) {
                    skipped.add(new PrismSkippedAnalysisRow(rowId, "descriptor_failed", "SkeletonSpheres descriptor calculation failed."));
                    continue;
                }
                rows.add(new PreparedRow(rowId, descriptor));
            } catch (RuntimeException exception) {
                skipped.add(new PrismSkippedAnalysisRow(rowId, "invalid_structure", exception.getMessage()));
            }
        }
        return new PreparedRows(List.copyOf(rows), List.copyOf(skipped));
    }

    private static MinedSimilarityGraph mineEdges(List<PreparedRow> rows, SimilarityConfig config) {
        DescriptorHandlerSkeletonSpheres descriptorHandler = DescriptorHandlerSkeletonSpheres.getDefaultInstance();
        Map<EdgeKey, EdgeAccumulator> edges = new HashMap<>();
        ArrayList<PriorityQueue<NeighborCandidate>> topQueues = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            topQueues.add(new PriorityQueue<>(PrismSimilarityGraphService::compareWorstNeighborFirst));
        }
        boolean useThreshold = MODE_THRESHOLD.equals(config.mode()) || MODE_HYBRID.equals(config.mode());
        boolean useKnn = MODE_KNN.equals(config.mode()) || MODE_HYBRID.equals(config.mode());
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                double similarity = descriptorHandler.getSimilarity(rows.get(i).descriptor(), rows.get(j).descriptor());
                if (Double.isNaN(similarity)) {
                    continue;
                }
                if (useThreshold && similarity >= config.similarityThreshold()) {
                    EdgeAccumulator edge = edge(edges, i, j, similarity);
                    edge.threshold = true;
                }
                if (config.neighborCount() > 0) {
                    offerTopNeighbor(topQueues.get(i), new NeighborCandidate(j, rows.get(j).rowId(), similarity), config.neighborCount());
                    offerTopNeighbor(topQueues.get(j), new NeighborCandidate(i, rows.get(i).rowId(), similarity), config.neighborCount());
                }
            }
        }
        if (useKnn) {
            for (int i = 0; i < rows.size(); i++) {
                List<NeighborCandidate> sorted = topQueues.get(i).stream()
                        .sorted(PrismSimilarityGraphService::compareBestNeighborFirst)
                        .toList();
                for (int rank = 0; rank < sorted.size(); rank++) {
                    NeighborCandidate candidate = sorted.get(rank);
                    int a = Math.min(i, candidate.index());
                    int b = Math.max(i, candidate.index());
                    EdgeAccumulator edge = edge(edges, a, b, candidate.similarity());
                    edge.knn = true;
                    if (i == a) {
                        edge.rankAtoB = rank + 1;
                    } else {
                        edge.rankBtoA = rank + 1;
                    }
                }
            }
        }
        List<EdgeAccumulator> selected = edges.values().stream()
                .filter(edge -> switch (config.mode()) {
                    case MODE_KNN -> edge.knn;
                    case MODE_THRESHOLD -> edge.threshold;
                    case MODE_HYBRID -> edge.knn || edge.threshold;
                    default -> false;
                })
                .filter(edge -> !config.mutualKnnOnly() || edge.isMutualKnn())
                .sorted(Comparator
                        .comparing((EdgeAccumulator edge) -> rows.get(edge.a).rowId())
                        .thenComparing(edge -> rows.get(edge.b).rowId()))
                .toList();
        if (config.maxEdges() != null && selected.size() > config.maxEdges()) {
            throw new ChemOperationException(
                    "similarity_graph_too_dense",
                    "Similarity graph would contain " + selected.size() + " edges, above max_edges " + config.maxEdges() + "."
            );
        }
        ArrayList<PrismRowGraphEdge> graphEdges = new ArrayList<>(selected.size());
        ArrayList<Double> similarities = new ArrayList<>(selected.size());
        int mutualKnnCount = 0;
        LinkedHashMap<String, Integer> sourceCounts = new LinkedHashMap<>();
        int index = 1;
        for (EdgeAccumulator edge : selected) {
            String edgeSource = edgeSource(edge);
            sourceCounts.merge(edgeSource, 1, Integer::sum);
            if (edge.isMutualKnn()) {
                mutualKnnCount++;
            }
            similarities.add(edge.similarity);
            LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
            properties.put("relationType", "chemical_similarity");
            properties.put("similarity", round(edge.similarity));
            properties.put("descriptor", config.descriptor());
            properties.put("edgeSource", edgeSource);
            if (edge.rankAtoB != null) properties.put("rankAtoB", edge.rankAtoB);
            if (edge.rankBtoA != null) properties.put("rankBtoA", edge.rankBtoA);
            properties.put("mutualKnn", edge.isMutualKnn());
            graphEdges.add(new PrismRowGraphEdge(
                    "similarity-edge-" + index++,
                    rows.get(edge.a).rowId(),
                    rows.get(edge.b).rowId(),
                    "similarity " + round(edge.similarity),
                    properties
            ));
        }
        similarities.sort(Double::compareTo);
        return new MinedSimilarityGraph(
                List.copyOf(graphEdges),
                similarityStats(similarities, mutualKnnCount, sourceCounts)
        );
    }

    private static EdgeAccumulator edge(Map<EdgeKey, EdgeAccumulator> edges, int a, int b, double similarity) {
        EdgeKey key = new EdgeKey(a, b);
        return edges.computeIfAbsent(key, ignored -> new EdgeAccumulator(a, b, similarity));
    }

    private static void offerTopNeighbor(PriorityQueue<NeighborCandidate> queue, NeighborCandidate candidate, int limit) {
        if (queue.size() < limit) {
            queue.add(candidate);
            return;
        }
        NeighborCandidate worst = queue.peek();
        if (worst != null && compareBestNeighborFirst(candidate, worst) < 0) {
            queue.poll();
            queue.add(candidate);
        }
    }

    private static int compareBestNeighborFirst(NeighborCandidate a, NeighborCandidate b) {
        int bySimilarity = Double.compare(b.similarity(), a.similarity());
        return bySimilarity != 0 ? bySimilarity : a.rowId().compareTo(b.rowId());
    }

    private static int compareWorstNeighborFirst(NeighborCandidate a, NeighborCandidate b) {
        int bySimilarity = Double.compare(a.similarity(), b.similarity());
        return bySimilarity != 0 ? bySimilarity : b.rowId().compareTo(a.rowId());
    }

    private static String edgeSource(EdgeAccumulator edge) {
        if (edge.knn && edge.threshold) return MODE_HYBRID;
        if (edge.knn) return MODE_KNN;
        return MODE_THRESHOLD;
    }

    static PrismGraphSimilarityStats similarityStats(List<Double> sortedSimilarities,
                                                     int mutualKnnCount,
                                                     Map<String, Integer> sourceCounts) {
        if (sortedSimilarities.isEmpty()) {
            return new PrismGraphSimilarityStats(0, null, null, null, null, null, 0, sourceCounts);
        }
        return new PrismGraphSimilarityStats(
                sortedSimilarities.size(),
                round(sortedSimilarities.getFirst()),
                round(percentile(sortedSimilarities, 0.25)),
                round(percentile(sortedSimilarities, 0.50)),
                round(percentile(sortedSimilarities, 0.75)),
                round(sortedSimilarities.getLast()),
                mutualKnnCount,
                sourceCounts
        );
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        if (sorted.size() == 1) return sorted.getFirst();
        double position = p * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }

    private static Map<String, Object> statsMap(PrismGraphSimilarityStats stats) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("edgeCount", stats.edgeCount());
        if (stats.min() != null) map.put("min", stats.min());
        if (stats.p25() != null) map.put("p25", stats.p25());
        if (stats.median() != null) map.put("median", stats.median());
        if (stats.p75() != null) map.put("p75", stats.p75());
        if (stats.max() != null) map.put("max", stats.max());
        map.put("mutualKnnEdgeCount", stats.mutualKnnEdgeCount());
        map.put("edgeSourceCounts", stats.edgeSourceCounts());
        return Map.copyOf(map);
    }

    private static SimilarityConfig config(MinePrismSimilarityGraphRequest request) {
        String descriptor = request.descriptor() == null || request.descriptor().isBlank()
                ? SimilarityClusteringConfig.DESCRIPTOR_SKELSPHERES
                : request.descriptor().trim().toLowerCase(Locale.ROOT);
        if (!SimilarityClusteringConfig.DESCRIPTOR_SKELSPHERES.equals(descriptor)) {
            throw new ChemOperationException("invalid_similarity_descriptor", "descriptor must be skelspheres.");
        }
        String mode = request.mode() == null || request.mode().isBlank()
                ? MODE_HYBRID
                : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!MODE_KNN.equals(mode) && !MODE_THRESHOLD.equals(mode) && !MODE_HYBRID.equals(mode)) {
            throw new ChemOperationException("invalid_similarity_graph_mode", "mode must be knn, threshold, or hybrid.");
        }
        int neighborCount = request.neighborCount() == null ? DEFAULT_NEIGHBOR_COUNT : request.neighborCount();
        if (neighborCount < 0) {
            throw new ChemOperationException("invalid_similarity_neighbor_count", "neighbor_count must be >= 0.");
        }
        if ((MODE_KNN.equals(mode) || MODE_HYBRID.equals(mode)) && neighborCount < 1) {
            throw new ChemOperationException("invalid_similarity_neighbor_count", "neighbor_count must be at least 1 for knn or hybrid mode.");
        }
        double threshold = request.similarityThreshold() == null ? DEFAULT_SIMILARITY_THRESHOLD : request.similarityThreshold();
        if (Double.isNaN(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new ChemOperationException("invalid_similarity_threshold", "similarity_threshold must be between 0.0 and 1.0.");
        }
        Integer maxEdges = request.maxEdges();
        if (maxEdges != null && maxEdges < 1) {
            throw new ChemOperationException("invalid_similarity_max_edges", "max_edges must be at least 1 when provided.");
        }
        return new SimilarityConfig(
                descriptor,
                mode,
                neighborCount,
                threshold,
                Boolean.TRUE.equals(request.mutualKnnOnly()),
                maxEdges
        );
    }

    private static Map<String, Object> configMap(SimilarityConfig config) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("descriptor", config.descriptor());
        map.put("mode", config.mode());
        map.put("neighborCount", config.neighborCount());
        map.put("similarityThreshold", config.similarityThreshold());
        map.put("mutualKnnOnly", config.mutualKnnOnly());
        if (config.maxEdges() != null) map.put("maxEdges", config.maxEdges());
        return Map.copyOf(map);
    }

    private static PrismColumn structureColumn(PrismSession workspace, String requestedColumnId) {
        if (requestedColumnId != null && !requestedColumnId.isBlank()) {
            PrismColumn column = workspace.table().column(requestedColumnId.trim());
            if (column.type() != PrismColumnType.MOLECULE && !"chemical_structure".equals(column.schema().semanticType())) {
                throw new ChemOperationException("invalid_structure_column", "Column " + column.id() + " is not a structure column.");
            }
            return column;
        }
        return workspace.table().columns().stream()
                .filter(column -> column.type() == PrismColumnType.MOLECULE
                        || "chemical_structure".equals(column.schema().semanticType())
                        || "primary_structure".equals(column.schema().role()))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("missing_structure_column", "No structure column is available in this Prism session."));
    }

    private static String resolveGraphId(PrismSession workspace, String requestedGraphId) {
        String base = requestedGraphId == null || requestedGraphId.isBlank() ? "similarity_graph" : requestedGraphId.trim();
        String normalized = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "similarity_graph";
        String candidate = normalized;
        int index = 1;
        LinkedHashSet<String> existing = workspace.graphs().stream()
                .map(PrismRowGraph::id)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        while (existing.contains(candidate)) {
            candidate = normalized + "_" + index++;
        }
        return candidate;
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private record SimilarityConfig(
            String descriptor,
            String mode,
            int neighborCount,
            double similarityThreshold,
            boolean mutualKnnOnly,
            Integer maxEdges
    ) {}

    private record PreparedRow(String rowId, byte[] descriptor) {}

    private record PreparedRows(List<PreparedRow> rows, List<PrismSkippedAnalysisRow> skippedRows) {}

    private record MinedSimilarityGraph(List<PrismRowGraphEdge> edges, PrismGraphSimilarityStats stats) {}

    private record NeighborCandidate(int index, String rowId, double similarity) {}

    private record EdgeKey(int a, int b) {}

    private static final class EdgeAccumulator {
        private final int a;
        private final int b;
        private final double similarity;
        private boolean knn;
        private boolean threshold;
        private Integer rankAtoB;
        private Integer rankBtoA;

        private EdgeAccumulator(int a, int b, double similarity) {
            this.a = a;
            this.b = b;
            this.similarity = similarity;
        }

        private boolean isMutualKnn() {
            return rankAtoB != null && rankBtoA != null;
        }
    }
}
