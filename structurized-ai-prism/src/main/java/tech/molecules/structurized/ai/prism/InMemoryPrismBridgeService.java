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
import tech.molecules.structurized.prism.engine.PrismGrouping;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocument;
import tech.molecules.structurized.prism.engine.PrismMoleculeDocumentMode;
import tech.molecules.structurized.prism.engine.PrismMoleculeList;
import tech.molecules.structurized.prism.engine.PrismColumnSchema;
import tech.molecules.structurized.prism.engine.PrismRowSet;
import tech.molecules.structurized.prism.engine.PrismSession;
import tech.molecules.structurized.prism.engine.TextPatternFilter;
import tech.molecules.structurized.prism.engine.TextPatternMode;
import tech.molecules.structurized.prism.engine.ocl.OclMoleculeDocumentCodec;
import tech.molecules.structurized.prism.io.PrismTsvDatasetLoader;
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
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.BitSet;
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
            InMemoryPrismDataset dataset = PrismTsvDatasetLoader.load(sourcePath);
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
