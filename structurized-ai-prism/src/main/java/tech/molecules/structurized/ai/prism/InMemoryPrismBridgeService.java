package tech.molecules.structurized.ai.prism;

import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.prism.engine.CategoryIncludeFilter;
import tech.molecules.structurized.prism.engine.MissingValueFilter;
import tech.molecules.structurized.prism.engine.MissingValueMode;
import tech.molecules.structurized.prism.engine.NumericRangeFilter;
import tech.molecules.structurized.prism.engine.PrismColumn;
import tech.molecules.structurized.prism.engine.PrismColumnType;
import tech.molecules.structurized.prism.engine.PrismEvaluationContext;
import tech.molecules.structurized.prism.engine.PrismFilter;
import tech.molecules.structurized.prism.engine.PrismGroup;
import tech.molecules.structurized.prism.engine.PrismGroupMembership;
import tech.molecules.structurized.prism.engine.PrismGrouping;
import tech.molecules.structurized.prism.engine.PrismGroupingMode;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocumentMode;
import tech.molecules.structurized.prism.engine.PrismMoleculeList;
import tech.molecules.structurized.prism.engine.PrismOperationResult;
import tech.molecules.structurized.prism.engine.PrismColumnSchema;
import tech.molecules.structurized.prism.engine.PrismRowGraph;
import tech.molecules.structurized.prism.engine.PrismRowGraphEdge;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.TextPatternFilter;
import tech.molecules.structurized.prism.engine.live.PrismLiveBinding;
import tech.molecules.structurized.prism.engine.live.PrismLiveEvaluation;
import tech.molecules.structurized.prism.engine.live.PrismLiveExecutionMode;
import tech.molecules.structurized.prism.engine.live.PrismLiveSuccessfulResult;
import tech.molecules.structurized.prism.engine.TextPatternMode;
import tech.molecules.structurized.prism.engine.ocl.OclMoleculeDocumentCodec;
import tech.molecules.structurized.prism.io.PrismTsvDatasetLoader;
import tech.molecules.structurized.prism.io.PrismTsvSnapshotLoader;
import tech.molecules.structurized.prism.model.CategoryDefinition;
import tech.molecules.structurized.prism.prediction.PredictionCapability;
import tech.molecules.structurized.prism.model.EndpointDefinition;
import tech.molecules.structurized.prism.model.NumericEndpointMeta;
import tech.molecules.structurized.prism.provider.SubjectRecord;
import tech.molecules.structurized.prism.provider.SubjectSet;
import tech.molecules.structurized.prism.provider.inmemory.InMemoryPrismDataset;
import tech.molecules.structurized.prism.query.EndpointValueRecord;
import tech.molecules.structurized.prism.result.EndpointResult;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.prism.result.OptionalNumericResult;
import tech.molecules.structurized.prism.result.OptionalNumericState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class InMemoryPrismBridgeService implements PrismBridgeService {
    private static final int PAGE_LIMIT_DEFAULT = 100;
    private static final int PAGE_LIMIT_MAX = 1_000;

    private final StructureRepositoryService repositories;
    private final PrismArtifactRegistry artifactRegistry;
    private final PrismGroupingClusteringService clustering;
    private final PrismMmpGraphService mmpGraphs;
    private final PrismSimilarityGraphService similarityGraphs;
    private final PrismPredictionService predictions;
    private final PrismSessionRegistry sessionRegistry;
    private final OclMoleculeDocumentCodec moleculeCodec = new OclMoleculeDocumentCodec();
    private final Map<String, MaterializationMapping> materializationsByRepositoryId = new LinkedHashMap<>();

    public InMemoryPrismBridgeService(StructureRepositoryService repositories) {
        this(repositories, new InMemoryPrismSessionRegistry(), new InMemoryPrismArtifactRegistry());
    }

    public InMemoryPrismBridgeService(StructureRepositoryService repositories, PrismSessionRegistry sessionRegistry) {
        this(repositories, sessionRegistry, new InMemoryPrismArtifactRegistry());
    }

    public InMemoryPrismBridgeService(StructureRepositoryService repositories,
                                      PrismSessionRegistry sessionRegistry,
                                      PrismArtifactRegistry artifactRegistry) {
        this(repositories, sessionRegistry, artifactRegistry, InMemoryPredictionRegistry.referenceRegistry());
    }

    public InMemoryPrismBridgeService(StructureRepositoryService repositories,
                                      PrismSessionRegistry sessionRegistry,
                                      PrismArtifactRegistry artifactRegistry,
                                      PredictionRegistry predictionRegistry) {
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.artifactRegistry = Objects.requireNonNull(artifactRegistry, "artifactRegistry");
        this.clustering = new PrismGroupingClusteringService(this.artifactRegistry);
        this.mmpGraphs = new PrismMmpGraphService();
        this.similarityGraphs = new PrismSimilarityGraphService();
        this.predictions = new PrismPredictionService(this.artifactRegistry, Objects.requireNonNull(predictionRegistry, "predictionRegistry"));
    }

    @Override
    public synchronized PrismDatasetSummary openDataset(OpenPrismDatasetRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.path() == null) {
            throw new ChemOperationException("invalid_prism_path", "Prism dataset path must not be null.");
        }
        Path sourcePath = request.path().toAbsolutePath().normalize();
        String sessionId = normalizeId(request.datasetId() == null || request.datasetId().isBlank()
                ? generatedSessionId()
                : request.datasetId(), "datasetId");
        if (sessionRegistry.find(sessionId).isPresent()) {
            throw new ChemOperationException("duplicate_prism_session_id", "Prism session " + sessionId + " already exists.");
        }
        try {
            InMemoryPrismDataset dataset = PrismTsvSnapshotLoader.isSnapshot(sourcePath)
                    ? PrismTsvSnapshotLoader.load(sourcePath).dataset()
                    : PrismTsvDatasetLoader.load(sourcePath);
            PrismSession workspace = PrismSessionImporter.toSession(dataset, sourcePath);
            ensureAllRowSet(workspace);
            String label = request.label() == null || request.label().isBlank() ? sessionId : request.label().trim();
            ManagedPrismSession managed = sessionRegistry.register(sessionId, label, sourcePath, dataset, workspace);
            return datasetSummary(managed);
        } catch (IOException | RuntimeException e) {
            throw new ChemOperationException("invalid_prism_dataset", "Could not load Prism dataset from " + sourcePath + ".", e);
        }
    }

    @Override
    public synchronized PrismSessionSummary openPack(OpenPrismPackRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.path() == null) {
            throw new ChemOperationException("invalid_prism_pack_path", "PrismPack path must not be null.");
        }
        Path sourcePath = request.path().toAbsolutePath().normalize();
        String sessionId = normalizeId(request.sessionId() == null || request.sessionId().isBlank()
                ? generatedSessionId()
                : request.sessionId(), "sessionId");
        if (sessionRegistry.find(sessionId).isPresent()) {
            throw new ChemOperationException("duplicate_prism_session_id", "Prism session " + sessionId + " already exists.");
        }
        try {
            PrismSession workspace = PrismSession.open(sourcePath);
            ensureAllRowSet(workspace);
            String label = request.label() == null || request.label().isBlank() ? sourcePath.getFileName().toString() : request.label().trim();
            ManagedPrismSession managed = sessionRegistry.register(sessionId, label, sourcePath, null, workspace);
            return sessionSummary(managed);
        } catch (IOException | RuntimeException e) {
            throw new ChemOperationException("invalid_prism_pack", "Could not open PrismPack from " + sourcePath + ".", e);
        }
    }

    @Override
    public synchronized List<PrismDatasetSummary> listDatasets() {
        return sessionRegistry.sessions().stream().map(this::datasetSummary).toList();
    }

    @Override
    public synchronized List<PrismSessionSummary> listSessions() {
        return sessionRegistry.sessions().stream().map(this::sessionSummary).toList();
    }

    @Override
    public synchronized PrismSessionInfo getSessionInfo(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return new PrismSessionInfo(sessionSummary(session), subjectSets(session), endpoints(session), rowSetSummaries(session));
    }

    @Override
    public synchronized List<PrismColumnSummary> listColumns(String sessionId) {
        return columnSummaries(session(sessionId));
    }

    @Override
    public synchronized PrismSessionAgentDescription describeSessionForAgent(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        List<PrismColumnSummary> columns = columnSummaries(session);
        List<PrismColumnSummary> identifierColumns = columns.stream()
                .filter(column -> "identifier".equals(column.role()) || "compound_id".equals(column.semanticType()))
                .toList();
        List<PrismColumnSummary> structureColumns = columns.stream()
                .filter(column -> "chemical_structure".equals(column.semanticType()) || "primary_structure".equals(column.role()))
                .toList();
        List<PrismColumnSummary> endpointColumns = columns.stream()
                .filter(column -> column.endpointId() != null || "endpoint_value".equals(column.semanticType()))
                .toList();
        return new PrismSessionAgentDescription(
                sessionSummary(session),
                columns,
                identifierColumns,
                structureColumns,
                endpointColumns,
                rowSetSummaries(session),
                countBy(columns, PrismColumnSummary::type),
                countBy(columns, PrismColumnSummary::semanticType)
        );
    }

    @Override
    public synchronized List<PrismMoleculeListSummary> listMoleculeLists(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return session.moleculeWorkspace().lists().stream()
                .map(list -> moleculeListSummary(session, list))
                .toList();
    }

    @Override
    public synchronized PrismMoleculeListView getMoleculeList(String sessionId, String listId) {
        ManagedPrismSession session = session(sessionId);
        PrismMoleculeList list = session.moleculeWorkspace().findList(normalizeId(listId, "listId"))
                .orElseThrow(() -> new ChemOperationException(
                        "prism_molecule_list_not_found",
                        "Molecule list " + listId + " does not exist in Prism session " + session.sessionId() + "."
                ));
        return moleculeListView(session, list);
    }

    @Override
    public synchronized PrismMoleculeListSummary createMoleculeList(CreatePrismMoleculeListRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        try {
            PrismMoleculeList list = session.callAs(
                    ManagedPrismSessionChangeOrigin.MCP,
                    () -> session.moleculeWorkspace().createList(request.listId(), request.title())
            );
            return moleculeListSummary(session, list);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("invalid_prism_molecule_list", exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized PrismMoleculeListView addMolecules(AddPrismMoleculesRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.molecules().isEmpty()) {
            throw new ChemOperationException("invalid_prism_molecules", "At least one molecule is required.");
        }
        if (request.molecules().size() > 500) {
            throw new ChemOperationException("invalid_prism_molecules", "A single request may add at most 500 molecules.");
        }
        ManagedPrismSession session = session(request.sessionId());
        String listId = normalizeId(request.listId(), "listId");
        if (session.moleculeWorkspace().findList(listId).isEmpty()) {
            throw new ChemOperationException(
                    "prism_molecule_list_not_found",
                    "Molecule list " + listId + " does not exist in Prism session " + session.sessionId() + "."
            );
        }
        List<PreparedMolecule> prepared;
        try {
            prepared = request.molecules().stream().map(input -> {
                PrismMoleculeDocumentMode mode = moleculeMode(input.mode());
                OclMoleculeDocumentCodec.EncodedMolecule encoded = moleculeCodec.parse(input.structure(), mode);
                return new PreparedMolecule(input.title(), mode, encoded);
            }).toList();
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("invalid_prism_molecule", exception.getMessage(), exception);
        }
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> {
            for (PreparedMolecule molecule : prepared) {
                session.moleculeWorkspace().addDocument(
                        listId, null, molecule.title(), molecule.mode(),
                        molecule.encoded().idcode(), molecule.encoded().coordinates()
                );
            }
        });
        return getMoleculeList(session.sessionId(), listId);
    }

    @Override
    public synchronized List<PrismLiveEvaluatorSummary> listLiveEvaluators(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return session.liveContext().bindings().stream()
                .map(binding -> liveEvaluatorSummary(session, binding))
                .toList();
    }

    @Override
    public synchronized PrismLiveEvaluatorSummary configureLiveEvaluator(
            ConfigurePrismLiveEvaluatorRequest request
    ) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String bindingId = normalizeId(request.bindingId(), "bindingId");
        PrismLiveBinding previous = session.liveContext().findBinding(bindingId).orElse(null);
        String capabilityId = request.capabilityId() == null || request.capabilityId().isBlank()
                ? previous == null ? null : previous.capabilityId()
                : normalizeId(request.capabilityId(), "capabilityId");
        if (capabilityId == null) {
            throw new ChemOperationException(
                    "invalid_prism_live_evaluator",
                    "capabilityId is required when creating a live evaluator binding."
            );
        }
        PrismLiveExecutionMode mode = request.mode() == null || request.mode().isBlank()
                ? previous == null ? PrismLiveExecutionMode.MANUAL : previous.mode()
                : liveMode(request.mode());
        long quietPeriodMillis = request.quietPeriodMillis() == null
                ? previous == null ? 0L : previous.quietPeriod().toMillis()
                : request.quietPeriodMillis();
        if (quietPeriodMillis < 0) {
            throw new ChemOperationException("invalid_prism_live_evaluator", "quietPeriodMillis must not be negative.");
        }
        Map<String, Object> configuration = request.configuration() == null
                ? previous == null ? Map.of() : previous.configuration()
                : request.configuration();
        PrismLiveBinding next = new PrismLiveBinding(
                bindingId, capabilityId, mode, Duration.ofMillis(quietPeriodMillis), configuration);
        try {
            session.runAs(
                    ManagedPrismSessionChangeOrigin.MCP,
                    request.expectedWorkspaceRevision(),
                    () -> session.liveContext().configureBinding(next)
            );
            return liveEvaluatorSummary(session, next);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ChemOperationException("invalid_prism_live_evaluator", exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized List<PrismLiveEvaluationView> listLiveEvaluations(
            String sessionId,
            String documentId
    ) {
        ManagedPrismSession session = session(sessionId);
        String normalizedDocumentId = normalizeId(documentId, "documentId");
        requireMoleculeDocument(session, normalizedDocumentId);
        return session.liveContext().evaluationsFor(normalizedDocumentId).stream()
                .map(evaluation -> liveEvaluationView(session, evaluation))
                .toList();
    }

    @Override
    public synchronized PrismLiveEvaluationView runLiveEvaluator(RunPrismLiveEvaluatorRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        try {
            PrismLiveEvaluation evaluation = session.callAs(
                    ManagedPrismSessionChangeOrigin.MCP,
                    () -> session.liveContext().runNow(
                            normalizeId(request.bindingId(), "bindingId"),
                            normalizeId(request.documentId(), "documentId"),
                            request.expectedDocumentRevision())
            );
            return liveEvaluationView(session, evaluation);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ChemOperationException("invalid_prism_live_evaluation", exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized List<PrismRowSetSummary> listRowSets(String sessionId) {
        return rowSetSummaries(session(sessionId));
    }

    @Override
    public synchronized List<PrismGroupingSummary> listGroupings(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return session.workspace().groupings().stream()
                .map(grouping -> groupingSummary(session, grouping))
                .toList();
    }

    @Override
    public synchronized List<PrismGraphSummary> listGraphs(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return session.workspace().graphs().stream()
                .map(graph -> graphSummary(session, graph))
                .toList();
    }

    @Override
    public synchronized PrismGraphSummary summarizeGraph(String sessionId, String graphId) {
        ManagedPrismSession session = session(sessionId);
        return graphSummary(session, graph(session, graphId));
    }


    @Override
    public synchronized PrismGraphAnalysis analyzeGraph(String sessionId, String graphId, int limit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? 20 : limit));
        List<PrismGraphNodeStat> nodeStats = graph.rowIds().stream()
                .map(rowId -> new PrismGraphNodeStat(rowId, graph.degree(rowId)))
                .sorted((a, b) -> {
                    int byDegree = Integer.compare(b.degree(), a.degree());
                    return byDegree != 0 ? byDegree : a.rowId().compareTo(b.rowId());
                })
                .toList();
        int sourceRowCount = graph.sourceRowSetId() == null
                ? graph.rowIds().size()
                : rowSet(session, graph.sourceRowSetId()).rowIds().size();
        int isolatedSourceRowCount = graph.sourceRowSetId() == null
                ? 0
                : (int) rowSet(session, graph.sourceRowSetId()).rowIds().stream()
                        .filter(rowId -> !graph.rowIds().contains(rowId))
                        .count();
        return new PrismGraphAnalysis(
                graphSummary(session, graph),
                sourceRowCount,
                graph.rowIds().size(),
                isolatedSourceRowCount,
                degreeStats(nodeStats),
                graphSimilarityStats(graph),
                safeLimit,
                nodeStats.stream().limit(safeLimit).toList()
        );
    }

    @Override
    public synchronized PrismGraphTsvExport exportGraph(String sessionId, String graphId, String format) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        String normalized = format == null || format.isBlank() ? "edges_tsv" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "edges_tsv" -> exportGraphEdges(session, graph);
            case "nodes_tsv" -> exportGraphNodes(session, graph);
            default -> throw new ChemOperationException("unsupported_prism_graph_export_format", "format must be edges_tsv or nodes_tsv.");
        };
    }

    @Override
    public synchronized PrismGraphNeighborhood inspectGraphNeighborhood(String sessionId,
                                                                        String graphId,
                                                                        String centerRowId,
                                                                        int limit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        String rowId = normalizeId(centerRowId, "centerRowId");
        if (session.workspace().physicalRowForRowId(rowId).isEmpty()) {
            throw new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist.");
        }
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? 50 : limit));
        List<String> neighborIds = graph.neighborRowIds(rowId).stream()
                .sorted((a, b) -> Integer.compare(graph.degree(b), graph.degree(a)))
                .limit(safeLimit)
                .toList();
        List<PrismGraphNeighbor> neighbors = neighborIds.stream()
                .map(neighborId -> new PrismGraphNeighbor(
                        rowMember(session, neighborId),
                        graph.degree(neighborId),
                        edgesBetween(graph, rowId, neighborId)
                ))
                .toList();
        return new PrismGraphNeighborhood(
                graphSummary(session, graph),
                rowMember(session, rowId),
                graph.neighborRowIds(rowId).size(),
                graph.incidentEdges(rowId).size(),
                neighbors
        );
    }

    @Override
    public synchronized PrismCollapsedGraphNeighborhood inspectCollapsedGraphNeighborhood(String sessionId,
                                                                                         String graphId,
                                                                                         String centerRowId,
                                                                                         int limit,
                                                                                         int transformExampleLimit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        String rowId = normalizeId(centerRowId, "centerRowId");
        if (session.workspace().physicalRowForRowId(rowId).isEmpty()) {
            throw new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist.");
        }
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? 10 : limit));
        int safeExampleLimit = Math.min(25, Math.max(1, transformExampleLimit <= 0 ? 3 : transformExampleLimit));
        List<String> neighborIds = graph.neighborRowIds(rowId).stream()
                .sorted((a, b) -> Integer.compare(graph.degree(b), graph.degree(a)))
                .limit(safeLimit)
                .toList();
        List<PrismCollapsedGraphNeighbor> neighbors = neighborIds.stream()
                .map(neighborId -> collapsedNeighbor(session, graph, rowId, neighborId, safeExampleLimit))
                .toList();
        return new PrismCollapsedGraphNeighborhood(
                graphSummary(session, graph),
                rowMember(session, rowId),
                graph.neighborRowIds(rowId).size(),
                graph.incidentEdges(rowId).size(),
                "collapsed",
                neighbors.size(),
                neighbors
        );
    }

    @Override
    public synchronized PrismGraphShortestPath findGraphShortestPath(String sessionId,
                                                                     String graphId,
                                                                     String sourceRowId,
                                                                     String targetRowId,
                                                                     boolean includePath,
                                                                     int maxDepth,
                                                                     int transformExampleLimit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        String sourceId = normalizeId(sourceRowId, "sourceRowId");
        String targetId = normalizeId(targetRowId, "targetRowId");
        if (session.workspace().physicalRowForRowId(sourceId).isEmpty()) {
            throw new ChemOperationException("prism_row_not_found", "Prism row " + sourceId + " does not exist.");
        }
        if (session.workspace().physicalRowForRowId(targetId).isEmpty()) {
            throw new ChemOperationException("prism_row_not_found", "Prism row " + targetId + " does not exist.");
        }
        PrismRowMember source = rowMember(session, sourceId);
        PrismRowMember target = rowMember(session, targetId);
        boolean sourceInGraph = graph.rowIds().contains(sourceId);
        boolean targetInGraph = graph.rowIds().contains(targetId);
        if (!sourceInGraph || !targetInGraph) {
            String reason = !sourceInGraph && !targetInGraph
                    ? "rows_not_in_graph"
                    : (!sourceInGraph ? "source_not_in_graph" : "target_not_in_graph");
            return new PrismGraphShortestPath(graphSummary(session, graph), source, target, false, null, 0, reason, List.of(), List.of());
        }
        if (sourceId.equals(targetId)) {
            return new PrismGraphShortestPath(
                    graphSummary(session, graph),
                    source,
                    target,
                    true,
                    0,
                    0,
                    "same_row",
                    includePath ? List.of(source) : List.of(),
                    List.of());
        }

        int safeMaxDepth = Math.max(0, maxDepth);
        int safeExampleLimit = Math.min(25, Math.max(1, transformExampleLimit <= 0 ? 2 : transformExampleLimit));
        int depthLimit = safeMaxDepth == 0 ? Integer.MAX_VALUE : safeMaxDepth;
        ArrayDeque<String> queue = new ArrayDeque<>();
        Map<String, String> predecessor = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        queue.add(sourceId);
        depth.put(sourceId, 0);
        int searchedDepth = 0;
        boolean reached = false;
        boolean depthLimitReached = false;
        while (!queue.isEmpty() && !reached) {
            String current = queue.removeFirst();
            int currentDepth = depth.get(current);
            searchedDepth = Math.max(searchedDepth, currentDepth);
            if (currentDepth >= depthLimit) {
                if (safeMaxDepth > 0) {
                    depthLimitReached = true;
                }
                continue;
            }
            for (String neighbor : graph.neighborRowIds(current).stream().sorted().toList()) {
                if (depth.containsKey(neighbor)) {
                    continue;
                }
                predecessor.put(neighbor, current);
                depth.put(neighbor, currentDepth + 1);
                if (neighbor.equals(targetId)) {
                    searchedDepth = Math.max(searchedDepth, currentDepth + 1);
                    reached = true;
                    break;
                }
                queue.addLast(neighbor);
            }
        }
        if (!reached) {
            String reason = depthLimitReached ? "max_depth_exceeded" : "no_path";
            return new PrismGraphShortestPath(graphSummary(session, graph), source, target, false, null, searchedDepth, reason, List.of(), List.of());
        }

        ArrayList<String> pathIds = new ArrayList<>();
        String cursor = targetId;
        pathIds.add(cursor);
        while (!cursor.equals(sourceId)) {
            cursor = predecessor.get(cursor);
            pathIds.add(cursor);
        }
        Collections.reverse(pathIds);
        List<PrismRowMember> pathRows = includePath
                ? pathIds.stream().map(rowId -> rowMember(session, rowId)).toList()
                : List.of();
        List<PrismGraphPathStep> steps = new ArrayList<>();
        if (includePath) {
            for (int i = 0; i < pathIds.size() - 1; i++) {
                steps.add(pathStep(graph, pathIds.get(i), pathIds.get(i + 1), safeExampleLimit));
            }
        }
        return new PrismGraphShortestPath(
                graphSummary(session, graph),
                source,
                target,
                true,
                pathIds.size() - 1,
                searchedDepth,
                "connected",
                pathRows,
                steps);
    }

    @Override
    public synchronized PrismMmpTransformSummary summarizeMmpTransforms(String sessionId,
                                                                        String graphId,
                                                                        int minSupport,
                                                                        String sortBy,
                                                                        int offset,
                                                                        int limit,
                                                                        int exampleLimit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowGraph graph = graph(session, graphId);
        String normalizedSort = normalizeMmpTransformSort(sortBy);
        int safeMinSupport = Math.max(1, minSupport);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? 50 : limit));
        int safeExampleLimit = Math.min(25, Math.max(1, exampleLimit <= 0 ? 3 : exampleLimit));
        List<PrismMmpTransformSummaryRow> allRows = transformSummaryRows(graph, safeExampleLimit).stream()
                .filter(row -> row.supportCount() >= safeMinSupport)
                .sorted(transformSummaryComparator(normalizedSort))
                .toList();
        int from = Math.min(safeOffset, allRows.size());
        int to = Math.min(from + safeLimit, allRows.size());
        List<PrismMmpTransformSummaryRow> page = allRows.subList(from, to);
        return new PrismMmpTransformSummary(
                graphSummary(session, graph),
                normalizedSort,
                allRows.size(),
                page.size(),
                safeOffset,
                safeLimit,
                page
        );
    }

    @Override
    public synchronized PrismRowSetSummary createGraphNeighborhoodRowSet(CreatePrismGraphNeighborhoodRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        PrismRowGraph graph = graph(session, request.graphId());
        String centerRowId = normalizeId(request.centerRowId(), "centerRowId");
        if (session.workspace().physicalRowForRowId(centerRowId).isEmpty()) {
            throw new ChemOperationException("prism_row_not_found", "Prism row " + centerRowId + " does not exist.");
        }
        int maxDepth = request.maxDepth();
        if (maxDepth < 1) {
            throw new ChemOperationException("invalid_graph_neighborhood_depth", "max_depth must be at least 1.");
        }
        LinkedHashMap<String, Integer> distances = graphRadiusDistances(graph, centerRowId, maxDepth);
        if (!request.includeCenter()) {
            distances.remove(centerRowId);
        }
        if (distances.isEmpty()) {
            throw new ChemOperationException("empty_graph_neighborhood", "Graph neighborhood contains no rows.");
        }
        LinkedHashSet<String> rowIds = new LinkedHashSet<>(distances.keySet());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, graph.id().replaceAll("[^a-zA-Z0-9_]+", "_") + "_neighborhood")
                : request.rowSetId().trim();
        String shellGroupingId = null;
        LinkedHashMap<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("source", "prism_row_graph_neighborhood");
        provenance.put("graphId", graph.id());
        provenance.put("centerRowId", centerRowId);
        provenance.put("maxDepth", maxDepth);
        provenance.put("includeCenter", request.includeCenter());
        if (request.createShellGrouping()) {
            shellGroupingId = request.shellGroupingId() == null || request.shellGroupingId().isBlank()
                    ? rowSetId + "_shells"
                    : normalizeId(request.shellGroupingId(), "shellGroupingId");
            provenance.put("shellGroupingId", shellGroupingId);
        }
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank()
                        ? graph.title() + " neighborhood / " + centerRowId + " / depth " + maxDepth
                        : request.name().trim(),
                request.description() == null || request.description().isBlank()
                        ? "Rows within graph distance " + maxDepth + " of " + centerRowId + " in graph " + graph.id() + "."
                        : request.description().trim(),
                rowIds,
                provenance
        );
        if (shellGroupingId == null) {
            session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        } else {
            PrismGrouping grouping = graphShellGrouping(shellGroupingId, graph, rowSet, centerRowId, maxDepth, distances);
            session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().applyOperationResult(
                    PrismOperationResult.builder()
                            .addRowSet(rowSet)
                            .addGrouping(grouping, false)
                            .provenance("rowSetId", rowSet.id())
                            .provenance("shellGroupingId", grouping.id())
                            .build()));
        }
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismMmpGraphSummary mineMmpGraph(MinePrismMmpGraphRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? "all"
                : request.rowSetId().trim();
        PrismRowSet sourceRowSet = rowSet(session, rowSetId);
        return mmpGraphs.mine(session, sourceRowSet, request);
    }

    @Override
    public synchronized PrismSimilarityGraphSummary mineSimilarityGraph(MinePrismSimilarityGraphRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? "all"
                : request.rowSetId().trim();
        PrismRowSet sourceRowSet = rowSet(session, rowSetId);
        return similarityGraphs.mine(session, sourceRowSet, request);
    }


    private static PrismGraphDegreeStats degreeStats(List<PrismGraphNodeStat> nodeStats) {
        if (nodeStats.isEmpty()) {
            return new PrismGraphDegreeStats(0, 0.0, 0);
        }
        List<Integer> degrees = nodeStats.stream().map(PrismGraphNodeStat::degree).sorted().toList();
        int min = degrees.getFirst();
        int max = degrees.getLast();
        int size = degrees.size();
        double median = size % 2 == 1
                ? degrees.get(size / 2)
                : (degrees.get(size / 2 - 1) + degrees.get(size / 2)) / 2.0;
        return new PrismGraphDegreeStats(min, median, max);
    }

    private static PrismGraphSimilarityStats graphSimilarityStats(PrismRowGraph graph) {
        ArrayList<Double> similarities = new ArrayList<>();
        int mutualKnnCount = 0;
        LinkedHashMap<String, Integer> sourceCounts = new LinkedHashMap<>();
        for (PrismRowGraphEdge edge : graph.edges()) {
            Double similarity = propertyDouble(edge.properties(), "similarity");
            if (similarity == null || Double.isNaN(similarity)) {
                continue;
            }
            similarities.add(similarity);
            if (Boolean.TRUE.equals(edge.properties().get("mutualKnn"))) {
                mutualKnnCount++;
            }
            String source = propertyText(edge.properties(), "edgeSource");
            if (source != null && !source.isBlank()) {
                sourceCounts.merge(source, 1, Integer::sum);
            }
        }
        if (similarities.isEmpty()) {
            return null;
        }
        similarities.sort(Double::compareTo);
        return PrismSimilarityGraphService.similarityStats(similarities, mutualKnnCount, sourceCounts);
    }

    private static LinkedHashMap<String, Integer> graphRadiusDistances(PrismRowGraph graph, String centerRowId, int maxDepth) {
        LinkedHashMap<String, Integer> distances = new LinkedHashMap<>();
        if (!graph.rowIds().contains(centerRowId)) {
            return distances;
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        distances.put(centerRowId, 0);
        queue.add(centerRowId);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int currentDepth = distances.get(current);
            if (currentDepth >= maxDepth) {
                continue;
            }
            for (String neighbor : graph.neighborRowIds(current).stream().sorted().toList()) {
                if (distances.containsKey(neighbor)) {
                    continue;
                }
                distances.put(neighbor, currentDepth + 1);
                queue.addLast(neighbor);
            }
        }
        return distances;
    }

    private static PrismGrouping graphShellGrouping(String groupingId,
                                                    PrismRowGraph graph,
                                                    PrismRowSet rowSet,
                                                    String centerRowId,
                                                    int maxDepth,
                                                    LinkedHashMap<String, Integer> distances) {
        LinkedHashMap<Integer, List<String>> rowsByShell = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : distances.entrySet()) {
            rowsByShell.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }
        List<PrismGroup> groups = new ArrayList<>();
        List<PrismGroupMembership> memberships = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : rowsByShell.entrySet()) {
            int distance = entry.getKey();
            List<String> shellRows = entry.getValue();
            String groupId = "shell_" + distance;
            String label = distance == 1 ? "1 hop" : distance + " hops";
            groups.add(new PrismGroup(
                    groupId,
                    label,
                    "Rows at graph distance " + distance + " from " + centerRowId + " in graph " + graph.id() + ".",
                    null,
                    shellRows.getFirst(),
                    Map.of("distance", distance, "rowCount", shellRows.size())
            ));
            for (String rowId : shellRows) {
                memberships.add(new PrismGroupMembership(
                        rowId,
                        groupId,
                        1.0,
                        distance == 0 ? "center" : "member",
                        Map.of("distance", distance)
                ));
            }
        }
        return new PrismGrouping(
                groupingId,
                rowSet.name() + " shells",
                "Exclusive graph-distance shells for row set " + rowSet.id() + ".",
                rowSet.id(),
                PrismGroupingMode.EXCLUSIVE,
                groups,
                memberships,
                groupingId + ".shell",
                Map.of(
                        "source", "prism_row_graph_neighborhood_shells",
                        "graphId", graph.id(),
                        "centerRowId", centerRowId,
                        "maxDepth", maxDepth,
                        "rowSetId", rowSet.id())
        );
    }

    private static PrismGraphTsvExport exportGraphEdges(ManagedPrismSession session, PrismRowGraph graph) {
        StringBuilder builder = new StringBuilder();
        builder.append("edge_id	source_row_id	target_row_id	label	relation_type	similarity	edge_source	descriptor	rank_a_to_b	rank_b_to_a	mutual_knn	transform_id	transform_text	key_fragment	from_fragment	to_fragment	cut_count	delta	value_a	value_b	key_idcode	from_value_idcode	to_value_idcode\n");
        for (PrismRowGraphEdge edge : graph.edges()) {
            Map<String, Object> properties = edge.properties();
            PrismMmpTransformText transform = PrismMmpTransformRenderer.render(properties);
            appendTsvCells(builder,
                    edge.id(),
                    edge.sourceRowId(),
                    edge.targetRowId(),
                    edge.label(),
                    propertyText(properties, "relationType"),
                    propertyText(properties, "similarity"),
                    propertyText(properties, "edgeSource"),
                    propertyText(properties, "descriptor"),
                    propertyText(properties, "rankAtoB"),
                    propertyText(properties, "rankBtoA"),
                    propertyText(properties, "mutualKnn"),
                    propertyText(properties, "transformId"),
                    transform.transformText(),
                    transform.keyFragment(),
                    transform.fromFragment(),
                    transform.toFragment(),
                    propertyText(properties, "cutCount"),
                    propertyText(properties, "delta"),
                    propertyText(properties, "valueA"),
                    propertyText(properties, "valueB"),
                    propertyText(properties, "keyIdcode"),
                    propertyText(properties, "fromValueIdcode"),
                    propertyText(properties, "toValueIdcode"));
        }
        return new PrismGraphTsvExport(PrismMmpGraphService.graphSummary(session, graph), "edges_tsv", graph.edges().size(), builder.toString());
    }

    private static PrismGraphTsvExport exportGraphNodes(ManagedPrismSession session, PrismRowGraph graph) {
        StringBuilder builder = new StringBuilder();
        builder.append("row_id\tdegree\n");
        List<String> rowIds = graph.rowIds().stream().sorted().toList();
        for (String rowId : rowIds) {
            appendTsvCells(builder, rowId, Integer.toString(graph.degree(rowId)));
        }
        return new PrismGraphTsvExport(PrismMmpGraphService.graphSummary(session, graph), "nodes_tsv", rowIds.size(), builder.toString());
    }

    private static String propertyText(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        return value == null ? "" : value.toString();
    }

    private static void appendTsvCells(StringBuilder builder, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append('\t');
            }
            builder.append(tsvCell(values[i]));
        }
        builder.append('\n');
    }

    private static String tsvCell(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    @Override
    public synchronized PrismGroupingView getGrouping(String sessionId,
                                                       String groupingId,
                                                       int offset,
                                                       int limit) {
        ManagedPrismSession session = session(sessionId);
        PrismGrouping grouping = grouping(session, groupingId);
        int safeOffset = Math.min(Math.max(0, offset), grouping.groups().size());
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        int to = Math.min(safeOffset + safeLimit, grouping.groups().size());
        List<PrismGroupSummary> groups = grouping.groups().subList(safeOffset, to).stream()
                .map(group -> new PrismGroupSummary(
                        group.id(),
                        group.label(),
                        group.description(),
                        group.parentGroupId(),
                        group.representativeRowId(),
                        grouping.rowsInGroup(group.id()).size(),
                        group.metadata()
                ))
                .toList();
        return new PrismGroupingView(
                groupingSummary(session, grouping),
                grouping.groups().size(),
                safeOffset,
                safeLimit,
                groups
        );
    }

    @Override
    public synchronized PrismRowSetSummary createGroupRowSet(CreatePrismGroupRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        PrismGrouping grouping = grouping(session, request.groupingId());
        String groupId = normalizeId(request.groupId(), "groupId");
        PrismGroup group;
        try {
            group = grouping.group(groupId);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException(
                    "prism_group_not_found",
                    "Prism group " + groupId + " does not exist in grouping " + grouping.id() + ".",
                    exception
            );
        }
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, grouping.id() + "_" + group.id())
                : request.rowSetId().trim();
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank()
                        ? grouping.title() + " / " + group.label()
                        : request.name().trim(),
                request.description() == null || request.description().isBlank()
                        ? "Rows in " + group.label() + " from Prism grouping " + grouping.id() + "."
                        : request.description().trim(),
                grouping.rowsInGroup(group.id()),
                Map.of(
                        "source", "prism_grouping",
                        "groupingId", grouping.id(),
                        "groupId", group.id()
                )
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismRowSetColumnSummary summarizeRowSetByColumns(String sessionId,
                                                                           String rowSetId,
                                                                           List<String> columnIds,
                                                                           Double threshold,
                                                                           String thresholdDirection,
                                                                           int topValuesLimit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowSet rowSet = rowSet(session, rowSetId);
        List<PrismColumn> columns = summaryColumns(session, columnIds);
        List<Integer> physicalRows = physicalRows(session, rowSet.rowIds());
        List<PrismRuntimeColumnValueSummary> summaries = columns.stream()
                .map(column -> summarizeColumn(column, physicalRows, threshold, thresholdDirection, topValuesLimit))
                .toList();
        return new PrismRowSetColumnSummary(
                rowSetSummary(session, rowSet),
                columns.stream().map(PrismColumn::id).toList(),
                summaries
        );
    }

    @Override
    public synchronized PrismGroupingColumnSummary summarizeGroupingByColumns(String sessionId,
                                                                              String groupingId,
                                                                              List<String> columnIds,
                                                                              boolean includeSingletons,
                                                                              int offset,
                                                                              int limit,
                                                                              Double threshold,
                                                                              String thresholdDirection,
                                                                              int topValuesLimit) {
        ManagedPrismSession session = session(sessionId);
        PrismGrouping grouping = grouping(session, groupingId);
        List<PrismColumn> columns = summaryColumns(session, columnIds);
        List<PrismGroup> filtered = grouping.groups().stream()
                .filter(group -> includeSingletons || grouping.rowsInGroup(group.id()).size() > 1)
                .sorted((a, b) -> Integer.compare(grouping.rowsInGroup(b.id()).size(), grouping.rowsInGroup(a.id()).size()))
                .toList();
        int safeOffset = Math.min(Math.max(0, offset), filtered.size());
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? 50 : limit));
        int to = Math.min(safeOffset + safeLimit, filtered.size());
        List<PrismGroupColumnSummaryRow> groups = filtered.subList(safeOffset, to).stream()
                .map(group -> {
                    List<Integer> physicalRows = physicalRows(session, grouping.rowsInGroup(group.id()));
                    return new PrismGroupColumnSummaryRow(
                            group.id(),
                            group.label(),
                            group.description(),
                            group.parentGroupId(),
                            group.representativeRowId(),
                            physicalRows.size(),
                            group.metadata(),
                            columns.stream()
                                    .map(column -> summarizeColumn(column, physicalRows, threshold, thresholdDirection, topValuesLimit))
                                    .toList()
                    );
                })
                .toList();
        return new PrismGroupingColumnSummary(
                groupingSummary(session, grouping),
                columns.stream().map(PrismColumn::id).toList(),
                includeSingletons,
                filtered.size(),
                groups.size(),
                safeOffset,
                safeLimit,
                groups
        );
    }

    @Override
    public synchronized PrismRowSetMembersView getRowSetMembers(String sessionId, String rowSetId, int offset, int limit) {
        ManagedPrismSession session = session(sessionId);
        PrismRowSet rowSet = rowSet(session, rowSetId);
        List<String> rowIds = List.copyOf(rowSet.rowIds());
        int safeOffset = Math.min(Math.max(0, offset), rowIds.size());
        int safeLimit = Math.min(PAGE_LIMIT_MAX, Math.max(1, limit <= 0 ? PAGE_LIMIT_DEFAULT : limit));
        int to = Math.min(safeOffset + safeLimit, rowIds.size());
        List<PrismRowMember> members = rowIds.subList(safeOffset, to).stream()
                .map(rowId -> rowMember(session, rowId))
                .toList();
        return new PrismRowSetMembersView(rowSetSummary(session, rowSet), safeOffset, safeLimit, members);
    }


    @Override
    public synchronized PrismRowSetColumnSummary summarizeRowsByColumns(String sessionId,
                                                                         List<String> rowIds,
                                                                         List<String> columnIds,
                                                                         Double threshold,
                                                                         String thresholdDirection,
                                                                         int topValuesLimit) {
        ManagedPrismSession session = session(sessionId);
        List<String> normalizedRowIds = rowIds == null ? List.of() : rowIds.stream()
                .map(rowId -> normalizeId(rowId, "rowId"))
                .toList();
        List<PrismColumn> columns = summaryColumns(session, columnIds);
        List<Integer> physicalRows = physicalRows(session, normalizedRowIds);
        PrismRowSetSummary summary = new PrismRowSetSummary(
                session.sessionId(),
                "ad_hoc_rows",
                "Ad hoc rows",
                "Transient row subset used for server-side column summaries.",
                normalizedRowIds.size(),
                Map.of("source", "ad_hoc_rows")
        );
        return new PrismRowSetColumnSummary(
                summary,
                columns.stream().map(PrismColumn::id).toList(),
                columns.stream()
                        .map(column -> summarizeColumn(column, physicalRows, threshold, thresholdDirection, topValuesLimit))
                        .toList()
        );
    }

    @Override
    public synchronized PrismRowSetSummary createRowSetFromRows(CreatePrismRowSetFromRowsRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        for (String rowId : request.rowIds()) {
            String normalized = normalizeId(rowId, "rowId");
            if (session.workspace().physicalRowForRowId(normalized).isEmpty()) {
                throw new ChemOperationException("prism_row_not_found", "Prism row " + normalized + " does not exist.");
            }
            rowIds.add(normalized);
        }
        if (rowIds.isEmpty()) {
            throw new ChemOperationException("empty_prism_row_set", "Cannot create a Prism row set from zero rows.");
        }
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, "mcp_rows")
                : request.rowSetId().trim();
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank() ? rowSetId : request.name().trim(),
                request.description() == null ? "" : request.description().trim(),
                rowIds,
                request.provenance()
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismRowSetSummary createRowSetFromSubjectSet(CreatePrismRowSetFromSubjectSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        InMemoryPrismDataset dataContext = dataContext(session);
        String subjectSetId = normalizeId(request.subjectSetId(), "subjectSetId");
        SubjectSet subjectSet = dataContext.findSubjectSet(subjectSetId)
                .orElseThrow(() -> new ChemOperationException("prism_subject_set_not_found", "Prism subject set " + subjectSetId + " does not exist."));
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank() ? subjectSetId : request.rowSetId().trim();
        if (request.rowSetId() == null || request.rowSetId().isBlank()) {
            for (PrismRowSet existing : session.workspace().rowSets()) {
                if (existing.id().equals(rowSetId)) {
                    return rowSetSummary(session, existing);
                }
            }
        }
        LinkedHashSet<String> rowIds = new LinkedHashSet<>(dataContext.getSubjectsForSet(subjectSetId));
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank() ? subjectSet.getName() : request.name(),
                request.description() == null ? subjectSet.getDescription() : request.description(),
                rowIds,
                Map.of("source", "prism_subject_set", "subjectSetId", subjectSetId)
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismRowSetSummary createEndpointRowSet(CreatePrismEndpointRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        InMemoryPrismDataset dataContext = dataContext(session);
        String endpointId = normalizeId(request.endpointId(), "endpointId");
        if (dataContext.findEndpointDefinition(endpointId).isEmpty()) {
            throw new ChemOperationException("prism_endpoint_not_found", "Prism endpoint " + endpointId + " does not exist.");
        }
        boolean hasNumeric = request.operator() != null || request.value() != null;
        if (hasNumeric && (request.operator() == null || request.value() == null)) {
            throw new ChemOperationException("invalid_endpoint_filter", "operator and value must be supplied together for numeric endpoint filtering.");
        }
        MeasurementDateFilter dateFilter = measurementDateFilter(
                request.measurementDateField(),
                request.measuredAfter(),
                request.measuredBefore(),
                request.requireMeasuredDate());
        if (!hasNumeric && !dateFilter.hasBounds()) {
            throw new ChemOperationException("invalid_endpoint_filter", "Endpoint row-set creation requires operator/value or measured_after/measured_before.");
        }
        String operator = hasNumeric ? normalizeEndpointFilterOperator(request.operator()) : null;
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        for (EndpointValueRecord value : dataContext.getEndpointValues()) {
            if (!endpointId.equals(value.getEndpointId())) {
                continue;
            }
            EndpointResult result = value.getResult();
            if (hasNumeric && !numericEndpointFilterMatches(result, operator, request.value())) {
                continue;
            }
            if (!dateFilter.matches(result)) {
                continue;
            }
            if (session.workspace().physicalRowForRowId(value.getSubjectId()).isPresent()) {
                rowIds.add(value.getSubjectId());
            }
        }
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, "endpoint_filter")
                : request.rowSetId().trim();
        String name = request.name() == null || request.name().isBlank()
                ? endpointSourceText(endpointId, operator, request.value(), dateFilter)
                : request.name().trim();
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                name,
                "Rows matching " + endpointSourceText(endpointId, operator, request.value(), dateFilter),
                rowIds,
                Map.of("source", "endpoint_filter", "endpointId", endpointId)
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismRowSetSummary createColumnRowSet(CreatePrismColumnRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String columnId = normalizeId(request.columnId(), "columnId");
        PrismColumn column = session.workspace().table().findColumn(columnId)
                .orElseThrow(() -> new ChemOperationException(
                        "prism_column_not_found",
                        "Prism column " + columnId + " does not exist."
                ));
        PrismFilter filter = columnFilter(request, column);
        BitSet matches;
        try {
            matches = filter.evaluate(
                    session.workspace().table(),
                    new PrismEvaluationContext(session.workspace().viewState())
            );
        } catch (RuntimeException exception) {
            throw new ChemOperationException(
                    "invalid_prism_column_filter",
                    "Could not evaluate filter for Prism column " + columnId + ": " + exception.getMessage(),
                    exception
            );
        }

        String baseRowSetId = request.baseRowSetId() == null || request.baseRowSetId().isBlank()
                ? "all"
                : request.baseRowSetId().trim();
        PrismRowSet baseRowSet = rowSet(session, baseRowSetId);
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        for (int physicalRow = matches.nextSetBit(0);
             physicalRow >= 0;
             physicalRow = matches.nextSetBit(physicalRow + 1)) {
            String rowId = session.workspace().rowIdForPhysicalRow(physicalRow);
            if (baseRowSet.rowIds().contains(rowId)) {
                rowIds.add(rowId);
            }
        }

        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, "column_filter")
                : request.rowSetId().trim();
        String sourceText = columnFilterSourceText(request, columnId);
        PrismRowSet created = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank() ? sourceText : request.name().trim(),
                request.description() == null || request.description().isBlank()
                        ? "Rows from " + baseRowSetId + " matching " + sourceText + "."
                        : request.description().trim(),
                rowIds,
                Map.of(
                        "source", "prism_column_filter",
                        "baseRowSetId", baseRowSetId,
                        "columnId", columnId,
                        "filterType", normalizeColumnFilterType(request.filterType())
                )
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(created));
        return rowSetSummary(session, created);
    }

    @Override
    public synchronized PrismRowSetSummary combineRowSets(CombinePrismRowSetsRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String operation = normalizeRowSetOperation(request.operation());
        if (request.rowSetIds().size() < 2) {
            throw new ChemOperationException("invalid_row_set_combination", "At least two row_set_ids are required.");
        }
        List<PrismRowSet> sources = request.rowSetIds().stream().map(id -> rowSet(session, id)).toList();
        LinkedHashSet<String> combined = new LinkedHashSet<>(sources.getFirst().rowIds());
        switch (operation) {
            case "union" -> {
                for (PrismRowSet source : sources.subList(1, sources.size())) {
                    combined.addAll(source.rowIds());
                }
            }
            case "intersect" -> {
                for (PrismRowSet source : sources.subList(1, sources.size())) {
                    combined.retainAll(source.rowIds());
                }
            }
            case "subtract" -> {
                for (PrismRowSet source : sources.subList(1, sources.size())) {
                    combined.removeAll(source.rowIds());
                }
            }
            default -> throw new IllegalStateException("Unsupported row-set operation: " + operation);
        }
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, "rowset_" + operation)
                : request.rowSetId().trim();
        PrismRowSet rowSet = new PrismRowSet(
                rowSetId,
                request.name() == null || request.name().isBlank() ? operation + " row set" : request.name(),
                request.description() == null ? "" : request.description(),
                combined,
                Map.of("source", "row_set_" + operation, "rowSetIds", request.rowSetIds())
        );
        session.runAs(ManagedPrismSessionChangeOrigin.MCP, () -> session.workspace().addRowSet(rowSet));
        return rowSetSummary(session, rowSet);
    }

    @Override
    public synchronized PrismRowSetStructureCollection rowSetStructures(String sessionId, String rowSetId) {
        ManagedPrismSession session = session(sessionId);
        PrismRowSet rowSet = rowSet(session, rowSetId);
        ArrayList<PrismRowStructureEntry> structures = new ArrayList<>();
        int skipped = 0;
        for (String rowId : rowSet.rowIds()) {
            PrismRowMember member = rowMember(session, rowId);
            if (member.smiles() == null || member.smiles().isBlank()) {
                skipped++;
                continue;
            }
            structures.add(new PrismRowStructureEntry(
                    member.rowId(),
                    member.subjectId(),
                    member.structureId(),
                    member.subjectId(),
                    member.smiles(),
                    member.fields()
            ));
        }
        return new PrismRowSetStructureCollection(
                session.sessionId(),
                rowSet.id(),
                session.revision(),
                rowSet.rowIds().size(),
                structures.size(),
                skipped,
                structures
        );
    }

    @Override
    public synchronized PrismClusteringSummary clusterRowSet(ClusterPrismRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? "all"
                : request.rowSetId().trim();
        PrismRowSet sourceRowSet = rowSet(session, rowSetId);
        PrismRowSetStructureCollection structures = rowSetStructures(session.sessionId(), sourceRowSet.id());
        return clustering.cluster(session, sourceRowSet, structures, request);
    }

    @Override
    public synchronized List<PrismAnalysisSummary> listAnalyses(String sessionId) {
        ManagedPrismSession session = session(sessionId);
        return artifactRegistry.summaries(session.sessionId());
    }

    @Override
    public synchronized List<PredictionCapability> listPredictionCapabilities(String sessionId, String endpointId) {
        ManagedPrismSession session = session(sessionId);
        return predictions.listCapabilities(session, endpoints(session), endpointId);
    }

    @Override
    public synchronized PredictionCapability describePredictionCapability(String sessionId, String capabilityId) {
        ManagedPrismSession session = session(sessionId);
        return predictions.describeCapability(session, endpoints(session), capabilityId);
    }

    @Override
    public synchronized PredictionRunSummary evaluatePrismPrediction(EvaluatePrismPredictionRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? "all"
                : request.rowSetId().trim();
        PrismRowSet sourceRowSet = rowSet(session, rowSetId);
        PrismRowSetStructureCollection structures = rowSetStructures(session.sessionId(), sourceRowSet.id());
        return predictions.evaluate(session, endpoints(session), sourceRowSet, structures, request);
    }


    @Override
    public synchronized PredictionRunView getPredictionRun(String sessionId, String predictionRunId, int offset, int limit) {
        return predictions.getRun(session(sessionId), predictionRunId, offset, limit);
    }

    @Override
    public synchronized PrismClusteringView getClustering(String sessionId,
                                                          String analysisId,
                                                          boolean includeSingletons,
                                                          int offset,
                                                          int limit) {
        return clustering.getClustering(session(sessionId), analysisId, includeSingletons, offset, limit);
    }

    @Override
    public synchronized PrismClusterMembersView getClusterMembers(String sessionId,
                                                                  String analysisId,
                                                                  String clusterId,
                                                                  int offset,
                                                                  int limit) {
        return clustering.getClusterMembers(session(sessionId), analysisId, clusterId, offset, limit);
    }

    @Override
    public synchronized PrismRowSetSummary createClusterRowSet(CreatePrismClusterRowSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession session = session(request.sessionId());
        String rowSetId = request.rowSetId() == null || request.rowSetId().isBlank()
                ? generatedRowSetId(session, request.analysisId() + "_" + request.clusterId())
                : request.rowSetId().trim();
        return clustering.createClusterRowSet(session, request, rowSetId);
    }

    @Override
    public synchronized PrismDatasetInfo getDatasetInfo(String datasetId) {
        ManagedPrismSession loaded = session(datasetId);
        return new PrismDatasetInfo(datasetSummary(loaded), subjectSets(loaded), endpoints(loaded));
    }

    @Override
    public synchronized List<PrismSubjectSetSummary> listSubjectSets(String datasetId) {
        return subjectSets(session(datasetId));
    }

    @Override
    public synchronized List<PrismSubjectSummary> listSubjects(String datasetId, String subjectSetId, int offset, int limit, boolean includeMetadata) {
        if (offset < 0 || limit < 1) {
            throw new ChemOperationException("invalid_arguments", "offset must be >= 0 and limit must be >= 1.");
        }
        ManagedPrismSession loaded = session(datasetId);
        List<SubjectRecord> subjects = subjects(loaded, subjectSetId);
        int from = Math.min(offset, subjects.size());
        int to = Math.min(from + limit, subjects.size());
        return subjects.subList(from, to).stream()
                .map(subject -> subjectSummary(subject, includeMetadata))
                .toList();
    }

    @Override
    public synchronized PrismSubjectSummary getSubject(String datasetId, String subjectId) {
        ManagedPrismSession loaded = session(datasetId);
        SubjectRecord subject = dataContext(loaded).findSubjectRecord(normalizeId(subjectId, "subjectId"))
                .orElseThrow(() -> new ChemOperationException("prism_subject_not_found", "Prism subject " + subjectId + " does not exist."));
        return subjectSummary(subject, true);
    }

    @Override
    public synchronized List<PrismEndpointSummary> listEndpoints(String datasetId) {
        return endpoints(session(datasetId));
    }

    @Override
    public synchronized List<PrismEndpointValue> getEndpointValues(String datasetId, List<String> subjectIds, List<String> endpointIds) {
        ManagedPrismSession loaded = session(datasetId);
        if (subjectIds == null || subjectIds.isEmpty()) {
            throw new ChemOperationException("invalid_arguments", "subjectIds must not be empty.");
        }
        if (endpointIds == null || endpointIds.isEmpty()) {
            throw new ChemOperationException("invalid_arguments", "endpointIds must not be empty.");
        }
        List<PrismEndpointValue> result = new ArrayList<>();
        for (String subjectId : subjectIds) {
            String normalizedSubjectId = normalizeId(subjectId, "subjectId");
            if (dataContext(loaded).findSubjectRecord(normalizedSubjectId).isEmpty()) {
                throw new ChemOperationException("prism_subject_not_found", "Prism subject " + normalizedSubjectId + " does not exist.");
            }
            for (String endpointId : endpointIds) {
                String normalizedEndpointId = normalizeId(endpointId, "endpointId");
                if (dataContext(loaded).findEndpointDefinition(normalizedEndpointId).isEmpty()) {
                    throw new ChemOperationException("prism_endpoint_not_found", "Prism endpoint " + normalizedEndpointId + " does not exist.");
                }
                dataContext(loaded).findEndpointValue(normalizedSubjectId, normalizedEndpointId)
                        .map(value -> new PrismEndpointValue(value.getSubjectId(), value.getEndpointId(), value.getResult()))
                        .ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized MaterializePrismSubjectSetResult materializeSubjectSet(MaterializePrismSubjectSetRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedPrismSession loaded = session(request.datasetId());
        String subjectSetId = request.subjectSetId() == null || request.subjectSetId().isBlank() ? null : request.subjectSetId().trim();
        if (subjectSetId != null && dataContext(loaded).findSubjectSet(subjectSetId).isEmpty()) {
            throw new ChemOperationException("prism_subject_set_not_found", "Prism subject set " + subjectSetId + " does not exist.");
        }
        List<SubjectRecord> subjects = subjects(loaded, subjectSetId);
        String repositoryId = request.repositoryId() == null || request.repositoryId().isBlank()
                ? defaultRepositoryId(loaded.sessionId(), subjectSetId)
                : request.repositoryId().trim();
        String label = request.label() == null || request.label().isBlank()
                ? defaultRepositoryLabel(loaded, subjectSetId)
                : request.label().trim();

        repositories.createRepository(new CreateRepositoryRequest(repositoryId, label, prismRepositoryDescription(loaded, subjectSetId), true));

        int missingSmiles = 0;
        int invalidSmiles = 0;
        int structuresImported = 0;
        List<PrismSkippedSubject> skipped = new ArrayList<>();
        for (SubjectRecord subject : subjects) {
            if (subject.getSmiles() == null || subject.getSmiles().isBlank()) {
                missingSmiles++;
                skipped.add(new PrismSkippedSubject(subject.getSubjectId(), "missing_smiles", "Subject has no SMILES."));
                continue;
            }
            try {
                repositories.registerStructure(new RegisterStructureRequest(
                        subject.getSmiles(),
                        repositoryId,
                        subject.getSubjectId(),
                        subject.getSubjectId(),
                        subjectFields(loaded, subjectSetId, subject)
                ));
                structuresImported++;
            } catch (RuntimeException e) {
                invalidSmiles++;
                skipped.add(new PrismSkippedSubject(subject.getSubjectId(), "invalid_smiles", e.getMessage()));
            }
        }
        materializationsByRepositoryId.put(repositoryId, new MaterializationMapping(loaded.sessionId(), subjectSetId));
        return new MaterializePrismSubjectSetResult(
                loaded.sessionId(),
                subjectSetId,
                repositoryId,
                subjects.size(),
                structuresImported,
                missingSmiles,
                invalidSmiles,
                List.copyOf(skipped)
        );
    }

    private PrismDatasetSummary datasetSummary(ManagedPrismSession loaded) {
        InMemoryPrismDataset dataContext = loaded.dataContext().orElse(null);
        return new PrismDatasetSummary(
                loaded.sessionId(),
                loaded.sessionId(),
                loaded.label(),
                loaded.sourcePath().toString(),
                dataContext == null ? loaded.workspace().totalRowCount() : dataContext.getSubjectRecords().size(),
                dataContext == null ? 0 : dataContext.getSubjectSets().size(),
                endpoints(loaded).size(),
                dataContext == null ? 0 : dataContext.getEndpointValues().size(),
                dataContext == null ? structureRowCount(loaded) : structureSubjectCount(dataContext)
        );
    }

    private PrismSessionSummary sessionSummary(ManagedPrismSession session) {
        InMemoryPrismDataset dataContext = session.dataContext().orElse(null);
        return new PrismSessionSummary(
                session.sessionId(),
                session.sessionId(),
                session.label(),
                session.sourcePath().toString(),
                session.workspace().totalRowCount(),
                session.workspace().visibleRowCount(),
                session.workspace().visibleColumnCount(),
                session.workspace().rowSets().size(),
                endpoints(session).size(),
                dataContext == null ? 0 : dataContext.getEndpointValues().size(),
                session.revision()
        );
    }

    private List<PrismSubjectSetSummary> subjectSets(ManagedPrismSession loaded) {
        InMemoryPrismDataset dataContext = loaded.dataContext().orElse(null);
        if (dataContext == null) {
            return List.of();
        }
        return dataContext.getSubjectSets().stream()
                .map(set -> new PrismSubjectSetSummary(
                        set.getId(),
                        set.getName(),
                        set.getSetType(),
                        set.getSubjectSetScope(),
                        set.getParentSetId(),
                        set.getDescription(),
                        dataContext.getSubjectsForSet(set.getId()).size()
                ))
                .toList();
    }

    private List<PrismEndpointSummary> endpoints(ManagedPrismSession loaded) {
        InMemoryPrismDataset dataContext = loaded.dataContext().orElse(null);
        if (dataContext != null) {
            return dataContext.getEndpointDefinitions().stream()
                    .map(this::endpointSummary)
                    .toList();
        }
        return loaded.workspace().table().columns().stream()
                .filter(column -> column.schema().endpointId() != null || "endpoint_value".equals(column.schema().semanticType()))
                .map(column -> endpointSummary(column))
                .toList();
    }

    private PrismEndpointSummary endpointSummary(EndpointDefinition endpoint) {
        NumericEndpointMeta numeric = endpoint.getNumericMeta();
        return new PrismEndpointSummary(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getPath(),
                endpoint.getDatatype().name(),
                endpoint.getEndpointType().name(),
                endpoint.getUnit(),
                endpoint.getEvaluationMode().name(),
                endpoint.getDescription(),
                numeric == null || numeric.getScale() == null ? null : numeric.getScale().name(),
                numeric == null ? null : numeric.getDomainLowerBound(),
                numeric == null ? null : numeric.getDomainUpperBound(),
                endpoint.getCategories().stream().map(CategoryDefinition::getId).toList()
        );
    }

    private PrismEndpointSummary endpointSummary(PrismColumn column) {
        PrismColumnSchema schema = column.schema();
        String id = schema.endpointId() == null || schema.endpointId().isBlank() ? schema.id() : schema.endpointId();
        return new PrismEndpointSummary(
                id,
                schema.displayName(),
                schema.raw().getOrDefault("endpointPath", schema.id()).toString(),
                schema.type().name(),
                "SESSION_COLUMN",
                schema.unit(),
                "",
                "Endpoint-like PrismPack column " + schema.id(),
                null,
                null,
                null,
                List.of()
        );
    }

    private List<PrismColumnSummary> columnSummaries(ManagedPrismSession session) {
        return session.workspace().table().columns().stream()
                .map(column -> columnSummary(session, column))
                .toList();
    }

    private PrismColumnSummary columnSummary(ManagedPrismSession session, PrismColumn column) {
        PrismColumnSchema schema = column.schema();
        long missing = 0;
        for (int row = 0; row < column.rowCount(); row++) {
            if (column.isMissing(row)) {
                missing++;
            }
        }
        return new PrismColumnSummary(
                session.sessionId(),
                schema.id(),
                schema.displayName(),
                schema.type().name(),
                schema.semanticType(),
                schema.role(),
                schema.unit(),
                schema.endpointId(),
                schema.direction(),
                schema.structureFormat(),
                missing,
                column.rowCount() - missing,
                schema.raw()
        );
    }

    private static Map<String, Integer> countBy(List<PrismColumnSummary> columns, Function<PrismColumnSummary, String> classifier) {
        return columns.stream()
                .map(classifier)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toMap(value -> value, value -> 1, Integer::sum, LinkedHashMap::new));
    }

    private List<PrismRowSetSummary> rowSetSummaries(ManagedPrismSession session) {
        return session.workspace().rowSets().stream().map(rowSet -> rowSetSummary(session, rowSet)).toList();
    }

    private PrismRowSetSummary rowSetSummary(ManagedPrismSession session, PrismRowSet rowSet) {
        return new PrismRowSetSummary(
                session.sessionId(),
                rowSet.id(),
                rowSet.name(),
                rowSet.description(),
                rowSet.rowIds().size(),
                rowSet.provenance()
        );
    }

    private PrismGroupingSummary groupingSummary(ManagedPrismSession session, PrismGrouping grouping) {
        return new PrismGroupingSummary(
                session.sessionId(),
                grouping.id(),
                grouping.title(),
                grouping.description(),
                grouping.mode().name(),
                grouping.sourceRowSetId(),
                grouping.facetColumnId(),
                grouping.groups().size(),
                grouping.memberships().size(),
                grouping.provenance()
        );
    }

    private PrismMoleculeListSummary moleculeListSummary(ManagedPrismSession session, PrismMoleculeList list) {
        return new PrismMoleculeListSummary(
                session.sessionId(),
                list.id(),
                list.title(),
                list.documents().size()
        );
    }

    private PrismMoleculeListView moleculeListView(ManagedPrismSession session, PrismMoleculeList list) {
        List<PrismMoleculeDocumentSummary> documents = list.documents().stream()
                .map(this::moleculeDocumentSummary)
                .toList();
        return new PrismMoleculeListView(
                moleculeListSummary(session, list),
                session.moleculeWorkspace().revision(),
                documents
        );
    }

    private PrismMoleculeDocumentSummary moleculeDocumentSummary(PrismMoleculeDocument document) {
        return new PrismMoleculeDocumentSummary(
                document.id(),
                document.title(),
                document.mode().name().toLowerCase(Locale.ROOT),
                moleculeCodec.interchange(document),
                document.revision()
        );
    }

    private PrismLiveEvaluatorSummary liveEvaluatorSummary(
            ManagedPrismSession session,
            PrismLiveBinding binding
    ) {
        var capability = session.liveContext().findCapability(binding.capabilityId())
                .orElseThrow(() -> new IllegalStateException(
                        "Live capability " + binding.capabilityId() + " is not registered."));
        return new PrismLiveEvaluatorSummary(
                session.sessionId(), session.revision(), binding.id(), capability.id(),
                capability.displayName(), capability.description(),
                binding.mode().name().toLowerCase(Locale.ROOT),
                binding.quietPeriod().toMillis(), binding.configuration()
        );
    }

    private PrismLiveEvaluationView liveEvaluationView(
            ManagedPrismSession session,
            PrismLiveEvaluation evaluation
    ) {
        PrismLiveBinding binding = session.liveContext().findBinding(evaluation.bindingId())
                .orElseThrow(() -> new IllegalStateException(
                        "Live binding " + evaluation.bindingId() + " is not registered."));
        PrismLiveSuccessfulResult successful = evaluation.lastSuccessful();
        return new PrismLiveEvaluationView(
                session.sessionId(),
                session.liveContext().sequence(),
                evaluation.bindingId(),
                binding.capabilityId(),
                evaluation.resourceId(),
                evaluation.targetRevision(),
                evaluation.status().name().toLowerCase(Locale.ROOT),
                evaluation.updatedAt().toString(),
                successful == null ? null : successful.inputRevision(),
                successful == null ? null : successful.completedAt().toString(),
                evaluation.showingStaleResult(),
                successful == null ? null : successful.result().schemaId(),
                successful == null ? Map.of() : successful.result().values(),
                successful == null ? List.of() : successful.result().warnings(),
                successful == null ? Map.of() : successful.result().metadata(),
                evaluation.error()
        );
    }

    private static PrismLiveExecutionMode liveMode(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> PrismLiveExecutionMode.AUTO;
            case "manual" -> PrismLiveExecutionMode.MANUAL;
            case "disabled" -> PrismLiveExecutionMode.DISABLED;
            default -> throw new ChemOperationException(
                    "invalid_prism_live_evaluator", "mode must be auto, manual, or disabled.");
        };
    }

    private static PrismMoleculeDocument requireMoleculeDocument(
            ManagedPrismSession session,
            String documentId
    ) {
        return session.moleculeWorkspace().findDocument(documentId)
                .orElseThrow(() -> new ChemOperationException(
                        "prism_molecule_document_not_found",
                        "Molecule document " + documentId + " does not exist in Prism session "
                                + session.sessionId() + "."
                ));
    }

    private static PrismMoleculeDocumentMode moleculeMode(String value) {
        String normalized = value == null || value.isBlank() ? "molecule" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "molecule" -> PrismMoleculeDocumentMode.MOLECULE;
            case "fragment" -> PrismMoleculeDocumentMode.FRAGMENT;
            default -> throw new IllegalArgumentException("molecule mode must be molecule or fragment");
        };
    }

    private PrismRowMember rowMember(ManagedPrismSession session, String rowId) {
        int physicalRow = session.workspace().physicalRowForRowId(rowId)
                .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist."));
        SubjectRecord subject = session.dataContext()
                .flatMap(dataContext -> dataContext.findSubjectRecord(rowId))
                .orElse(null);
        LinkedHashMap<String, String> fields = new LinkedHashMap<>(rowFields(session, physicalRow));
        if (subject != null) {
            fields.putAll(subjectFields(session, null, subject));
        }
        return new PrismRowMember(
                rowId,
                physicalRow,
                subject == null ? rowId : subject.getSubjectId(),
                subject == null ? firstStringValue(session, physicalRow, "structure_id", "canonical_postera_id", "compound_id") : subject.getStructureId(),
                subject == null ? stringValue(session, physicalRow, "batch_id") : subject.getBatchId(),
                subject == null ? stringValue(session, physicalRow, "project") : subject.getProject(),
                subject == null ? stringValue(session, physicalRow, "series") : subject.getSeries(),
                subject == null ? structureValue(session, physicalRow) : subject.getSmiles(),
                fields
        );
    }

    private List<PrismColumn> summaryColumns(ManagedPrismSession session, List<String> columnIds) {
        if (columnIds == null || columnIds.isEmpty()) {
            throw new ChemOperationException("invalid_arguments", "column_ids must not be empty.");
        }
        if (columnIds.size() > 25) {
            throw new ChemOperationException("invalid_arguments", "A single summary request may include at most 25 column_ids.");
        }
        return columnIds.stream()
                .map(columnId -> {
                    String id = normalizeId(columnId, "columnId");
                    return session.workspace().table().findColumn(id)
                            .orElseThrow(() -> new ChemOperationException(
                                    "prism_column_not_found",
                                    "Prism column " + id + " does not exist."
                            ));
                })
                .toList();
    }

    private List<Integer> physicalRows(ManagedPrismSession session, Iterable<String> rowIds) {
        ArrayList<Integer> rows = new ArrayList<>();
        for (String rowId : rowIds) {
            int physicalRow = session.workspace().physicalRowForRowId(rowId)
                    .orElseThrow(() -> new ChemOperationException("prism_row_not_found", "Prism row " + rowId + " does not exist."));
            rows.add(physicalRow);
        }
        return List.copyOf(rows);
    }

    private PrismRuntimeColumnValueSummary summarizeColumn(PrismColumn column,
                                                           List<Integer> physicalRows,
                                                           Double threshold,
                                                           String thresholdDirection,
                                                           int topValuesLimit) {
        return switch (column.type()) {
            case NUMERIC, INTEGER -> summarizeNumericColumn(column, physicalRows, threshold, thresholdDirection);
            default -> summarizeCategoricalColumn(column, physicalRows, topValuesLimit);
        };
    }

    private PrismRuntimeColumnValueSummary summarizeNumericColumn(PrismColumn column,
                                                                  List<Integer> physicalRows,
                                                                  Double threshold,
                                                                  String thresholdDirection) {
        ArrayList<Double> values = new ArrayList<>();
        for (int physicalRow : physicalRows) {
            if (column.isMissing(physicalRow)) {
                continue;
            }
            double value = column.doubleValueAt(physicalRow);
            if (Double.isFinite(value)) {
                values.add(value);
            }
        }
        Collections.sort(values);
        String direction = normalizeThresholdDirection(thresholdDirection);
        Integer hitCount = null;
        Double hitRate = null;
        if (threshold != null) {
            int hits = 0;
            for (double value : values) {
                if ("lte".equals(direction) ? value <= threshold : value >= threshold) {
                    hits++;
                }
            }
            hitCount = hits;
            hitRate = values.isEmpty() ? null : (double) hits / values.size();
        }
        PrismColumnSchema schema = column.schema();
        return new PrismRuntimeColumnValueSummary(
                column.id(),
                schema.displayName(),
                column.type().name(),
                schema.semanticType(),
                schema.role(),
                schema.unit(),
                schema.endpointId(),
                schema.direction(),
                physicalRows.size(),
                values.size(),
                physicalRows.size() - values.size(),
                new PrismNumericColumnStats(
                        values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
                        percentile(values, 0.50),
                        percentile(values, 0.25),
                        percentile(values, 0.75),
                        values.isEmpty() ? null : values.getFirst(),
                        values.isEmpty() ? null : values.getLast(),
                        threshold,
                        direction,
                        hitCount,
                        hitRate
                ),
                null
        );
    }

    private PrismRuntimeColumnValueSummary summarizeCategoricalColumn(PrismColumn column,
                                                                      List<Integer> physicalRows,
                                                                      int topValuesLimit) {
        int safeLimit = Math.min(100, Math.max(1, topValuesLimit <= 0 ? 10 : topValuesLimit));
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        int valid = 0;
        for (int physicalRow : physicalRows) {
            if (column.isMissing(physicalRow)) {
                continue;
            }
            String value = column.formattedValueAt(physicalRow);
            if (value == null || value.isBlank()) {
                continue;
            }
            valid++;
            counts.merge(value, 1, Integer::sum);
        }
        int denominator = Math.max(1, valid);
        List<PrismCategoryFrequency> topValues = counts.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .limit(safeLimit)
                .map(entry -> new PrismCategoryFrequency(entry.getKey(), entry.getValue(), (double) entry.getValue() / denominator))
                .toList();
        PrismColumnSchema schema = column.schema();
        return new PrismRuntimeColumnValueSummary(
                column.id(),
                schema.displayName(),
                column.type().name(),
                schema.semanticType(),
                schema.role(),
                schema.unit(),
                schema.endpointId(),
                schema.direction(),
                physicalRows.size(),
                valid,
                physicalRows.size() - valid,
                null,
                new PrismCategoricalColumnStats(counts.size(), topValues)
        );
    }

    private static Double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return null;
        }
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double position = p * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction;
    }

    private static String normalizeThresholdDirection(String thresholdDirection) {
        String direction = thresholdDirection == null || thresholdDirection.isBlank()
                ? "gte"
                : thresholdDirection.trim().toLowerCase(Locale.ROOT);
        if (!direction.equals("gte") && !direction.equals("lte")) {
            throw new ChemOperationException("invalid_arguments", "threshold_direction must be gte or lte.");
        }
        return direction;
    }

    private String stringValue(ManagedPrismSession session, int physicalRow, String columnId) {
        if (session.workspace().table().findColumn(columnId).isEmpty()) {
            return null;
        }
        Object value = session.workspace().table().valueAt(physicalRow, columnId);
        return value == null ? null : value.toString();
    }

    private String firstStringValue(ManagedPrismSession session, int physicalRow, String... columnIds) {
        for (String columnId : columnIds) {
            String value = stringValue(session, physicalRow, columnId);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String structureValue(ManagedPrismSession session, int physicalRow) {
        for (PrismColumn column : session.workspace().table().columns()) {
            PrismColumnSchema schema = column.schema();
            if ("chemical_structure".equals(schema.semanticType()) || "primary_structure".equals(schema.role())) {
                Object value = column.valueAt(physicalRow);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            }
        }
        return firstStringValue(session, physicalRow, "smiles", "structure");
    }

    private Map<String, String> rowFields(ManagedPrismSession session, int physicalRow) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        put(fields, "prism.session_id", session.sessionId());
        put(fields, "prism.dataset_id", session.sessionId());
        put(fields, "prism.source_path", session.sourcePath().toString());
        for (PrismColumn column : session.workspace().table().columns()) {
            Object value = column.valueAt(physicalRow);
            if (value != null && !value.toString().isBlank()) {
                put(fields, "prism.column." + column.id(), value.toString());
            }
        }
        return Map.copyOf(fields);
    }

    private List<SubjectRecord> subjects(ManagedPrismSession loaded, String subjectSetId) {
        InMemoryPrismDataset dataContext = dataContext(loaded);
        if (subjectSetId == null || subjectSetId.isBlank()) {
            return dataContext.getSubjectRecords();
        }
        String normalizedSubjectSetId = subjectSetId.trim();
        if (dataContext.findSubjectSet(normalizedSubjectSetId).isEmpty()) {
            throw new ChemOperationException("prism_subject_set_not_found", "Prism subject set " + normalizedSubjectSetId + " does not exist.");
        }
        return dataContext.getSubjectsForSet(normalizedSubjectSetId).stream()
                .map(subjectId -> dataContext.findSubjectRecord(subjectId).orElseThrow())
                .toList();
    }

    private static PrismSubjectSummary subjectSummary(SubjectRecord subject, boolean includeMetadata) {
        return new PrismSubjectSummary(
                subject.getSubjectId(),
                subject.getStructureId(),
                subject.getBatchId(),
                subject.getProject(),
                subject.getSeries(),
                subject.getSmiles() != null && !subject.getSmiles().isBlank(),
                includeMetadata ? subject.getMetadata() : Map.of()
        );
    }

    private static Map<String, String> subjectFields(ManagedPrismSession loaded, String subjectSetId, SubjectRecord subject) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        put(fields, "prism.session_id", loaded.sessionId());
        put(fields, "prism.dataset_id", loaded.sessionId());
        put(fields, "prism.source_path", loaded.sourcePath().toString());
        put(fields, "prism.subject_id", subject.getSubjectId());
        put(fields, "prism.subject_set_id", subjectSetId);
        put(fields, "prism.structure_id", subject.getStructureId());
        put(fields, "prism.batch_id", subject.getBatchId());
        put(fields, "prism.project", subject.getProject());
        put(fields, "prism.series", subject.getSeries());
        for (Map.Entry<String, String> entry : subject.getMetadata().entrySet()) {
            put(fields, "prism.metadata." + entry.getKey(), entry.getValue());
        }
        return Map.copyOf(fields);
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private record PreparedMolecule(
            String title,
            PrismMoleculeDocumentMode mode,
            OclMoleculeDocumentCodec.EncodedMolecule encoded
    ) {
    }

    private ManagedPrismSession session(String sessionId) {
        return sessionRegistry.require(normalizeId(sessionId, "sessionId"));
    }

    private InMemoryPrismDataset dataContext(ManagedPrismSession session) {
        return session.requireDataContext();
    }

    private static void ensureAllRowSet(PrismSession workspace) {
        boolean exists = workspace.rowSets().stream().anyMatch(rowSet -> rowSet.id().equals("all"));
        if (exists) {
            return;
        }
        LinkedHashSet<String> rowIds = new LinkedHashSet<>();
        for (int row = 0; row < workspace.totalRowCount(); row++) {
            rowIds.add(workspace.rowIdForPhysicalRow(row));
        }
        workspace.addRowSet(new PrismRowSet(
                "all",
                "All rows",
                "All rows in the managed Prism session.",
                rowIds,
                Map.of("source", "managed_prism_session")
        ));
    }

    private PrismRowGraph graph(ManagedPrismSession session, String graphId) {
        String id = normalizeId(graphId, "graphId");
        try {
            return session.workspace().graph(id);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("prism_graph_not_found", "Prism graph " + id + " does not exist.", exception);
        }
    }

    private PrismGraphSummary graphSummary(ManagedPrismSession session, PrismRowGraph graph) {
        return PrismMmpGraphService.graphSummary(session, graph);
    }

    private List<PrismGraphEdgeView> edgesBetween(PrismRowGraph graph, String centerRowId, String neighborRowId) {
        return graph.incidentEdges(centerRowId).stream()
                .filter(edge -> connects(edge, centerRowId, neighborRowId))
                .map(this::edgeView)
                .toList();
    }

    private static boolean connects(PrismRowGraphEdge edge, String leftRowId, String rightRowId) {
        return (edge.sourceRowId().equals(leftRowId) && edge.targetRowId().equals(rightRowId))
                || (edge.sourceRowId().equals(rightRowId) && edge.targetRowId().equals(leftRowId));
    }

    private PrismGraphEdgeView edgeView(PrismRowGraphEdge edge) {
        return new PrismGraphEdgeView(edge.id(), edge.sourceRowId(), edge.targetRowId(), edge.label(), edge.properties());
    }

    private PrismGraphPathStep pathStep(PrismRowGraph graph, String fromRowId, String toRowId, int transformExampleLimit) {
        List<PrismGraphEdgeView> edges = edgesBetween(graph, fromRowId, toRowId);
        LinkedHashMap<String, PrismMmpTransformText> transforms = new LinkedHashMap<>();
        for (PrismGraphEdgeView edge : edges) {
            PrismMmpTransformText text = PrismMmpTransformRenderer.render(edge.properties());
            if (text.transformId() != null) {
                transforms.putIfAbsent(text.transformId(), text);
            }
        }
        return new PrismGraphPathStep(
                fromRowId,
                toRowId,
                edges.size(),
                transforms.values().stream().limit(transformExampleLimit).toList());
    }

    private PrismCollapsedGraphNeighbor collapsedNeighbor(ManagedPrismSession session,
                                                         PrismRowGraph graph,
                                                         String centerRowId,
                                                         String neighborRowId,
                                                         int transformExampleLimit) {
        List<PrismGraphEdgeView> edges = edgesBetween(graph, centerRowId, neighborRowId);
        List<Double> deltas = edges.stream()
                .map(edge -> propertyDouble(edge.properties(), "delta"))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        LinkedHashMap<String, PrismMmpTransformText> transforms = new LinkedHashMap<>();
        for (PrismGraphEdgeView edge : edges) {
            PrismMmpTransformText text = PrismMmpTransformRenderer.render(edge.properties());
            if (text.transformId() != null) {
                transforms.putIfAbsent(text.transformId(), text);
            }
        }
        return new PrismCollapsedGraphNeighbor(
                rowMember(session, neighborRowId),
                graph.degree(neighborRowId),
                edges.size(),
                transforms.size(),
                deltas.isEmpty() ? null : deltas.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
                median(deltas),
                deltas.isEmpty() ? null : deltas.getFirst(),
                deltas.isEmpty() ? null : deltas.getLast(),
                transforms.values().stream().limit(transformExampleLimit).toList()
        );
    }

    private static List<PrismMmpTransformSummaryRow> transformSummaryRows(PrismRowGraph graph, int exampleLimit) {
        Map<String, List<PrismRowGraphEdge>> byTransform = new HashMap<>();
        for (PrismRowGraphEdge edge : graph.edges()) {
            String transformId = propertyText(edge.properties(), "transformId");
            if (transformId == null || transformId.isBlank()) {
                continue;
            }
            byTransform.computeIfAbsent(transformId, ignored -> new ArrayList<>()).add(edge);
        }
        ArrayList<PrismMmpTransformSummaryRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<PrismRowGraphEdge>> entry : byTransform.entrySet()) {
            List<PrismRowGraphEdge> edges = entry.getValue().stream()
                    .sorted(Comparator.comparing(PrismRowGraphEdge::sourceRowId).thenComparing(PrismRowGraphEdge::targetRowId))
                    .toList();
            List<Double> deltas = edges.stream()
                    .map(edge -> propertyDouble(edge.properties(), "delta"))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
            PrismRowGraphEdge first = edges.getFirst();
            PrismMmpTransformText text = PrismMmpTransformRenderer.render(first.properties());
            rows.add(new PrismMmpTransformSummaryRow(
                    entry.getKey(),
                    text.cutCount(),
                    text.keyFragment(),
                    text.fromFragment(),
                    text.toFragment(),
                    text.transformText(),
                    edges.size(),
                    deltas.size(),
                    deltas.isEmpty() ? null : deltas.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
                    median(deltas),
                    deltas.isEmpty() ? null : deltas.getFirst(),
                    deltas.isEmpty() ? null : deltas.getLast(),
                    deltas.isEmpty() ? null : deltas.stream().filter(delta -> delta > 0.0).count() / (double) deltas.size(),
                    edges.stream().limit(exampleLimit).map(PrismRowGraphEdge::id).toList(),
                    edges.stream().limit(exampleLimit).map(PrismRowGraphEdge::sourceRowId).toList(),
                    edges.stream().limit(exampleLimit).map(PrismRowGraphEdge::targetRowId).toList()
            ));
        }
        return List.copyOf(rows);
    }

    private static Comparator<PrismMmpTransformSummaryRow> transformSummaryComparator(String sortBy) {
        Comparator<PrismMmpTransformSummaryRow> byText = Comparator.comparing(row -> row.transformText() == null ? row.transformId() : row.transformText());
        return switch (sortBy) {
            case "median_delta_desc" -> Comparator.comparing(
                    PrismMmpTransformSummaryRow::medianDelta,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byText);
            case "median_delta_asc" -> Comparator.comparing(
                    PrismMmpTransformSummaryRow::medianDelta,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(byText);
            case "abs_median_delta_desc" -> Comparator.comparingDouble((PrismMmpTransformSummaryRow row) -> row.medianDelta() == null ? -1.0 : Math.abs(row.medianDelta()))
                    .reversed()
                    .thenComparing(byText);
            case "transform_text" -> byText;
            case "support_desc" -> Comparator.comparingInt(PrismMmpTransformSummaryRow::supportCount).reversed().thenComparing(byText);
            default -> throw new IllegalStateException("Unexpected MMP transform sort: " + sortBy);
        };
    }

    private static String normalizeMmpTransformSort(String sortBy) {
        String normalized = sortBy == null || sortBy.isBlank() ? "support_desc" : sortBy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "support_desc", "median_delta_desc", "median_delta_asc", "abs_median_delta_desc", "transform_text" -> normalized;
            default -> throw new ChemOperationException("invalid_mmp_transform_sort", "sort_by must be support_desc, median_delta_desc, median_delta_asc, abs_median_delta_desc, or transform_text.");
        };
    }

    private static Double median(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int size = sortedValues.size();
        int mid = size / 2;
        if (size % 2 == 1) {
            return sortedValues.get(mid);
        }
        return (sortedValues.get(mid - 1) + sortedValues.get(mid)) / 2.0;
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

    private PrismGrouping grouping(ManagedPrismSession session, String groupingId) {
        String id = normalizeId(groupingId, "groupingId");
        try {
            return session.workspace().grouping(id);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException(
                    "prism_grouping_not_found",
                    "Prism grouping " + id + " does not exist.",
                    exception
            );
        }
    }

    private PrismRowSet rowSet(ManagedPrismSession session, String rowSetId) {
        String id = normalizeId(rowSetId, "rowSetId");
        try {
            return session.workspace().rowSet(id);
        } catch (IllegalArgumentException exception) {
            throw new ChemOperationException("prism_row_set_not_found", "Prism row set " + id + " does not exist.", exception);
        }
    }

    private String generatedSessionId() {
        int index = sessionRegistry.sessions().size() + 1;
        while (sessionRegistry.find("prism" + index).isPresent()) {
            index++;
        }
        return "prism" + index;
    }

    private static String defaultRepositoryId(String datasetId, String subjectSetId) {
        return sanitizeRepositoryId("prism:" + datasetId + ":" + (subjectSetId == null ? "all" : subjectSetId));
    }

    private static String defaultRepositoryLabel(ManagedPrismSession loaded, String subjectSetId) {
        if (subjectSetId == null) {
            return loaded.label() + " all structures";
        }
        return loaded.dataContext()
                .flatMap(dataContext -> dataContext.findSubjectSet(subjectSetId))
                .map(set -> loaded.label() + " / " + set.getName())
                .orElse(loaded.label() + " / " + subjectSetId);
    }

    private static String prismRepositoryDescription(ManagedPrismSession loaded, String subjectSetId) {
        return "Materialized chemistry structures from Prism session " + loaded.sessionId()
                + (subjectSetId == null ? " (all subjects)." : " subject set " + subjectSetId + ".");
    }

    private String generatedRowSetId(ManagedPrismSession session, String prefix) {
        int index = 1;
        Set<String> existing = session.workspace().rowSets().stream()
                .map(PrismRowSet::id)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        String candidate;
        do {
            candidate = prefix + "_" + index++;
        } while (existing.contains(candidate));
        return candidate;
    }

    private static String sanitizeRepositoryId(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ':') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String normalizeId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ChemOperationException("invalid_arguments", name + " must not be blank.");
        }
        return value.trim();
    }

    private static PrismFilter columnFilter(CreatePrismColumnRowSetRequest request, PrismColumn column) {
        String type = normalizeColumnFilterType(request.filterType());
        boolean includeMissing = Boolean.TRUE.equals(request.includeMissing());
        return switch (type) {
            case "numeric_range" -> {
                if (column.type() != PrismColumnType.NUMERIC && column.type() != PrismColumnType.INTEGER) {
                    throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "numeric_range requires a numeric Prism column, but " + column.id() + " is " + column.type() + "."
                    );
                }
                if (request.minimum() == null && request.maximum() == null) {
                    throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "numeric_range requires minimum and/or maximum."
                    );
                }
                if (request.minimum() != null && request.maximum() != null
                        && request.minimum() > request.maximum()) {
                    throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "numeric_range minimum must not exceed maximum."
                    );
                }
                yield new NumericRangeFilter(column.id(), request.minimum(), request.maximum(), includeMissing);
            }
            case "category_include" -> {
                if (request.values().isEmpty()) {
                    throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "category_include requires at least one value."
                    );
                }
                yield new CategoryIncludeFilter(column.id(), Set.copyOf(request.values()), includeMissing);
            }
            case "text_pattern" -> {
                if (request.pattern() == null || request.pattern().isBlank()) {
                    throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "text_pattern requires a non-blank pattern."
                    );
                }
                String mode = request.textMode() == null || request.textMode().isBlank()
                        ? "substring"
                        : request.textMode().trim().toLowerCase(Locale.ROOT);
                TextPatternMode patternMode = switch (mode) {
                    case "substring", "contains" -> TextPatternMode.SUBSTRING;
                    case "regex" -> TextPatternMode.REGEX;
                    default -> throw new ChemOperationException(
                            "invalid_prism_column_filter",
                            "text_mode must be substring or regex."
                    );
                };
                boolean caseInsensitive = request.caseInsensitive() == null || request.caseInsensitive();
                yield new TextPatternFilter(
                        column.id(),
                        request.pattern(),
                        patternMode,
                        caseInsensitive,
                        includeMissing
                );
            }
            case "missing" -> new MissingValueFilter(column.id(), MissingValueMode.MISSING);
            case "has_value" -> new MissingValueFilter(column.id(), MissingValueMode.HAS_VALUE);
            default -> throw new IllegalStateException("Unsupported Prism column filter type: " + type);
        };
    }

    private static String normalizeColumnFilterType(String filterType) {
        if (filterType == null || filterType.isBlank()) {
            throw new ChemOperationException(
                    "invalid_prism_column_filter",
                    "filterType must be numeric_range, category_include, text_pattern, missing, or has_value."
            );
        }
        String normalized = filterType.trim().toLowerCase(Locale.ROOT);
        if (!List.of("numeric_range", "category_include", "text_pattern", "missing", "has_value").contains(normalized)) {
            throw new ChemOperationException(
                    "invalid_prism_column_filter",
                    "filterType must be numeric_range, category_include, text_pattern, missing, or has_value."
            );
        }
        return normalized;
    }

    private static String columnFilterSourceText(CreatePrismColumnRowSetRequest request, String columnId) {
        return switch (normalizeColumnFilterType(request.filterType())) {
            case "numeric_range" -> columnId + " in ["
                    + (request.minimum() == null ? "-inf" : request.minimum())
                    + ", "
                    + (request.maximum() == null ? "+inf" : request.maximum())
                    + "]";
            case "category_include" -> columnId + " in " + request.values();
            case "text_pattern" -> columnId + " "
                    + (request.textMode() == null || request.textMode().isBlank() ? "contains" : request.textMode())
                    + " " + request.pattern();
            case "missing" -> columnId + " is missing";
            case "has_value" -> columnId + " has a value";
            default -> throw new IllegalStateException();
        };
    }
    private static String normalizeRowSetOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new ChemOperationException("invalid_row_set_operation", "operation must be union, merge, intersect, or subtract.");
        }
        String normalized = operation.trim().toLowerCase();
        if ("merge".equals(normalized)) {
            return "union";
        }
        if (!"union".equals(normalized) && !"intersect".equals(normalized) && !"subtract".equals(normalized)) {
            throw new ChemOperationException("invalid_row_set_operation", "operation must be union, merge, intersect, or subtract.");
        }
        return normalized;
    }

    private static String normalizeEndpointFilterOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ChemOperationException("invalid_endpoint_filter", "operator must be gt, gte, lt, lte, or eq.");
        }
        String normalized = operator.trim().toLowerCase();
        if (!List.of("gt", "gte", "lt", "lte", "eq").contains(normalized)) {
            throw new ChemOperationException("invalid_endpoint_filter", "operator must be gt, gte, lt, lte, or eq.");
        }
        return normalized;
    }

    private static boolean numericEndpointFilterMatches(EndpointResult result, String operator, double threshold) {
        Double mean = null;
        if (result instanceof NumericResult numeric && numeric.getState() == NumericState.VALUE) {
            mean = numeric.getMean();
        } else if (result instanceof OptionalNumericResult numeric && numeric.getState() == OptionalNumericState.VALUE) {
            mean = numeric.getMean();
        }
        if (mean == null) {
            return false;
        }
        return switch (operator) {
            case "gt" -> mean > threshold;
            case "gte" -> mean >= threshold;
            case "lt" -> mean < threshold;
            case "lte" -> mean <= threshold;
            case "eq" -> Double.compare(mean, threshold) == 0;
            default -> throw new IllegalArgumentException("Unsupported endpoint filter operator: " + operator);
        };
    }

    private static MeasurementDateFilter measurementDateFilter(String field, String afterText, String beforeText, Boolean requireMeasuredDate) {
        String normalizedField = normalizeMeasurementDateField(field == null || field.isBlank() ? "last" : field, "measurement_date_field");
        return new MeasurementDateFilter(
                normalizedField,
                parseMeasurementDateBound(afterText, true),
                parseMeasurementDateBound(beforeText, false),
                afterText,
                beforeText,
                requireMeasuredDate == null ? afterText != null || beforeText != null : requireMeasuredDate);
    }

    private static String normalizeMeasurementDateField(String value, String argumentName) {
        String normalized = value == null || value.isBlank() ? "last" : value.trim().toLowerCase();
        if (!"first".equals(normalized) && !"last".equals(normalized)) {
            throw new ChemOperationException("invalid_measurement_date_filter", argumentName + " must be first or last.");
        }
        return normalized;
    }

    private static Instant parseMeasurementDateBound(String value, boolean lowerBound) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate date = LocalDate.parse(trimmed);
                return lowerBound
                        ? date.atStartOfDay(ZoneId.systemDefault()).toInstant()
                        : date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1);
            }
            return Instant.parse(trimmed);
        } catch (RuntimeException exception) {
            throw new ChemOperationException("invalid_measurement_date_filter", "Measurement date filters must be YYYY-MM-DD or ISO instants: " + trimmed, exception);
        }
    }

    private static Instant parseOptionalMeasurementInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.trim().matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(value.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant();
            }
            return Instant.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new ChemOperationException("invalid_measurement_date_filter", "Invalid measurement date in Prism endpoint value: " + value, exception);
        }
    }

    private static String endpointSourceText(String endpointId, String operator, Double threshold, MeasurementDateFilter dateFilter) {
        List<String> parts = new ArrayList<>();
        if (operator != null && threshold != null) {
            parts.add(endpointId + " " + operator + " " + threshold);
        } else {
            parts.add(endpointId);
        }
        if (dateFilter.hasBounds()) {
            parts.add(dateFilter.sourceText());
        }
        return String.join(" and ", parts);
    }

    private static int structureSubjectCount(InMemoryPrismDataset dataset) {
        int count = 0;
        for (SubjectRecord subject : dataset.getSubjectRecords()) {
            if (subject.getSmiles() != null && !subject.getSmiles().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int structureRowCount(ManagedPrismSession session) {
        for (PrismColumn column : session.workspace().table().columns()) {
            PrismColumnSchema schema = column.schema();
            if ("chemical_structure".equals(schema.semanticType()) || "primary_structure".equals(schema.role())) {
                int count = 0;
                for (int row = 0; row < column.rowCount(); row++) {
                    if (!column.isMissing(row)) {
                        count++;
                    }
                }
                return count;
            }
        }
        return 0;
    }

    private record MeasurementDateFilter(String field, Instant after, Instant before, String afterText, String beforeText, boolean requireMeasuredDate) {
        private boolean hasBounds() {
            return after != null || before != null;
        }

        private boolean matches(EndpointResult result) {
            if (!hasBounds()) {
                return true;
            }
            if (result == null) {
                return !requireMeasuredDate;
            }
            String text = "first".equals(field) ? result.getFirstMeasurement() : result.getLastMeasurement();
            Instant instant = parseOptionalMeasurementInstant(text);
            if (instant == null) {
                return !requireMeasuredDate;
            }
            if (after != null && instant.isBefore(after)) {
                return false;
            }
            return before == null || !instant.isAfter(before);
        }

        private String sourceText() {
            ArrayList<String> parts = new ArrayList<>();
            if (afterText != null && !afterText.isBlank()) {
                parts.add(field + " measured after " + afterText.trim());
            }
            if (beforeText != null && !beforeText.isBlank()) {
                parts.add(field + " measured before " + beforeText.trim());
            }
            return String.join(" and ", parts);
        }
    }

    private record MaterializationMapping(String datasetId, String subjectSetId) {}
}
