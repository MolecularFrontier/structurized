package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.clustering.SimilarityClusteringAiService;
import tech.molecules.structurized.ai.decomposition.DecompositionAiService;
import tech.molecules.structurized.ai.prism.PrismEndpointSummary;
import tech.molecules.structurized.ai.prism.PrismEndpointValue;
import tech.molecules.structurized.ai.selection.SelectionAiService;
import tech.molecules.structurized.ai.selection.SelectionAiService.SelectionMember;
import tech.molecules.structurized.ai.inspect.OclStructureInspectionService;
import tech.molecules.structurized.ai.inspect.StructureInspectionService;
import tech.molecules.structurized.ai.model.AtomRef;
import tech.molecules.structurized.ai.model.BondRef;
import tech.molecules.structurized.ai.model.ChemOperationException;
import tech.molecules.structurized.ai.model.CreateRepositoryRequest;
import tech.molecules.structurized.ai.model.CutBondsRequest;
import tech.molecules.structurized.ai.model.ExactStructureSearchRequest;
import tech.molecules.structurized.ai.model.RegisterStructureRequest;
import tech.molecules.structurized.ai.model.RepositoryRecord;
import tech.molecules.structurized.ai.model.StructureInspection;
import tech.molecules.structurized.ai.model.StructureRecord;
import tech.molecules.structurized.ai.model.SubstructureSearchMatch;
import tech.molecules.structurized.ai.model.SubstructureSearchResult;
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.prism.ClusterPrismRowSetRequest;
import tech.molecules.structurized.ai.prism.AddPrismMoleculesRequest;
import tech.molecules.structurized.ai.prism.ConfigurePrismLiveEvaluatorRequest;
import tech.molecules.structurized.ai.prism.CreatePrismMoleculeListRequest;
import tech.molecules.structurized.ai.prism.CreatePrismClusterRowSetRequest;
import tech.molecules.structurized.ai.prism.CreatePrismGraphNeighborhoodRowSetRequest;
import tech.molecules.structurized.ai.prism.CreatePrismGroupRowSetRequest;
import tech.molecules.structurized.ai.prism.CreatePrismColumnRowSetRequest;
import tech.molecules.structurized.ai.prism.CreatePrismEndpointRowSetRequest;
import tech.molecules.structurized.ai.prism.CreatePrismRowSetFromSubjectSetRequest;
import tech.molecules.structurized.ai.prism.CombinePrismRowSetsRequest;
import tech.molecules.structurized.ai.prism.EvaluatePrismPredictionRequest;
import tech.molecules.structurized.ai.prism.MinePrismMmpGraphRequest;
import tech.molecules.structurized.ai.prism.MinePrismSimilarityGraphRequest;
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.MaterializePrismSubjectSetRequest;
import tech.molecules.structurized.ai.prism.OpenPrismDatasetRequest;
import tech.molecules.structurized.ai.prism.OpenPrismPackRequest;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.prism.RunPrismLiveEvaluatorRequest;
import tech.molecules.structurized.ai.prism.PrismGroupingColumnSummary;
import tech.molecules.structurized.ai.prism.PrismRowSetColumnSummary;
import tech.molecules.structurized.ai.prism.PrismGroupingSummary;
import tech.molecules.structurized.ai.prism.PrismRowSetSummary;
import tech.molecules.structurized.ai.prism.PrismMoleculeInput;
import tech.molecules.structurized.ai.render.CompactStructureRenderer;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;
import tech.molecules.structurized.clustering.SimilarityCluster;
import tech.molecules.structurized.prism.result.EndpointResult;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.decomposition.DecompositionConfig;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class McpChemistryTools {
    private final ObjectMapper mapper;
    private final StructureRepositoryService repositories;
    private final StructureInspectionService inspections;
    private final StructureSearchService searches;
    private final PrismBridgeService prism;
    private final McpArtifactService artifacts;
    private final McpToolOutputSupport output;
    private final StructureComparisonMcpTool comparisonTools;
    private final PrismGraphMcpTool graphTools;
    private final ScaffoldSarMcpTool scaffoldSarTools;
    private final SelectionAiService selections;
    private final SimilarityClusteringAiService clusterings;
    private final DecompositionAiService decompositions;
    private final CompactStructureRenderer compactRenderer = new CompactStructureRenderer();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final List<McpToolDefinition> tools;

    private McpChemistryTools(ObjectMapper mapper, StructureRepositoryService repositories, PrismBridgeService prism) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.inspections = new OclStructureInspectionService(repositories);
        this.searches = new OclStructureSearchService(repositories);
        this.prism = Objects.requireNonNull(prism, "prism");
        this.artifacts = new McpArtifactService(mapper);
        this.output = new McpToolOutputSupport(artifacts);
        this.comparisonTools = new StructureComparisonMcpTool(this.repositories, this.prism);
        this.graphTools = new PrismGraphMcpTool(this.prism, this.artifacts, this.output);
        this.scaffoldSarTools = new ScaffoldSarMcpTool(this.prism, this.artifacts, this.output);
        this.selections = new SelectionAiService(repositories);
        this.clusterings = new SimilarityClusteringAiService(repositories);
        this.decompositions = new DecompositionAiService(repositories);
        this.tools = List.copyOf(registerTools());
    }

    static McpChemistryTools createDefault(ObjectMapper mapper) {
        StructureRepositoryService repositories = new InMemoryStructureRepositoryService();
        return new McpChemistryTools(mapper, repositories, new InMemoryPrismBridgeService(repositories));
    }

    static McpChemistryTools create(ObjectMapper mapper,
                                    StructureRepositoryService repositories,
                                    PrismBridgeService prism) {
        return new McpChemistryTools(mapper, repositories, prism);
    }

    List<McpToolDefinition> tools() {
        return tools;
    }

    ToolCallResult call(String name, ObjectNode arguments) throws Exception {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new ChemOperationException("tool_not_found", "Unknown chemistry tool: " + name);
        }
        Object result = handler.call(arguments == null ? mapper.createObjectNode() : arguments);
        String text = result instanceof StructureInspectionText structureText
                ? structureText.text()
                : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Object structured = result instanceof StructureInspectionText structureText
                ? structureText.inspection()
                : result;
        return new ToolCallResult(text, mapper.valueToTree(structured), false);
    }

    private List<McpToolDefinition> registerTools() {
        List<McpToolDefinition> result = new ArrayList<>();
        add(result, "create_repository", "Creates a lightweight structure repository identity boundary.", schema(
                prop("repository_id", "string", "Optional unique repository ID."),
                prop("label", "string", "Human-readable repository label."),
                prop("description", "string", "Optional repository description."),
                prop("mutable", "boolean", "Whether structures may be registered into this repository.")),
                args -> repositories.createRepository(new CreateRepositoryRequest(
                        optionalString(args, "repository_id", null),
                        optionalString(args, "label", null),
                        optionalString(args, "description", null),
                        optionalBoolean(args, "mutable", true))));
        add(result, "list_repositories", "Lists concise repository metadata.", schema(),
                args -> repositories.listRepositories());
        add(result, "list_artifacts", "Lists file artifacts written by this MCP server session.", schema(),
                args -> artifacts.listArtifacts());
        add(result, "get_artifact_info", "Returns metadata for one file artifact.", schema(
                required("artifact_id"),
                prop("artifact_id", "string", "Artifact ID returned by a file-output tool.")),
                args -> artifacts.getArtifact(requiredString(args, "artifact_id")));
        add(result, "get_structurized_tool_guide", "Returns concise workflow and semantics guidance for using Structurized MCP tools.", schema(
                prop("topic", "string", "overview, payload_hygiene, prism_workflow, clustering_workflow, mmp_graph_workflow, scaffold_sar_workflow, decomposition_rules, or artifact_output.")),
                this::toolGuide);
        add(result, "get_repository_info", "Returns metadata for one repository.", schema(required("repository_id"), prop("repository_id", "string", "Repository ID.")),
                args -> repository(requiredString(args, "repository_id")));
        add(result, "register_structure", "Parses and registers a SMILES as an immutable molecular snapshot with stable atom and bond IDs.", schema(
                required("smiles"),
                prop("smiles", "string", "Input SMILES."),
                prop("repository_id", "string", "Target repository ID. Defaults to session."),
                prop("structure_id", "string", "Optional structure ID."),
                prop("label", "string", "Optional structure label."),
                prop("fields", "object", "Optional source fields.")),
                args -> repositories.registerStructure(new RegisterStructureRequest(
                        requiredString(args, "smiles"),
                        optionalString(args, "repository_id", "session"),
                        optionalString(args, "structure_id", null),
                        optionalString(args, "label", null),
                        stringMap(args, "fields"))));
        add(result, "list_structures", "Lists concise structure records in a repository.", schema(
                required("repository_id"),
                prop("repository_id", "string", "Repository ID."),
                prop("offset", "integer", "Zero-based offset."),
                prop("limit", "integer", "Maximum records to return.")),
                args -> repositories.listStructures(requiredString(args, "repository_id"), optionalInt(args, "offset", 0), optionalInt(args, "limit", 100)));
        add(result, "inspect_structure", "Returns the complete molecular graph; compact format is optimized for LLM reading.", schema(
                required("repository_id", "structure_id"),
                prop("repository_id", "string", "Repository ID."),
                prop("structure_id", "string", "Structure ID."),
                prop("format", "string", "compact or json.")),
                this::inspectStructure);
        add(result, "inspect_atom", "Returns detailed factual information for one atom ID.", atomSchema("atom_id"),
                args -> inspections.inspectAtom(new AtomRef(structureRef(args), requiredString(args, "atom_id"))));
        add(result, "inspect_bond", "Returns detailed factual information for one bond ID.", atomSchema("bond_id"),
                args -> inspections.inspectBond(new BondRef(structureRef(args), requiredString(args, "bond_id"))));
        add(result, "inspect_atom_environment", "Returns the exact local molecular graph within a bond radius around an atom.", schema(
                required("repository_id", "structure_id", "atom_id"),
                prop("repository_id", "string", "Repository ID."),
                prop("structure_id", "string", "Structure ID."),
                prop("atom_id", "string", "Center atom ID."),
                prop("radius", "integer", "Topological radius, default 2.")),
                args -> inspections.inspectAtomEnvironment(new AtomRef(structureRef(args), requiredString(args, "atom_id")), optionalInt(args, "radius", 2)));
        add(result, "inspect_ring_system", "Returns the ring system containing an atom, including ring atoms, bonds, junctions, and attachments.", atomSchema("atom_id"),
                args -> inspections.inspectRingSystem(new AtomRef(structureRef(args), requiredString(args, "atom_id"))));
        add(result, "find_shortest_path", "Finds a deterministic shortest topological path between two atom IDs.", schema(
                required("repository_id", "structure_id", "atom_id_1", "atom_id_2"),
                prop("repository_id", "string", "Repository ID."),
                prop("structure_id", "string", "Structure ID."),
                prop("atom_id_1", "string", "Start atom ID."),
                prop("atom_id_2", "string", "End atom ID.")),
                args -> inspections.findShortestPath(
                        new AtomRef(structureRef(args), requiredString(args, "atom_id_1")),
                        new AtomRef(structureRef(args), requiredString(args, "atom_id_2"))));
        add(result, "cut_bonds", "Cuts requested graph bonds and returns deterministic fragments with mapped attachment points.", schema(
                required("repository_id", "structure_id", "bond_ids"),
                prop("repository_id", "string", "Repository ID."),
                prop("structure_id", "string", "Structure ID."),
                arrayProp("bond_ids", "string", "Bond IDs to cut.")),
                args -> inspections.cutBonds(new CutBondsRequest(structureRef(args), stringList(args, "bond_ids"))));
        add(result, "search_exact_structure", "Searches repositories for exact canonical chemical identity matches without normalization.", schema(
                required("query_smiles"),
                prop("query_smiles", "string", "Query SMILES."),
                arrayProp("repository_ids", "string", "Optional repository scope."),
                prop("component_scope", "string", "whole_record, any_component, or largest.")),
                args -> searches.searchExactStructure(new ExactStructureSearchRequest(
                        requiredString(args, "query_smiles"),
                        optionalStringList(args, "repository_ids"),
                        optionalString(args, "component_scope", "whole_record"))));
        add(result, "search_substructure", "Searches repositories for a SMILES or SMARTS substructure. Defaults to count-only; request ids or full for rows.", schema(
                required("query"),
                prop("query", "string", "SMILES or SMARTS query."),
                prop("query_type", "string", "smiles or smarts."),
                arrayProp("repository_ids", "string", "Optional repository scope."),
                prop("component_scope", "string", "all or largest."),
                prop("output_mode", "string", "count, ids, or full. Defaults to count."),
                prop("offset", "integer", "Zero-based result offset for ids/full modes."),
                prop("limit", "integer", "Maximum rows returned for ids/full modes."),
                prop("max_results", "integer", "Legacy maximum rows; used if limit is omitted."),
                prop("max_matches_per_structure", "integer", "Maximum mappings per structure."),
                prop("include_atom_mappings", "boolean", "Whether full mode should include atom mappings."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported."),
                prop("create_selection", "boolean", "Whether to store all matching structure IDs as a server-side selection."),
                prop("selection_id", "string", "Optional selection ID when create_selection is true.")),
                this::searchSubstructure);
        add(result, "compare_structures", "Compares two structures with strict-MCS alignment and returns summary, compact, or full independent structural edits. Works from SMILES, repository refs, or Prism row refs.", schema(
                prop("left_smiles", "string", "Left/input structure as SMILES."),
                prop("right_smiles", "string", "Right/output structure as SMILES."),
                prop("left_repository_id", "string", "Left repository ID for repository mode."),
                prop("left_structure_id", "string", "Left structure ID for repository mode."),
                prop("right_repository_id", "string", "Right repository ID for repository mode."),
                prop("right_structure_id", "string", "Right structure ID for repository mode."),
                prop("session_id", "string", "Managed Prism session ID for Prism row mode."),
                prop("left_row_id", "string", "Left Prism row ID for Prism row mode."),
                prop("right_row_id", "string", "Right Prism row ID for Prism row mode."),
                prop("structure_column_id", "string", "Reserved for Prism sessions with multiple structure columns; v1 uses the session primary structure."),
                prop("context_radius", "integer", "Core context radius around extension points. Defaults to 1."),
                prop("output_mode", "string", "summary, compact, or full. Defaults to summary."),
                prop("include_idcodes", "boolean", "Whether to include raw OCL IDCodes in compact output. Full mode always includes them."),
                prop("include_atom_mappings", "boolean", "Whether to include strict-MCS atom mappings. Defaults to false.")),
                comparisonTools::compareStructures);
        add(result, "open_prism_dataset", "Loads a PRISM TSV bundle as an in-memory dataset session.", schema(
                required("path"),
                prop("path", "string", "Path to a PRISM TSV bundle directory."),
                prop("dataset_id", "string", "Optional dataset ID."),
                prop("label", "string", "Optional display label.")),
                args -> prism.openDataset(new OpenPrismDatasetRequest(
                        Path.of(requiredString(args, "path")),
                        optionalString(args, "dataset_id", null),
                        optionalString(args, "label", null))));
        add(result, "open_prism_pack", "Opens a PrismPack directory as a managed PrismSession without TSV materialization.", schema(
                required("path"),
                prop("path", "string", "Path to a PrismPack directory."),
                prop("session_id", "string", "Optional managed Prism session ID."),
                prop("label", "string", "Optional display label.")),
                args -> prism.openPack(new OpenPrismPackRequest(
                        Path.of(requiredString(args, "path")),
                        optionalString(args, "session_id", null),
                        optionalString(args, "label", null))));
        add(result, "list_prism_datasets", "Lists loaded PRISM dataset sessions. Legacy alias for session listing.", schema(),
                args -> prism.listDatasets());
        add(result, "list_prism_sessions", "Lists managed Prism analysis sessions backed by real PrismSession workspaces.", schema(),
                args -> prism.listSessions());
        add(result, "get_prism_session_info", "Returns counts, endpoints, subject sets, row sets, and revision for one managed Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.getSessionInfo(requiredString(args, "session_id")));
        add(result, "list_prism_columns", "Lists runtime PrismSession columns with schema metadata and missing-value counts.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listColumns(requiredString(args, "session_id")));
        add(result, "describe_prism_session_for_agent", "Returns an agent-oriented overview of one PrismSession, including key columns and row sets.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.describeSessionForAgent(requiredString(args, "session_id")));
        add(result, "list_prediction_capabilities", "Lists endpoint-linked prediction capabilities available for one managed Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("endpoint_id", "string", "Optional measured or predicted Prism endpoint ID.")),
                args -> prism.listPredictionCapabilities(
                        requiredString(args, "session_id"),
                        optionalString(args, "endpoint_id", null)));
        add(result, "describe_prediction_capability", "Returns provider, workflow, endpoint mapping, status, and metadata for one prediction capability.", schema(
                required("session_id", "capability_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("capability_id", "string", "Prediction capability ID.")),
                args -> prism.describePredictionCapability(
                        requiredString(args, "session_id"),
                        requiredString(args, "capability_id")));
        add(result, "evaluate_prism_prediction", "Runs one endpoint-centered prediction capability for a Prism row set and publishes result columns without overwriting measured data.", schema(
                required("session_id", "endpoint_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Source Prism row set. Defaults to all."),
                prop("prediction_run_id", "string", "Optional unique prediction run ID."),
                prop("label", "string", "Optional human-readable run label."),
                prop("endpoint_id", "string", "Measured Prism endpoint ID to predict or fill."),
                prop("capability_id", "string", "Optional exact prediction capability ID. Defaults to the highest-priority compatible capability."),
                prop("mode", "string", "MISSING_ONLY or ALL. Defaults to MISSING_ONLY."),
                prop("publish_value", "boolean", "Publish prediction value columns. Defaults to true."),
                prop("publish_status", "boolean", "Publish prediction status columns. Defaults to true."),
                prop("publish_uncertainty", "boolean", "Publish uncertainty columns. Defaults to true."),
                prop("publish_applicability", "boolean", "Publish applicability columns. Defaults to true.")),
                args -> prism.evaluatePrismPrediction(new EvaluatePrismPredictionRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "row_set_id", "all"),
                        optionalString(args, "prediction_run_id", null),
                        optionalString(args, "label", null),
                        requiredString(args, "endpoint_id"),
                        optionalString(args, "capability_id", null),
                        optionalString(args, "mode", "MISSING_ONLY"),
                        optionalNullableBoolean(args, "publish_value"),
                        optionalNullableBoolean(args, "publish_status"),
                        optionalNullableBoolean(args, "publish_uncertainty"),
                        optionalNullableBoolean(args, "publish_applicability"))));
        add(result, "get_prediction_run", "Returns a paged, provenance-rich prediction run artifact for one managed Prism session.", schema(
                required("session_id", "prediction_run_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("prediction_run_id", "string", "Prediction run artifact ID."),
                prop("offset", "integer", "Zero-based value offset."),
                prop("limit", "integer", "Maximum prediction values returned.")),
                args -> prism.getPredictionRun(
                        requiredString(args, "session_id"),
                        requiredString(args, "prediction_run_id"),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "list_prism_molecule_lists", "Lists lightweight ordered molecule-document lists in one managed Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listMoleculeLists(requiredString(args, "session_id")));
        add(result, "get_prism_molecule_list", "Returns one ordered molecule list as normalized SMILES and SMARTS documents.", schema(
                required("session_id", "list_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("list_id", "string", "Molecule list ID.")),
                args -> prism.getMoleculeList(
                        requiredString(args, "session_id"),
                        requiredString(args, "list_id")));
        add(result, "create_prism_molecule_list", "Creates an empty ordered molecule list for sketches or proposed compounds.", schema(
                required("session_id", "title"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("list_id", "string", "Optional molecule list ID."),
                prop("title", "string", "Human-readable list title.")),
                args -> prism.createMoleculeList(new CreatePrismMoleculeListRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "list_id", null),
                        requiredString(args, "title"))));
        add(result, "add_prism_molecules", "Adds an ordered batch of new molecule or fragment documents to a session molecule list. Molecules use SMILES; fragments use SMARTS.", schema(
                required("session_id", "list_id", "molecules"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("list_id", "string", "Target molecule list ID."),
                moleculeArrayProp()),
                args -> prism.addMolecules(new AddPrismMoleculesRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "list_id"),
                        moleculeInputs(args))));
        add(result, "list_prism_live_evaluators", "Lists live evaluator bindings and their execution configuration for one Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listLiveEvaluators(requiredString(args, "session_id")));
        add(result, "configure_prism_live_evaluator", "Creates or updates a live evaluator binding. Binding changes are guarded workspace mutations.", schema(
                required("session_id", "binding_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("binding_id", "string", "Evaluator binding ID."),
                prop("capability_id", "string", "Capability ID; required only for a new binding."),
                prop("mode", "string", "auto, manual, or disabled."),
                prop("quiet_period_ms", "integer", "Debounce quiet period in milliseconds."),
                prop("configuration", "object", "Provider-specific evaluator configuration."),
                prop("expected_workspace_revision", "integer", "Optional optimistic workspace revision guard.")),
                args -> prism.configureLiveEvaluator(new ConfigurePrismLiveEvaluatorRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "binding_id"),
                        optionalString(args, "capability_id", null),
                        optionalString(args, "mode", null),
                        optionalLong(args, "quiet_period_ms"),
                        optionalObjectMap(args, "configuration"),
                        optionalLong(args, "expected_workspace_revision"))));
        add(result, "list_prism_live_evaluations", "Returns current live evaluator states and latest successful results for one molecule document.", schema(
                required("session_id", "document_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("document_id", "string", "Molecule document ID.")),
                args -> prism.listLiveEvaluations(
                        requiredString(args, "session_id"),
                        requiredString(args, "document_id")));
        add(result, "run_prism_live_evaluator", "Queues one evaluator immediately for a molecule document without changing semantic workspace revision.", schema(
                required("session_id", "binding_id", "document_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("binding_id", "string", "Evaluator binding ID."),
                prop("document_id", "string", "Molecule document ID."),
                prop("expected_document_revision", "integer", "Optional optimistic molecule-document revision guard.")),
                args -> prism.runLiveEvaluator(new RunPrismLiveEvaluatorRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "binding_id"),
                        requiredString(args, "document_id"),
                        optionalLong(args, "expected_document_revision"))));
        add(result, "list_prism_row_sets", "Lists Prism row sets for a managed session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listRowSets(requiredString(args, "session_id")));
        add(result, "get_prism_row_set_members", "Returns paged row members for a Prism session row set.", schema(
                required("session_id", "row_set_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Prism row set ID."),
                prop("offset", "integer", "Zero-based row offset."),
                prop("limit", "integer", "Maximum rows returned.")),
                args -> prism.getRowSetMembers(
                        requiredString(args, "session_id"),
                        requiredString(args, "row_set_id"),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "create_prism_row_set_from_subject_set", "Creates or returns a Prism row set from a PRISM subject set without materializing a repository.", schema(
                required("session_id", "subject_set_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("subject_set_id", "string", "PRISM subject set ID."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                args -> prism.createRowSetFromSubjectSet(new CreatePrismRowSetFromSubjectSetRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "subject_set_id"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null))));
        add(result, "create_prism_endpoint_row_set", "Creates a Prism row set from endpoint mean and/or endpoint measurement-date filters without repository materialization.", schema(
                required("session_id", "endpoint_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("endpoint_id", "string", "PRISM endpoint ID."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("operator", "string", "Optional numeric mean operator: gt, gte, lt, lte, or eq."),
                prop("value", "number", "Optional numeric threshold compared against endpoint mean."),
                prop("measurement_date_field", "string", "first or last measurement date. Defaults to last."),
                prop("measured_after", "string", "Inclusive measurement date lower bound, YYYY-MM-DD or ISO instant."),
                prop("measured_before", "string", "Inclusive measurement date upper bound, YYYY-MM-DD or ISO instant."),
                prop("require_measured_date", "boolean", "Whether missing dates are excluded when date bounds are supplied. Defaults to true.")),
                args -> prism.createEndpointRowSet(new CreatePrismEndpointRowSetRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "endpoint_id"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "operator", null),
                        optionalDouble(args, "value", null),
                        optionalString(args, "measurement_date_field", null),
                        optionalString(args, "measured_after", null),
                        optionalString(args, "measured_before", null),
                        optionalNullableBoolean(args, "require_measured_date"))));
        add(result, "create_prism_column_row_set", "Creates a Prism row set by evaluating an existing runtime column without changing applied table filters.", schema(
                required("session_id", "column_id", "filter_type"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("base_row_set_id", "string", "Optional source row set. Defaults to all."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description."),
                prop("column_id", "string", "Runtime Prism column ID."),
                prop("filter_type", "string", "numeric_range, category_include, text_pattern, missing, or has_value."),
                prop("minimum", "number", "Inclusive numeric lower bound."),
                prop("maximum", "number", "Inclusive numeric upper bound."),
                arrayProp("values", "string", "Included formatted values for category_include."),
                prop("pattern", "string", "Substring or regular expression for text_pattern."),
                prop("text_mode", "string", "substring or regex. Defaults to substring."),
                prop("case_insensitive", "boolean", "Case-insensitive text matching. Defaults to true."),
                prop("include_missing", "boolean", "Include missing rows for range, category, or text filters.")),
                args -> prism.createColumnRowSet(new CreatePrismColumnRowSetRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "base_row_set_id", "all"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null),
                        requiredString(args, "column_id"),
                        requiredString(args, "filter_type"),
                        optionalDouble(args, "minimum", null),
                        optionalDouble(args, "maximum", null),
                        optionalStringList(args, "values"),
                        optionalString(args, "pattern", null),
                        optionalString(args, "text_mode", null),
                        optionalNullableBoolean(args, "case_insensitive"),
                        optionalNullableBoolean(args, "include_missing"))));
        add(result, "combine_prism_row_sets", "Creates a new Prism row set from existing session row sets using union/merge, intersect, or subtract.", schema(
                required("session_id", "operation", "row_set_ids"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("operation", "string", "union, merge, intersect, or subtract."),
                arrayProp("row_set_ids", "string", "Input Prism row set IDs. For subtract, the first row set is the minuend."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                args -> prism.combineRowSets(new CombinePrismRowSetsRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null),
                        requiredString(args, "operation"),
                        stringList(args, "row_set_ids"))));
        add(result, "get_prism_row_set_structures", "Returns lazy structure entries for a Prism session row set without copying them into a repository.", schema(
                required("session_id", "row_set_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Prism row set ID.")),
                args -> prism.rowSetStructures(requiredString(args, "session_id"), requiredString(args, "row_set_id")));
        add(result, "list_prism_groupings", "Lists reusable row groupings published in one managed Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listGroupings(requiredString(args, "session_id")));
        add(result, "get_prism_grouping", "Returns a paged description of groups, hierarchy, representatives, and member counts for one Prism grouping.", schema(
                required("session_id", "grouping_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("grouping_id", "string", "Prism grouping ID."),
                prop("offset", "integer", "Zero-based group offset."),
                prop("limit", "integer", "Maximum groups returned.")),
                args -> prism.getGrouping(
                        requiredString(args, "session_id"),
                        requiredString(args, "grouping_id"),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "create_prism_group_row_set", "Creates a Prism row set from any group in a reusable session grouping.", schema(
                required("session_id", "grouping_id", "group_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("grouping_id", "string", "Prism grouping ID."),
                prop("group_id", "string", "Group ID within the grouping."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                args -> prism.createGroupRowSet(new CreatePrismGroupRowSetRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "grouping_id"),
                        requiredString(args, "group_id"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null))));
        add(result, "list_prism_graphs", "Lists reusable row graphs published in one managed Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listGraphs(requiredString(args, "session_id")));
        add(result, "summarize_prism_graph", "Returns a compact summary for one Prism row graph.", schema(
                required("session_id", "graph_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID.")),
                args -> prism.summarizeGraph(requiredString(args, "session_id"), requiredString(args, "graph_id")));
        add(result, "inspect_prism_graph_neighborhood", "Inspects rows directly connected to a center row in a Prism row graph. Defaults to output_mode:stats; use collapsed for one readable row per neighbor, compact/full for raw edge drill-down.", schema(
                required("session_id", "graph_id", "center_row_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID."),
                prop("center_row_id", "string", "Center Prism row ID."),
                prop("output_mode", "string", "stats, collapsed, compact, or full. Defaults to stats."),
                prop("limit", "integer", "Maximum neighbors returned. Defaults to 10 for collapsed/compact and 50 for full."),
                prop("transform_example_limit", "integer", "Maximum readable transform examples per collapsed neighbor. Defaults to 3."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                graphTools::inspectPrismGraphNeighborhood);
        add(result, "find_prism_graph_shortest_path", "Checks whether two Prism rows are connected in a row graph and returns graph-hop distance. Defaults to output_mode:stats; use output_mode:compact with include_path:true for a readable path.", schema(
                required("session_id", "graph_id", "source_row_id", "target_row_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID."),
                prop("source_row_id", "string", "Start Prism row ID."),
                prop("target_row_id", "string", "Target Prism row ID."),
                prop("include_path", "boolean", "Whether to include path rows and bounded readable transform examples for compact/full output. Defaults to false."),
                prop("output_mode", "string", "stats, compact, or full. Defaults to stats."),
                prop("max_depth", "integer", "Optional maximum graph-hop depth. Omit or set 0 for unlimited BFS."),
                prop("transform_example_limit", "integer", "Maximum readable transform examples per returned path step. Defaults to 2."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                graphTools::findPrismGraphShortestPath);
        add(result, "summarize_prism_mmp_transforms", "Ranks readable MMP transforms from an existing Prism MMP graph without returning raw edge lists. Use pIC50/LipE/selectivity value columns at mining time for meaningful deltas.", schema(
                required("session_id", "graph_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism MMP graph ID."),
                prop("min_support", "integer", "Minimum transform support. Defaults to 1."),
                prop("sort_by", "string", "support_desc, median_delta_desc, median_delta_asc, abs_median_delta_desc, or transform_text. Defaults to support_desc."),
                prop("offset", "integer", "Zero-based transform offset."),
                prop("limit", "integer", "Maximum transform rows returned. Defaults to 50."),
                prop("example_limit", "integer", "Maximum example edge/source/target IDs per transform. Defaults to 3."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                graphTools::summarizePrismMmpTransforms);
        add(result, "analyze_prism_graph", "Returns compact global graph statistics such as degree distribution and high-degree rows without returning edge lists.", schema(
                required("session_id", "graph_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID."),
                prop("limit", "integer", "Maximum high-degree rows returned. Defaults to 20."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                graphTools::analyzePrismGraph);
        add(result, "export_prism_graph", "Writes a Prism row graph as a TSV artifact for Python/DuckDB/networkx analysis; graph rows are never returned inline.", schema(
                required("session_id", "graph_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID."),
                prop("format", "string", "edges_tsv or nodes_tsv. Defaults to edges_tsv."),
                prop("output_name", "string", "Optional relative TSV artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact.")),
                graphTools::exportPrismGraph);
        add(result, "create_prism_graph_neighborhood_row_set", "Creates a compact Prism row-set summary for rows within max_depth graph hops of a center row.", schema(
                required("session_id", "graph_id", "center_row_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("graph_id", "string", "Prism row graph ID."),
                prop("center_row_id", "string", "Center Prism row ID."),
                prop("max_depth", "integer", "Maximum graph-hop radius. Defaults to 1 for direct neighbors."),
                prop("include_center", "boolean", "Whether to include the center row. Defaults to true."),
                prop("create_shell_grouping", "boolean", "Also create an exclusive grouping shell_0, shell_1, ... by graph-hop distance. Defaults to false."),
                prop("shell_grouping_id", "string", "Optional grouping ID for distance shells. Defaults to <row_set_id>_shells when shell grouping is requested; collisions fail."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                args -> prism.createGraphNeighborhoodRowSet(new CreatePrismGraphNeighborhoodRowSetRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "graph_id"),
                        requiredString(args, "center_row_id"),
                        optionalInt(args, "max_depth", 1),
                        optionalBoolean(args, "include_center", true),
                        optionalBoolean(args, "create_shell_grouping", false),
                        optionalString(args, "shell_grouping_id", null),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null))));
        add(result, "discover_prism_scaffolds", "Mines compact scaffold candidates from a managed Prism row set and stores a discovery handle for drill-down.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Prism row set ID. Defaults to all."),
                prop("discovery_id", "string", "Optional reusable scaffold discovery ID."),
                prop("neighbor_count", "integer", "Nearest-neighbor count for scaffold discovery. Defaults to 4."),
                prop("min_neighbor_similarity", "number", "Minimum FFP512 neighbor similarity. Defaults to 0.15."),
                prop("max_seeds", "integer", "Maximum discovery seeds. Defaults to all rows."),
                prop("min_scaffold_heavy_atoms", "integer", "Minimum scaffold heavy atoms. Defaults to 5."),
                prop("min_support", "integer", "Minimum candidate support. Defaults to 2."),
                prop("offset", "integer", "Zero-based candidate offset."),
                prop("limit", "integer", "Maximum candidates returned. Defaults to 20."),
                prop("example_limit", "integer", "Maximum example row IDs per candidate. Defaults to 3."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                scaffoldSarTools::discoverPrismScaffolds);
        add(result, "analyze_prism_scaffold", "Analyzes a concrete scaffold substructure against a Prism row set. Extra bonds leaving matched scaffold atoms become exit vectors; mapped atoms such as [cH:1] can be labeled with exit_atom_map_labels.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Prism row set ID. Defaults to all."),
                prop("scaffold_smiles", "string", "Concrete scaffold SMILES substructure. Required unless discovery_id/candidate_id is supplied. Atom maps may label scaffold atoms, e.g. [cH:1]1ccc(N[C:2](=O)N)cc1."),
                prop("discovery_id", "string", "Stored scaffold discovery ID."),
                prop("candidate_id", "string", "Candidate ID from discover_prism_scaffolds, e.g. scaffold_1."),
                prop("scaffold_analysis_id", "string", "Optional reusable scaffold analysis ID."),
                prop("exit_atom_map_labels", "object", "Optional map from SMILES atom-map numbers to exit-vector labels, e.g. {\"1\":\"cap\",\"2\":\"tail\"}. Maps must be on actual scaffold atoms."),
                prop("exit_atom_labels", "object", "Optional fallback map from zero-based scaffold atom indices to labels."),
                prop("include_unmatched_buckets", "boolean", "Whether top bucket summaries include unmatched rows. Defaults to false; unmatchedCount remains reported."),
                prop("context_radius", "integer", "Context radius for scaffold-to-compound splitting. Defaults to 1."),
                prop("top_substituent_limit", "integer", "Top buckets returned per observed exit vector. Defaults to 5."),
                prop("example_limit", "integer", "Maximum example row IDs per bucket and match diagnostics. Defaults to 3."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                scaffoldSarTools::analyzePrismScaffold);
        add(result, "get_prism_scaffold_projection", "Returns compact 1D/2D/n-dimensional scaffold substituent buckets with optional Prism column summaries and matched-context diversity metadata.", schema(
                required("scaffold_analysis_id"),
                prop("scaffold_analysis_id", "string", "Scaffold analysis ID returned by analyze_prism_scaffold."),
                arrayProp("scaffold_atoms", "integer", "Zero-based scaffold atom indices defining the projection dimensions."),
                arrayProp("scaffold_atom_maps", "integer", "SMILES atom-map numbers defining the projection dimensions. If omitted and labeled mapped atoms exist, those labeled atoms are used."),
                prop("include_unmatched_buckets", "boolean", "Whether projection rows include unmatched buckets. Defaults to false; suppressedUnmatchedBucketCount reports hidden rows."),
                prop("offset", "integer", "Zero-based bucket offset."),
                prop("limit", "integer", "Maximum buckets returned. Defaults to 50."),
                prop("example_limit", "integer", "Maximum example row IDs per bucket. Defaults to 3."),
                arrayProp("column_ids", "string", "Optional Prism runtime columns summarized for each returned bucket."),
                prop("threshold", "number", "Optional numeric threshold for hit counts/rates in column summaries."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("top_values_limit", "integer", "Top categorical values per column summary. Defaults to 5."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                scaffoldSarTools::getPrismScaffoldProjection);
        add(result, "create_prism_scaffold_bucket_row_set", "Creates a Prism row set from a scaffold projection bucket key returned by get_prism_scaffold_projection.", schema(
                required("scaffold_analysis_id", "bucket_key"),
                prop("scaffold_analysis_id", "string", "Scaffold analysis ID returned by analyze_prism_scaffold."),
                arrayProp("scaffold_atoms", "integer", "The same zero-based scaffold atom indices used to create the projection."),
                arrayProp("scaffold_atom_maps", "integer", "The same SMILES atom-map numbers used to create the projection."),
                prop("include_unmatched_buckets", "boolean", "Set true only for bucket keys containing unmatched rows."),
                prop("bucket_key", "string", "Bucket key returned by get_prism_scaffold_projection."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                scaffoldSarTools::createPrismScaffoldBucketRowSet);
        add(result, "export_prism_scaffold_projection", "Writes a full scaffold projection bucket table as a TSV artifact; rows are never returned inline.", schema(
                required("scaffold_analysis_id"),
                prop("scaffold_analysis_id", "string", "Scaffold analysis ID returned by analyze_prism_scaffold."),
                arrayProp("scaffold_atoms", "integer", "Zero-based scaffold atom indices defining the projection dimensions."),
                arrayProp("scaffold_atom_maps", "integer", "SMILES atom-map numbers defining the projection dimensions."),
                prop("include_unmatched_buckets", "boolean", "Whether TSV includes unmatched buckets. Defaults to false."),
                prop("example_limit", "integer", "Maximum example row IDs included in the TSV. Defaults to 3."),
                prop("output_name", "string", "Optional relative TSV artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact.")),
                scaffoldSarTools::exportPrismScaffoldProjection);
        add(result, "mine_prism_mmp_graph", "Mines a matched molecular pair network from a Prism structure column and publishes it as a Prism row graph.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Source Prism row set. Defaults to all."),
                prop("structure_column_id", "string", "Structure column ID. Defaults to the first structure column."),
                prop("value_column_id", "string", "Optional numeric value column used for edge deltas."),
                prop("graph_id", "string", "Optional output graph ID."),
                prop("label", "string", "Optional graph label."),
                prop("max_cuts", "integer", "Maximum cuts, 1 or 2. Defaults to 1 for agent-facing MMP graphs."),
                prop("min_transform_support", "integer", "Minimum transform support. Defaults to 1 for agent-facing MMP graphs."),
                prop("max_variable_heavy_atoms", "integer", "Maximum variable fragment heavy atoms. Defaults to 16 for agent-facing MMP graphs."),
                prop("max_variable_to_mol_heavy_atom_fraction", "number", "Maximum variable fragment fraction. Defaults to 0.3 for agent-facing MMP graphs."),
                prop("max_fragmentation_records_per_compound", "integer", "Per-compound fragmentation cap."),
                prop("max_pairs_per_key", "integer", "Maximum pairs emitted for one constant key.")),
                args -> prism.mineMmpGraph(new MinePrismMmpGraphRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "row_set_id", "all"),
                        optionalString(args, "structure_column_id", null),
                        optionalString(args, "value_column_id", null),
                        optionalString(args, "graph_id", null),
                        optionalString(args, "label", null),
                        optionalInt(args, "max_cuts", 1),
                        optionalInt(args, "min_transform_support", 1),
                        optionalInt(args, "max_variable_heavy_atoms", 16),
                        optionalDouble(args, "max_variable_to_mol_heavy_atom_fraction", 0.3),
                        optionalInteger(args, "max_fragmentation_records_per_compound"),
                        optionalInteger(args, "max_pairs_per_key"))));
        add(result, "mine_prism_similarity_graph", "Mines a chemical similarity network from a Prism structure column and publishes it as a Prism row graph. Defaults to SkeletonSpheres hybrid top-5 plus similarity >= 0.85.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Source Prism row set. Defaults to all."),
                prop("structure_column_id", "string", "Structure column ID. Defaults to the first structure column."),
                prop("graph_id", "string", "Optional output graph ID."),
                prop("label", "string", "Optional graph label."),
                prop("descriptor", "string", "Descriptor name. Only skelspheres is supported; defaults to skelspheres."),
                prop("mode", "string", "knn, threshold, or hybrid. Defaults to hybrid."),
                prop("neighbor_count", "integer", "Top neighbors per row for knn/hybrid modes. Defaults to 5."),
                prop("similarity_threshold", "number", "Similarity cutoff for threshold/hybrid modes. Defaults to 0.85."),
                prop("mutual_knn_only", "boolean", "Whether to keep only edges where both rows are in each other's top-k list. Defaults to false."),
                prop("max_edges", "integer", "Optional safety cap; mining fails if the graph would exceed this many edges.")),
                args -> prism.mineSimilarityGraph(new MinePrismSimilarityGraphRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "row_set_id", "all"),
                        optionalString(args, "structure_column_id", null),
                        optionalString(args, "graph_id", null),
                        optionalString(args, "label", null),
                        optionalString(args, "descriptor", null),
                        optionalString(args, "mode", null),
                        optionalInteger(args, "neighbor_count"),
                        optionalDouble(args, "similarity_threshold", null),
                        optionalNullableBoolean(args, "mutual_knn_only"),
                        optionalInteger(args, "max_edges"))));
        add(result, "summarize_prism_row_set_by_columns", "Summarizes runtime Prism columns for one row set without materializing a repository. Numeric columns return distribution statistics; other columns return top formatted values.", schema(
                required("session_id", "row_set_id", "column_ids"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Prism row set ID."),
                arrayProp("column_ids", "string", "Runtime Prism column IDs to summarize."),
                prop("threshold", "number", "Optional numeric threshold applied to numeric columns."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("top_values_limit", "integer", "Maximum top values for categorical/text columns. Defaults to 10."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::summarizePrismRowSetByColumns);
        add(result, "summarize_prism_grouping_by_columns", "Summarizes runtime Prism columns for paged groups in a Prism grouping without materializing a repository.", schema(
                required("session_id", "grouping_id", "column_ids"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("grouping_id", "string", "Prism grouping ID."),
                arrayProp("column_ids", "string", "Runtime Prism column IDs to summarize within each group."),
                prop("include_singletons", "boolean", "Whether to include singleton groups. Defaults to false."),
                prop("offset", "integer", "Zero-based group offset after filtering and size sorting. Defaults to 0."),
                prop("limit", "integer", "Maximum groups returned. Defaults to 50."),
                prop("threshold", "number", "Optional numeric threshold applied to numeric columns."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("top_values_limit", "integer", "Maximum top values for categorical/text columns. Defaults to 10."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::summarizePrismGroupingByColumns);
        add(result, "cluster_prism_row_set", "Clusters structures from a managed Prism row set, publishes a reusable grouping, and optionally exposes its facet and similarity column.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("row_set_id", "string", "Source Prism row set. Defaults to all."),
                prop("analysis_id", "string", "Optional unique analysis ID."),
                prop("label", "string", "Optional analysis and column label."),
                prop("descriptor", "string", "Descriptor name. Defaults to skelspheres."),
                prop("threshold", "number", "Representative similarity threshold. Defaults to 0.80."),
                prop("max_cross_neighbors", "integer", "Maximum nearest cross-cluster neighbors. Defaults to 5."),
                prop("publish_columns", "boolean", "Show the grouping facet and publish the similarity column. Defaults to true.")),
                args -> prism.clusterRowSet(new ClusterPrismRowSetRequest(
                        requiredString(args, "session_id"),
                        optionalString(args, "row_set_id", "all"),
                        optionalString(args, "analysis_id", null),
                        optionalString(args, "label", null),
                        optionalString(args, "descriptor", null),
                        optionalDouble(args, "threshold", null),
                        optionalInt(args, "max_cross_neighbors", 5),
                        optionalNullableBoolean(args, "publish_columns"))));
        add(result, "list_prism_analyses", "Lists provider-managed detailed analysis artifacts associated with one Prism session.", schema(
                required("session_id"),
                prop("session_id", "string", "Managed Prism session ID.")),
                args -> prism.listAnalyses(requiredString(args, "session_id")));
        add(result, "get_prism_clustering", "Returns a paged rich clustering summary from the Structurized artifact associated with a Prism grouping.", schema(
                required("session_id", "analysis_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("analysis_id", "string", "Clustering artifact and grouping ID."),
                prop("include_singletons", "boolean", "Include singleton clusters. Defaults to true."),
                prop("offset", "integer", "Zero-based cluster offset."),
                prop("limit", "integer", "Maximum clusters returned.")),
                args -> prism.getClustering(
                        requiredString(args, "session_id"),
                        requiredString(args, "analysis_id"),
                        optionalBoolean(args, "include_singletons", true),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "get_prism_cluster_members", "Returns paged Prism rows for one cluster while preserving dataframe row identity.", schema(
                required("session_id", "analysis_id", "cluster_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("analysis_id", "string", "Clustering artifact and grouping ID."),
                prop("cluster_id", "string", "Cluster ID such as cluster_1."),
                prop("offset", "integer", "Zero-based member offset."),
                prop("limit", "integer", "Maximum members returned.")),
                args -> prism.getClusterMembers(
                        requiredString(args, "session_id"),
                        requiredString(args, "analysis_id"),
                        requiredString(args, "cluster_id"),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "create_prism_cluster_row_set", "Compatibility tool that creates a Prism row set from one clustering-backed group.", schema(
                required("session_id", "analysis_id", "cluster_id"),
                prop("session_id", "string", "Managed Prism session ID."),
                prop("analysis_id", "string", "Session-owned clustering analysis ID."),
                prop("cluster_id", "string", "Cluster ID such as cluster_1."),
                prop("row_set_id", "string", "Optional output row set ID."),
                prop("name", "string", "Optional row set name."),
                prop("description", "string", "Optional row set description.")),
                args -> prism.createClusterRowSet(new CreatePrismClusterRowSetRequest(
                        requiredString(args, "session_id"),
                        requiredString(args, "analysis_id"),
                        requiredString(args, "cluster_id"),
                        optionalString(args, "row_set_id", null),
                        optionalString(args, "name", null),
                        optionalString(args, "description", null))));
        add(result, "get_prism_dataset_info", "Returns counts, subject sets, and endpoint summaries for one PRISM dataset/session.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID.")),
                args -> prism.getDatasetInfo(requiredString(args, "dataset_id")));
        add(result, "list_prism_subject_sets", "Lists discoverable PRISM subject sets with subject counts.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID.")),
                args -> prism.listSubjectSets(requiredString(args, "dataset_id")));
        add(result, "list_prism_subjects", "Lists PRISM subjects, optionally restricted to a subject set.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                prop("subject_set_id", "string", "Optional subject set ID."),
                prop("offset", "integer", "Zero-based offset."),
                prop("limit", "integer", "Maximum records to return."),
                prop("include_metadata", "boolean", "Whether to include subject metadata.")),
                args -> prism.listSubjects(
                        requiredString(args, "dataset_id"),
                        optionalString(args, "subject_set_id", null),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100),
                        optionalBoolean(args, "include_metadata", false)));
        add(result, "get_prism_subject", "Returns one PRISM subject record summary with metadata.", schema(
                required("dataset_id", "subject_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                prop("subject_id", "string", "PRISM subject ID.")),
                args -> prism.getSubject(requiredString(args, "dataset_id"), requiredString(args, "subject_id")));
        add(result, "list_prism_endpoints", "Lists PRISM endpoint definitions and concise endpoint metadata.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID.")),
                args -> prism.listEndpoints(requiredString(args, "dataset_id")));
        add(result, "get_prism_endpoint_values", "Fetches PRISM endpoint values for selected subjects and endpoints.", schema(
                required("dataset_id", "subject_ids", "endpoint_ids"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                arrayProp("subject_ids", "string", "PRISM subject IDs."),
                arrayProp("endpoint_ids", "string", "PRISM endpoint IDs.")),
                args -> prism.getEndpointValues(
                        requiredString(args, "dataset_id"),
                        stringList(args, "subject_ids"),
                        stringList(args, "endpoint_ids")));
        add(result, "create_endpoint_selection", "Creates a server-side selection from Prism endpoint mean and/or endpoint measurement-date filters, optionally scoped to an existing selection.", schema(
                required("dataset_id", "endpoint_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                prop("repository_id", "string", "Repository ID to scan. Required unless base_selection_id is provided."),
                prop("base_selection_id", "string", "Optional existing selection to filter instead of scanning a repository."),
                prop("endpoint_id", "string", "Prism endpoint ID."),
                prop("operator", "string", "Optional numeric mean operator: gt, gte, lt, lte, or eq."),
                prop("value", "number", "Optional numeric threshold compared against endpoint mean."),
                prop("measurement_date_field", "string", "first or last measurement date. Defaults to last."),
                prop("measured_after", "string", "Inclusive measurement date lower bound, YYYY-MM-DD or ISO instant."),
                prop("measured_before", "string", "Inclusive measurement date upper bound, YYYY-MM-DD or ISO instant."),
                prop("require_measured_date", "boolean", "Whether missing dates are excluded when date bounds are supplied. Defaults to true."),
                prop("selection_id", "string", "Optional output selection ID.")),
                this::createEndpointSelection);
        add(result, "create_subject_measurement_date_selection", "Creates a server-side selection from subject-level first/last measurement dates aggregated across all or selected Prism endpoints.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                prop("repository_id", "string", "Repository ID to scan. Required unless base_selection_id is provided."),
                prop("base_selection_id", "string", "Optional existing selection to filter instead of scanning a repository."),
                arrayProp("endpoint_ids", "string", "Optional Prism endpoint IDs to aggregate. Defaults to all endpoints."),
                prop("subject_date_field", "string", "first or last subject aggregate measurement date. Defaults to last."),
                prop("measured_after", "string", "Inclusive aggregate date lower bound, YYYY-MM-DD or ISO instant."),
                prop("measured_before", "string", "Inclusive aggregate date upper bound, YYYY-MM-DD or ISO instant."),
                prop("selection_id", "string", "Optional output selection ID.")),
                this::createSubjectMeasurementDateSelection);
        add(result, "materialize_prism_subject_set", "Materializes a PRISM subject set into a normal AI chemistry repository.", schema(
                required("dataset_id"),
                prop("dataset_id", "string", "Loaded PRISM dataset ID."),
                prop("subject_set_id", "string", "Optional PRISM subject set ID. Omit for all subjects."),
                prop("repository_id", "string", "Optional target AI repository ID."),
                prop("label", "string", "Optional target repository label.")),
                args -> prism.materializeSubjectSet(new MaterializePrismSubjectSetRequest(
                        requiredString(args, "dataset_id"),
                        optionalString(args, "subject_set_id", null),
                        optionalString(args, "repository_id", null),
                        optionalString(args, "label", null))));
        add(result, "cluster_structures", "Runs fast deterministic greedy clustering with OpenChemLib SkelSpheres descriptors.", schema(
                required("repository_id"),
                prop("clustering_id", "string", "Optional clustering result ID."),
                prop("repository_id", "string", "Repository ID to cluster."),
                arrayProp("structure_ids", "string", "Optional selected structure IDs."),
                prop("descriptor", "string", "Descriptor name. Default skelspheres."),
                prop("threshold", "number", "Representative similarity threshold. Default 0.80."),
                prop("max_cross_neighbors", "integer", "Maximum nearest cross-cluster neighbors per cluster.")),
                args -> clusterings.clusterStructures(
                        optionalString(args, "clustering_id", null),
                        requiredString(args, "repository_id"),
                        optionalStringList(args, "structure_ids"),
                        optionalString(args, "descriptor", null),
                        optionalDouble(args, "threshold", null),
                        optionalInt(args, "max_cross_neighbors", 5)));
        add(result, "list_clusterings", "Lists stored rough similarity clustering results.", schema(),
                args -> clusterings.listClusterings());
        add(result, "get_clustering", "Returns a clustering summary with paged representative-led cluster summaries.", schema(
                required("clustering_id"),
                prop("clustering_id", "string", "Stored clustering ID."),
                prop("include_singletons", "boolean", "Whether to include singleton cluster summaries."),
                prop("offset", "integer", "Zero-based cluster offset."),
                prop("limit", "integer", "Maximum clusters returned.")),
                args -> clusterings.getClustering(
                        requiredString(args, "clustering_id"),
                        optionalBoolean(args, "include_singletons", true),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "get_cluster", "Returns one compact similarity cluster summary with example members and nearest cross-cluster neighbors.", schema(
                required("clustering_id", "cluster_id"),
                prop("clustering_id", "string", "Stored clustering ID."),
                prop("cluster_id", "string", "Cluster ID such as cluster_1.")),
                args -> clusterings.getCluster(
                        requiredString(args, "clustering_id"),
                        requiredString(args, "cluster_id")));
        add(result, "get_cluster_members", "Returns a paged full membership list for one similarity cluster.", schema(
                required("clustering_id", "cluster_id"),
                prop("clustering_id", "string", "Stored clustering ID."),
                prop("cluster_id", "string", "Cluster ID such as cluster_1."),
                prop("offset", "integer", "Zero-based member offset."),
                prop("limit", "integer", "Maximum members returned."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported."),
                prop("create_selection", "boolean", "Whether to store all cluster members as a server-side selection."),
                prop("selection_id", "string", "Optional selection ID when create_selection is true.")),
                this::getClusterMembers);
        add(result, "get_selection", "Returns metadata and a few examples for a server-side structure selection.", schema(
                required("selection_id"),
                prop("selection_id", "string", "Stored selection ID.")),
                args -> selections.getSelection(requiredString(args, "selection_id")));
        add(result, "combine_selections", "Creates a new server-side selection from existing selections using union/merge, intersect, or subtract; returns metadata and a few examples.", schema(
                required("operation", "selection_ids"),
                prop("operation", "string", "union, merge, intersect, or subtract."),
                arrayProp("selection_ids", "string", "Input selection IDs. For subtract, the first selection is the minuend."),
                prop("selection_id", "string", "Optional output selection ID.")),
                args -> selections.combineSelections(
                        optionalString(args, "selection_id", null),
                        requiredString(args, "operation"),
                        stringList(args, "selection_ids")));
        add(result, "get_selection_members", "Returns paged members of a server-side structure selection.", schema(
                required("selection_id"),
                prop("selection_id", "string", "Stored selection ID."),
                prop("offset", "integer", "Zero-based member offset."),
                prop("limit", "integer", "Maximum members returned."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::getSelectionMembers);
        add(result, "summarize_selection_by_endpoint", "Summarizes numeric Prism endpoints for a server-side structure selection.", schema(
                required("selection_id", "dataset_id", "endpoint_ids"),
                prop("selection_id", "string", "Stored selection ID."),
                prop("dataset_id", "string", "Loaded Prism dataset ID."),
                arrayProp("endpoint_ids", "string", "Numeric Prism endpoint IDs."),
                prop("threshold", "number", "Optional activity threshold."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::summarizeSelectionByEndpoint);
        add(result, "export_selection_table", "Writes a TSV artifact for a server-side selection, with optional PRISM endpoint long rows and decomposition fragment columns for Python/DuckDB analysis.", schema(
                required("selection_id"),
                prop("selection_id", "string", "Stored selection ID to export."),
                prop("dataset_id", "string", "Loaded Prism dataset ID. Required when endpoint_ids is provided."),
                arrayProp("endpoint_ids", "string", "Optional Prism endpoint IDs to export in long format."),
                prop("decomposition_evaluation_id", "string", "Optional decomposition evaluation whose terminal fragments become wide columns."),
                prop("include_smiles", "boolean", "Whether to include canonical_smiles. Defaults to true."),
                prop("include_fields", "boolean", "Whether to include selection member fields as field_* columns. Defaults to false."),
                prop("include_subject_measurement_dates", "boolean", "Whether to add subject_first_measurement, subject_last_measurement, and subject_measurement_endpoint_count columns aggregated across all Prism endpoints. Requires dataset_id."),
                prop("output_name", "string", "Optional relative TSV artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only tsv is supported for this tool.")),
                this::exportSelectionTable);
        add(result, "summarize_clusters_by_endpoint", "Summarizes one numeric Prism endpoint for paged non-singleton clusters without returning member IDs. Defaults to include_singletons:false, offset:0, limit:50; use output_target:file for the full filtered table.", schema(
                required("clustering_id", "dataset_id", "endpoint_id"),
                prop("clustering_id", "string", "Stored clustering ID."),
                prop("dataset_id", "string", "Loaded Prism dataset ID."),
                prop("endpoint_id", "string", "Numeric Prism endpoint ID."),
                prop("include_singletons", "boolean", "Whether to include singleton clusters. Defaults to false."),
                prop("offset", "integer", "Zero-based cluster offset after filtering and size sorting. Defaults to 0."),
                prop("limit", "integer", "Maximum clusters returned in response mode. Defaults to 50."),
                prop("threshold", "number", "Optional activity threshold."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::summarizeClustersByEndpoint);
        add(result, "validate_decomposition_config", "Validates a decomposition config JSON object without storing it. Checks schema, SMARTS compilation, zero-based atomLabels indices, and query-graph label partitioning; dataset-specific success still requires evaluate_decomposition.", schema(
                prop("config", "object", "Decomposition config object: version plus ordered rules[]."),
                prop("config_json", "string", "Decomposition config JSON string.")),
                args -> decompositions.validateConfig(decompositionConfig(args)));
        add(result, "create_decomposition_config", "Stores a session-scoped recursive decomposition config. Rule shape: rules[].{id,labelToSplit,smarts,atomLabels}; atomLabels keys are zero-based SMARTS query atom indices, not atom-map numbers.", schema(
                prop("config_id", "string", "Optional decomposition config ID."),
                prop("label", "string", "Optional display label."),
                prop("config", "object", "Decomposition config object: version plus ordered rules[]."),
                prop("config_json", "string", "Decomposition config JSON string.")),
                args -> decompositions.createConfig(
                        optionalString(args, "config_id", null),
                        optionalString(args, "label", null),
                        decompositionConfig(args)));
        add(result, "list_decomposition_configs", "Lists stored decomposition config metadata.", schema(),
                args -> decompositions.listConfigs());
        add(result, "get_decomposition_config", "Returns a stored decomposition config and metadata.", schema(
                required("config_id"),
                prop("config_id", "string", "Stored decomposition config ID."),
                prop("include_config", "boolean", "Whether to include the full config object.")),
                args -> decompositions.getConfig(
                        requiredString(args, "config_id"),
                        optionalBoolean(args, "include_config", true)));
        add(result, "evaluate_decomposition", "Evaluates a decomposition config against a repository, explicit structure_ids, or a server-side selection_id and reports coverage, no-match, non-unique, and invalid-assignment outcomes.", schema(
                required("config_id"),
                prop("evaluation_id", "string", "Optional evaluation ID."),
                prop("config_id", "string", "Stored decomposition config ID."),
                prop("repository_id", "string", "Repository ID to evaluate. Required unless selection_id is provided."),
                arrayProp("structure_ids", "string", "Optional selected structure IDs for repository mode."),
                prop("selection_id", "string", "Optional server-side structure selection to evaluate instead of raw structure_ids.")),
                this::evaluateDecomposition);
        add(result, "get_decomposition_evaluation", "Returns decomposition evaluation summary and optional paged molecule results.", schema(
                required("evaluation_id"),
                prop("evaluation_id", "string", "Stored decomposition evaluation ID."),
                prop("include_results", "boolean", "Whether to include molecule summaries."),
                prop("offset", "integer", "Zero-based result offset."),
                prop("limit", "integer", "Maximum result count.")),
                args -> decompositions.getEvaluation(
                        requiredString(args, "evaluation_id"),
                        optionalBoolean(args, "include_results", false),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "get_decomposition_result", "Returns one molecule's full decomposition tree with atom IDs, rule attempts, cut bonds, and boundary bonds.", schema(
                required("evaluation_id", "structure_id"),
                prop("evaluation_id", "string", "Stored decomposition evaluation ID."),
                prop("structure_id", "string", "Structure ID inside the evaluated repository.")),
                args -> decompositions.getResult(
                        requiredString(args, "evaluation_id"),
                        requiredString(args, "structure_id")));
        add(result, "get_decomposition_failures", "Returns non-successful decomposition molecules grouped by status; use this after evaluate_decomposition to inspect no-match, non-unique, and invalid-assignment witnesses.", schema(
                required("evaluation_id"),
                prop("evaluation_id", "string", "Stored decomposition evaluation ID."),
                prop("offset", "integer", "Zero-based group offset."),
                prop("limit", "integer", "Maximum result count per group.")),
                args -> decompositions.getFailures(
                        requiredString(args, "evaluation_id"),
                        optionalInt(args, "offset", 0),
                        optionalInt(args, "limit", 100)));
        add(result, "get_decomposition_fragment_summary", "Returns compact terminal fragment statistics by default; request details for signatures and atom arrays.", schema(
                required("evaluation_id"),
                prop("evaluation_id", "string", "Stored decomposition evaluation ID."),
                prop("offset", "integer", "Zero-based summary row offset."),
                prop("limit", "integer", "Maximum summary rows."),
                prop("include_details", "boolean", "Whether to include full signatures and atom index arrays."),
                prop("example_limit", "integer", "Maximum examples retained per row in compact mode."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::decompositionFragmentSummary);
        add(result, "get_decomposition_fragment_histogram", "Returns a ranked distinct-fragment histogram for one decomposition path or label, with optional Prism endpoint statistics. Defaults to offset:0, limit:50, example_limit:3; use output_target:file for the full compact table.", schema(
                required("evaluation_id"),
                prop("evaluation_id", "string", "Stored decomposition evaluation ID."),
                prop("path", "string", "Terminal decomposition path such as root.cap."),
                prop("label", "string", "Terminal decomposition label; ambiguous labels require path instead."),
                prop("offset", "integer", "Zero-based histogram row offset."),
                prop("limit", "integer", "Maximum histogram rows returned in response mode."),
                prop("example_limit", "integer", "Maximum example structure IDs retained per fragment row."),
                prop("dataset_id", "string", "Optional loaded Prism dataset ID for endpoint stats."),
                prop("endpoint_id", "string", "Optional numeric Prism endpoint ID for endpoint stats."),
                prop("threshold", "number", "Optional activity threshold."),
                prop("threshold_direction", "string", "gte or lte. Defaults to gte."),
                prop("output_target", "string", "response or file. Defaults to response."),
                prop("output_name", "string", "Optional relative artifact path inside the managed artifact directory."),
                prop("overwrite", "boolean", "Whether to overwrite an existing caller-named artifact."),
                prop("format", "string", "Artifact format. Only json is supported.")),
                this::decompositionFragmentHistogram);
        return result;
    }


    private DecompositionAiService.DecompositionEvaluationRecord evaluateDecomposition(ObjectNode args) {
        String evaluationId = optionalString(args, "evaluation_id", null);
        String configId = requiredString(args, "config_id");
        String repositoryId = optionalString(args, "repository_id", null);
        String selectionId = optionalString(args, "selection_id", null);
        List<String> structureIds = optionalStringList(args, "structure_ids");

        if (selectionId != null && !selectionId.isBlank()) {
            if (structureIds != null && !structureIds.isEmpty()) {
                throw new ChemOperationException("invalid_decomposition_scope", "selection_id and structure_ids cannot be provided together.");
            }
            SelectionAiService.StoredSelectionData selection = selections.selectionData(selectionId);
            String selectionRepositoryId = selection.summary().repositoryId();
            if (repositoryId != null && !repositoryId.isBlank() && !repositoryId.trim().equals(selectionRepositoryId)) {
                throw new ChemOperationException("selection_repository_mismatch", "selection_id " + selectionId + " belongs to repository " + selectionRepositoryId + ", not " + repositoryId + ".");
            }
            List<String> selectedStructureIds = selection.members().stream()
                    .map(SelectionMember::structureId)
                    .toList();
            return decompositions.evaluate(evaluationId, configId, selectionRepositoryId, selectedStructureIds);
        }

        if (repositoryId == null || repositoryId.isBlank()) {
            throw new ChemOperationException("invalid_decomposition_scope", "repository_id is required unless selection_id is provided.");
        }
        return decompositions.evaluate(evaluationId, configId, repositoryId, structureIds);
    }


    private ToolGuide toolGuide(ObjectNode args) {
        String topic = optionalString(args, "topic", "overview");
        String normalized = topic == null || topic.isBlank() ? "overview" : topic.trim().toLowerCase();
        return new ToolGuide(normalized, switch (normalized) {
            case "overview" -> """
                    # Structurized MCP Guide
                    Start compact: use counts, summaries, selections, and endpoint aggregations before requesting row-level detail.
                    Main flows: open_prism_pack -> describe_prism_session_for_agent -> create_prism_column_row_set -> summarize_prism_row_set_by_columns, mine_prism_mmp_graph, or mine_prism_similarity_graph -> analyze_prism_graph/inspect_prism_graph_neighborhood for session-native analysis; open_prism_dataset -> materialize_prism_subject_set -> cluster_structures remains available for standalone repository workflows; search_substructure(create_selection:true) and create_endpoint_selection -> combine_selections when needed -> summarize_selection_by_endpoint, evaluate_decomposition(selection_id), or export_selection_table; create_decomposition_config -> evaluate_decomposition -> get_decomposition_fragment_histogram.
                    Use output_target:file for large drill-downs and list_artifacts/get_artifact_info to recover artifact paths. Use topic:mmp_graph_workflow and topic:scaffold_sar_workflow for graph and scaffold SAR strategies.
                    """;
            case "payload_hygiene" -> """
                    # Payload Hygiene
                    search_substructure defaults to output_mode:count. Request output_mode:ids for compact rows and output_mode:full only when atom mappings are needed.
                    get_clustering and get_cluster are summaries; get_cluster_members and get_selection_members are paged drill-down tools. inspect_prism_graph_neighborhood defaults to output_mode:stats; request compact or full explicitly.
                    Prefer create_selection:true plus summarize_selection_by_endpoint when analyzing endpoint distributions for search hits or cluster members. Use create_endpoint_selection for numeric potency/property and endpoint measurement-date filters without fetching value rows. Use create_subject_measurement_date_selection to find subjects whose first or last measured endpoint date is recent. Use combine_selections for union/merge, intersect, and subtract without copying IDs into context. evaluate_decomposition accepts selection_id, and export_selection_table writes TSV artifacts for Python/DuckDB without inline rows.
                    """;
            case "prism_workflow" -> """
                    # Prism Workflow
                    Open a PrismPack directory with open_prism_pack, then call describe_prism_session_for_agent or list_prism_columns to inspect runtime columns, endpoint-like columns, structures, and row sets. Use create_prism_column_row_set to define an analysis scope without changing visible table filters, summarize_prism_row_set_by_columns for compact endpoint/category summaries, then cluster_prism_row_set to publish a reusable grouping into the same session.
                    Open a TSV bundle with open_prism_dataset when canonical TSV subject sets and endpoint records are needed; inspect endpoints and subject sets, then materialize a subject set into a chemistry repository.
                    Use repository IDs returned by materialize_prism_subject_set for legacy structure search, clustering, and decomposition evaluation.
                    Endpoint summaries, create_endpoint_selection, create_subject_measurement_date_selection, and export_selection_table use Prism subject IDs preserved in materialized structure fields. create_endpoint_selection creates reusable potency/property/date subsets with operators gt/gte/lt/lte/eq and optional first/last measurement date bounds. create_subject_measurement_date_selection aggregates first/last measurement dates across all or selected endpoints and is the preferred way to identify likely newest compounds. export_selection_table writes long endpoint rows with first_measurement/last_measurement and optional subject aggregate measurement date columns.
                    """;
            case "clustering_workflow" -> """
                    # Clustering Workflow
                    For a managed Prism session, create a scope with create_prism_column_row_set and run cluster_prism_row_set with a SkelSpheres threshold around 0.75-0.85. Structurized retains the rich artifact while Prism receives a reusable grouping; publish_columns also shows its categorical facet and adds representative similarity.
                    Use list_prism_groupings and get_prism_grouping for provider-neutral inspection. Use summarize_prism_grouping_by_columns to compare runtime endpoint/category columns across groups without returning member rows. Use get_prism_clustering and get_prism_cluster_members for rich clustering details. Call create_prism_group_row_set, or the compatibility create_prism_cluster_row_set, only for groups that should become named reusable scopes.
                    The repository-based cluster_structures, get_clustering, get_cluster_members, and summarize_clusters_by_endpoint tools remain available for standalone chemistry repositories and legacy materialized-dataset workflows.
                    """;
            case "mmp_graph_workflow" -> """
                    # MMP Graph Workflow
                    Use mine_prism_mmp_graph on managed Prism sessions for strict matched-pair SAR. Recommended default profile is max_cuts:1, min_transform_support:1, max_variable_heavy_atoms:16, max_variable_to_mol_heavy_atom_fraction:0.3; omit these arguments unless there is a specific reason to change them. Use mine_prism_similarity_graph for broader related-chemistry maps that connect compounds by SkeletonSpheres similarity even when no MMP edge exists; recommended defaults are mode:hybrid, neighbor_count:5, similarity_threshold:0.85.
                    Start with analyze_prism_graph for global orientation: edge count, connected coverage, isolated source rows, degree statistics, and high-degree rows. Then call inspect_prism_graph_neighborhood with output_mode:stats for a row, output_mode:collapsed for one readable row per neighbor, output_mode:compact for bounded raw neighbor transforms, or output_mode:full only for detailed edge properties.
                    Use find_prism_graph_shortest_path for cheap questions like "are these compounds connected and how many MMP hops apart?" It defaults to output_mode:stats for only connectivity and distance; use output_mode:compact with include_path:true for one deterministic short path with bounded readable transform examples. Reserve output_mode:full for debugging because it includes full row fields and graph metadata.
                    Use summarize_prism_mmp_transforms to rank readable transforms by support or delta without returning raw edge lists. Mine against pIC50, LipE, or selectivity columns when delta signs should have SAR meaning; raw IC50/nM columns produce raw numeric deltas.
                    Use create_prism_graph_neighborhood_row_set to turn graph-radius neighborhoods into reusable Prism row sets: max_depth:1 for direct analogs, max_depth:2 or 3 for broader local SAR clouds. Set create_shell_grouping:true when you also want shell_0/shell_1/... graph-distance groups; the returned row-set provenance includes shellGroupingId for get_prism_grouping, summarize_prism_grouping_by_columns, or create_prism_group_row_set. Then call summarize_prism_row_set_by_columns for endpoint/SAR context. Use export_prism_graph with format:edges_tsv or nodes_tsv when Python/DuckDB/networkx should analyze the full graph outside the MCP context; edge TSV includes readable transform columns plus raw IDCodes.
                    """;
            case "scaffold_sar_workflow" -> """
                    # Scaffold SAR Workflow
                    Start from a candidate scaffold_smiles returned by discover_prism_scaffolds, or hand-write a conserved core. Discovered cores are often fully elaborated; trim them toward the smallest conserved core that answers the SAR question to raise exit-vector support.
                    Concepts: draw the conserved core, not dummy attachment points. scaffold_smiles is concrete substructure mode, not SMARTS; when the core matches inside a compound, extra bonds leaving matched scaffold atoms become exit vectors. Simple scaffold atoms may match more-substituted target atoms, and those extra bonds become R-groups.
                    Stable labels: atom maps on actual scaffold atoms may label vectors, e.g. scaffold_smiles:"[cH:1]1ccc(N[C:2](=O)N)cc1", exit_atom_map_labels:{"1":"cap","2":"tail"}. Simple mapped atoms are normalized during matching so bracket-induced hydrogen/valence metadata does not constrain the query. scaffold_atom_maps:[1,2] and zero-based scaffold_atoms select the same positions.
                    Matched-context recipe: 1) analyze_prism_scaffold with a small conserved core and exit_atom_map_labels. 2) get_prism_scaffold_projection(scaffold_atom_maps:[2], column_ids:[endpoint]) for a 1D trend; column_ids may list several endpoints to read potency, selectivity, and liabilities across the same buckets in one call. If row.context.cleanMatchedContext is false or diverseOtherPositionCount is high, treat the endpoint stat as marginal. 3) Add the co-varying position, e.g. scaffold_atom_maps:[1,2], to get a cap x tail matrix; cells with cleanMatchedContext:true are clean matched observations. 4) Promote a useful cell with create_prism_scaffold_bucket_row_set using the same scaffold_atom_maps/scaffold_atoms and bucket_key, then inspect MMP neighborhoods or summarize other endpoints.
                    Interpretation: cleanMatchedContext=true means all other observed exit-vector positions are constant inside that bucket. otherPositionCount is the number of observed vectors outside the projection. diverseOtherPositionCount counts how many of those vary; diverseOtherPositions lists the main confounders.
                    Buckets: none means the position is unsubstituted; multi means multiple or ambiguous attachments at that scaffold atom are reported jointly; unmatched means the scaffold did not match and is suppressed by default. Use include_unmatched_buckets:true only when needed.
                    Zero-hit diagnosis: a hand scaffold must be a conserved substructure. Common causes are wrong ring size, protonation/aromaticity mismatch, over-specific caps, CF2/CF3 mismatch, or including atoms that are actually variable. Prefer the smallest conserved core that still defines the SAR question; smaller cores often give higher-support exit vectors.
                    Export/follow-up: use export_prism_scaffold_projection for full TSV projection tables outside the MCP context.
                    """;
            case "decomposition_rules" -> """
                    # Decomposition Rules
                    Config shape is {version, rules:[{id,labelToSplit,smarts,atomLabels,enabled}]}. labelToSplit:null targets the root molecule; child rules target output labels.
                    atomLabels maps zero-based SMARTS query atom indices to output fragment labels. These keys are not SMARTS atom-map numbers like :1 or :2.
                    Bonds between differently labeled adjacent matched atoms are cut. After cuts, every resulting component must contain exactly one label type; unlabeled atoms are absorbed into their connected labeled component.
                    Amide example: for [C:1](=O)[NX3:2], label the carbon and nitrogen query atoms, e.g. {"0":"acyl","2":"amine"}; leave oxygen unlabeled. The common {"1":"acyl","2":"amine"} labels oxygen and nitrogen and leaves carbon unlabeled, producing one component with multiple label types.
                    validate_decomposition_config checks schema, SMARTS compilation, label index range, and query-graph label partitioning. evaluate_decomposition is still required for molecule-specific matches, ambiguity, and coverage.
                    evaluate_decomposition can evaluate a whole repository, explicit structure_ids, or a server-side selection_id from search_substructure/create_selection workflows.
                    After evaluation, use get_decomposition_fragment_summary to list terminal paths, then get_decomposition_fragment_histogram for ranked distinct fragments at one path or label, optionally joined to Prism endpoint stats.
                    """;
            case "artifact_output" -> """
                    # Artifact Output
                    Large-output tools accept output_target:response or output_target:file. File mode writes JSON under a server-managed artifact directory and returns an artifact receipt instead of the full payload. export_selection_table always writes a TSV artifact and returns a compact receipt/schema.
                    output_name is optional and must be a safe relative path such as series_A/matches.json. Absolute paths, . and .. segments, and symlink traversal are rejected.
                    Existing caller-named files are auto-suffixed unless overwrite:true is provided. Use list_artifacts and get_artifact_info to recover paths.
                    """;
            default -> throw new ChemOperationException("unknown_guide_topic", "Unknown guide topic: " + topic);
        });
    }

    private Object searchSubstructure(ObjectNode args) {
        String outputMode = optionalString(args, "output_mode", SubstructureSearchRequest.OUTPUT_COUNT).trim().toLowerCase();
        int offset = optionalInt(args, "offset", 0);
        int limit = optionalInt(args, "limit", optionalInt(args, "max_results", 50));
        SubstructureSearchRequest request = new SubstructureSearchRequest(
                requiredString(args, "query"),
                optionalString(args, "query_type", "smiles"),
                optionalStringList(args, "repository_ids"),
                optionalString(args, "component_scope", "all"),
                limit,
                optionalInt(args, "max_matches_per_structure", 1),
                optionalBoolean(args, "include_atom_mappings", false),
                outputMode,
                offset,
                limit
        );
        SubstructureSearchResult result = searches.searchSubstructure(request);
        SelectionAiService.SelectionRecord selection = null;
        if (optionalBoolean(args, "create_selection", false)) {
            selection = createSearchSelection(args, request, result.query().input());
        }
        SubstructureSearchToolResult response = new SubstructureSearchToolResult(
                result.query(),
                result.scope(),
                result.summary(),
                outputMode,
                Math.max(0, offset),
                Math.max(1, limit),
                selection,
                result.matches()
        );
        return maybeFile(
                args,
                "search_substructure",
                response,
                new SubstructureSearchArtifactSummary(response.query(), response.scope(), response.summary(), response.outputMode(), response.offset(), response.limit(), response.selection()),
                response.matches().size()
        );
    }

    private SelectionAiService.SelectionRecord createSearchSelection(ObjectNode args, SubstructureSearchRequest request, String sourceId) {
        SubstructureSearchResult all = searches.searchSubstructure(new SubstructureSearchRequest(
                request.query(),
                request.queryType(),
                request.repositoryIds(),
                request.componentScope(),
                1_000_000,
                1,
                false,
                SubstructureSearchRequest.OUTPUT_IDS,
                0,
                1_000_000
        ));
        Map<String, StructureRecord> records = new LinkedHashMap<>();
        String repositoryId = null;
        for (SubstructureSearchMatch match : all.matches()) {
            if (repositoryId == null) {
                repositoryId = match.repositoryId();
            } else if (!repositoryId.equals(match.repositoryId())) {
                throw new ChemOperationException("selection_requires_single_repository", "Selections can only be created from a single repository-scoped substructure search.");
            }
            String key = match.repositoryId() + ":" + match.structureId();
            records.putIfAbsent(key, repositories.getStructure(new StructureRef(match.repositoryId(), match.structureId())).record());
        }
        if (repositoryId == null) {
            List<String> scope = request.repositoryIds();
            repositoryId = scope != null && scope.size() == 1 ? scope.getFirst() : "session";
        }
        return selections.createSelectionFromRecords(
                optionalString(args, "selection_id", null),
                repositoryId,
                "substructure_search",
                sourceId,
                List.copyOf(records.values())
        );
    }

    private SelectionAiService.SelectionView createEndpointSelection(ObjectNode args) {
        String datasetId = requiredString(args, "dataset_id");
        String endpointId = requiredString(args, "endpoint_id");
        String operatorArgument = optionalString(args, "operator", null);
        Double threshold = optionalDouble(args, "value", null);
        MeasurementDateFilter dateFilter = measurementDateFilter(args, "measurement_date_field");
        boolean hasNumericFilter = operatorArgument != null || threshold != null;
        if (hasNumericFilter && (operatorArgument == null || threshold == null)) {
            throw new ChemOperationException("invalid_endpoint_filter", "operator and value must be supplied together for numeric endpoint filtering.");
        }
        if (!hasNumericFilter && !dateFilter.hasBounds()) {
            throw new ChemOperationException("invalid_endpoint_filter", "create_endpoint_selection requires either operator/value or measured_after/measured_before.");
        }
        String operator = hasNumericFilter ? normalizeEndpointFilterOperator(operatorArgument) : null;
        StructureScope scope = structureScope(args);

        List<String> subjectIds = subjectIdsFromRecords(scope.candidates());
        Map<String, PrismEndpointValue> valuesBySubject = new LinkedHashMap<>();
        if (!subjectIds.isEmpty()) {
            for (PrismEndpointValue value : prism.getEndpointValues(datasetId, subjectIds, List.of(endpointId))) {
                valuesBySubject.put(value.subjectId(), value);
            }
        }

        List<StructureRecord> matches = scope.candidates().stream()
                .filter(record -> {
                    String subjectId = record.fields().get("prism.subject_id");
                    PrismEndpointValue endpointValue = subjectId == null ? null : valuesBySubject.get(subjectId);
                    if (endpointValue == null) {
                        return false;
                    }
                    if (hasNumericFilter && !numericEndpointFilterMatches(endpointValue, operator, threshold)) {
                        return false;
                    }
                    return dateFilter.matches(endpointValue.result());
                })
                .toList();
        String sourceId = endpointFilterSourceId(endpointId, operator, threshold, dateFilter, scope.baseSelectionId());
        SelectionAiService.SelectionRecord record = selections.createSelectionFromRecords(
                optionalString(args, "selection_id", null),
                scope.repositoryId(),
                "endpoint_filter",
                sourceId,
                matches);
        return selections.getSelection(record.selectionId());
    }

    private SelectionAiService.SelectionView createSubjectMeasurementDateSelection(ObjectNode args) {
        String datasetId = requiredString(args, "dataset_id");
        List<String> endpointIds = optionalStringList(args, "endpoint_ids");
        if (endpointIds == null || endpointIds.isEmpty()) {
            endpointIds = prism.listEndpoints(datasetId).stream()
                    .map(PrismEndpointSummary::endpointId)
                    .toList();
        }
        if (endpointIds.isEmpty()) {
            throw new ChemOperationException("invalid_subject_measurement_date_selection", "No Prism endpoints are available for subject measurement date aggregation.");
        }
        MeasurementDateFilter dateFilter = subjectMeasurementDateFilter(args);
        if (!dateFilter.hasBounds()) {
            throw new ChemOperationException("invalid_subject_measurement_date_selection", "create_subject_measurement_date_selection requires measured_after or measured_before.");
        }
        StructureScope scope = structureScope(args);
        Map<String, SubjectMeasurementDates> subjectDates = subjectMeasurementDates(datasetId, subjectIdsFromRecords(scope.candidates()), endpointIds);
        List<StructureRecord> matches = scope.candidates().stream()
                .filter(record -> {
                    String subjectId = record.fields().get("prism.subject_id");
                    SubjectMeasurementDates dates = subjectId == null ? null : subjectDates.get(subjectId);
                    return dateFilter.matches(dates);
                })
                .toList();
        String sourceId = "subject " + dateFilter.sourceText()
                + " across " + endpointIds.size() + " endpoint" + (endpointIds.size() == 1 ? "" : "s")
                + (scope.baseSelectionId() == null ? "" : " in " + scope.baseSelectionId());
        SelectionAiService.SelectionRecord record = selections.createSelectionFromRecords(
                optionalString(args, "selection_id", null),
                scope.repositoryId(),
                "subject_measurement_date_filter",
                sourceId,
                matches);
        return selections.getSelection(record.selectionId());
    }

    private StructureScope structureScope(ObjectNode args) {
        String baseSelectionId = optionalString(args, "base_selection_id", null);
        String repositoryId = optionalString(args, "repository_id", null);
        if (baseSelectionId != null && !baseSelectionId.isBlank()) {
            SelectionAiService.StoredSelectionData base = selections.selectionData(baseSelectionId);
            String repoId = base.summary().repositoryId();
            if (repositoryId != null && !repositoryId.isBlank() && !repositoryId.trim().equals(repoId)) {
                throw new ChemOperationException("selection_repository_mismatch", "base_selection_id " + baseSelectionId + " belongs to repository " + repoId + ", not " + repositoryId + ".");
            }
            List<StructureRecord> candidates = base.members().stream()
                    .map(member -> repositories.getStructure(new StructureRef(repoId, member.structureId())).record())
                    .toList();
            return new StructureScope(repoId, baseSelectionId, candidates);
        }
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new ChemOperationException("invalid_endpoint_selection_scope", "repository_id is required unless base_selection_id is provided.");
        }
        String repoId = repositoryId.trim();
        return new StructureScope(repoId, null, allRepositoryRecords(repoId));
    }

    private static boolean numericEndpointFilterMatches(PrismEndpointValue value, String operator, double threshold) {
        if (value.result() instanceof NumericResult numeric
                && numeric.getState() == NumericState.VALUE
                && numeric.getMean() != null) {
            return endpointFilterMatches(numeric.getMean(), operator, threshold);
        }
        return false;
    }

    private static String endpointFilterSourceId(String endpointId, String operator, Double threshold, MeasurementDateFilter dateFilter, String baseSelectionId) {
        List<String> parts = new ArrayList<>();
        if (operator != null && threshold != null) {
            parts.add(endpointId + " " + operator + " " + threshold);
        } else {
            parts.add(endpointId);
        }
        if (dateFilter.hasBounds()) {
            parts.add(dateFilter.sourceText());
        }
        return String.join(" and ", parts) + (baseSelectionId == null || baseSelectionId.isBlank() ? "" : " in " + baseSelectionId);
    }

    private List<StructureRecord> allRepositoryRecords(String repositoryId) {
        List<StructureRecord> records = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<StructureRecord> page = repositories.listStructures(repositoryId, offset, 500);
            if (page.isEmpty()) {
                break;
            }
            records.addAll(page);
            offset += page.size();
        }
        return List.copyOf(records);
    }

    private static String normalizeEndpointFilterOperator(String operator) {
        String normalized = operator == null ? "" : operator.trim().toLowerCase();
        if (!"gt".equals(normalized)
                && !"gte".equals(normalized)
                && !"lt".equals(normalized)
                && !"lte".equals(normalized)
                && !"eq".equals(normalized)) {
            throw new ChemOperationException("invalid_endpoint_filter_operator", "operator must be gt, gte, lt, lte, or eq.");
        }
        return normalized;
    }

    private static boolean endpointFilterMatches(double mean, String operator, double threshold) {
        return switch (operator) {
            case "gt" -> mean > threshold;
            case "gte" -> mean >= threshold;
            case "lt" -> mean < threshold;
            case "lte" -> mean <= threshold;
            case "eq" -> Double.compare(mean, threshold) == 0;
            default -> false;
        };
    }


    private Object getClusterMembers(ObjectNode args) {
        String clusteringId = requiredString(args, "clustering_id");
        String clusterId = requiredString(args, "cluster_id");
        SimilarityClusteringAiService.ClusterMembersView members = clusterings.getClusterMembers(
                clusteringId,
                clusterId,
                optionalInt(args, "offset", 0),
                optionalInt(args, "limit", 50)
        );
        SelectionAiService.SelectionRecord selection = null;
        if (optionalBoolean(args, "create_selection", false)) {
            selection = selections.createSelectionFromRecords(
                    optionalString(args, "selection_id", null),
                    members.repositoryId(),
                    "cluster",
                    clusteringId + ":" + clusterId,
                    clusterings.clusterMemberRecords(clusteringId, clusterId)
            );
        }
        ClusterMembersToolResult response = new ClusterMembersToolResult(members, selection);
        return maybeFile(
                args,
                "get_cluster_members",
                response,
                new ClusterMembersArtifactSummary(members.clusteringId(), members.repositoryId(), members.clusterId(), members.totalMembers(), members.members().size(), selection),
                members.members().size()
        );
    }

    private Object getSelectionMembers(ObjectNode args) {
        SelectionAiService.SelectionMembersView members = selections.getMembers(
                requiredString(args, "selection_id"),
                optionalInt(args, "offset", 0),
                optionalInt(args, "limit", 50)
        );
        return maybeFile(
                args,
                "get_selection_members",
                members,
                new SelectionMembersArtifactSummary(members.summary(), members.members().size()),
                members.members().size()
        );
    }

    private Object exportSelectionTable(ObjectNode args) throws Exception {
        String selectionId = requiredString(args, "selection_id");
        String datasetId = optionalString(args, "dataset_id", null);
        List<String> endpointIds = optionalStringList(args, "endpoint_ids");
        if (endpointIds == null) {
            endpointIds = List.of();
        }
        boolean includeSubjectMeasurementDates = optionalBoolean(args, "include_subject_measurement_dates", false);
        if (!endpointIds.isEmpty() && (datasetId == null || datasetId.isBlank())) {
            throw new ChemOperationException("invalid_arguments", "dataset_id is required when endpoint_ids is provided.");
        }
        if (includeSubjectMeasurementDates && (datasetId == null || datasetId.isBlank())) {
            throw new ChemOperationException("invalid_arguments", "dataset_id is required when include_subject_measurement_dates is true.");
        }
        String format = optionalString(args, "format", "tsv").trim().toLowerCase();
        if (!"tsv".equals(format)) {
            throw new ChemOperationException("unsupported_artifact_format", "export_selection_table only supports format: tsv.");
        }

        SelectionAiService.StoredSelectionData selection = selections.selectionData(selectionId);
        boolean includeSmiles = optionalBoolean(args, "include_smiles", true);
        boolean includeFields = optionalBoolean(args, "include_fields", false);
        String decompositionEvaluationId = optionalString(args, "decomposition_evaluation_id", null);
        DecompositionAiService.DecompositionExportView decomposition = null;
        if (decompositionEvaluationId != null && !decompositionEvaluationId.isBlank()) {
            decomposition = decompositions.exportView(decompositionEvaluationId);
            if (!selection.summary().repositoryId().equals(decomposition.repositoryId())) {
                throw new ChemOperationException("selection_repository_mismatch", "decomposition_evaluation_id " + decompositionEvaluationId + " belongs to repository " + decomposition.repositoryId() + ", not " + selection.summary().repositoryId() + ".");
            }
        }

        SelectionTableExport export = buildSelectionTableExport(selection, datasetId, endpointIds, decomposition, includeSmiles, includeFields, includeSubjectMeasurementDates);
        McpArtifactService.ArtifactRecord artifact = artifacts.writeText(
                "export_selection_table",
                optionalString(args, "output_name", null),
                optionalBoolean(args, "overwrite", false),
                "tsv",
                "text/tab-separated-values",
                export.tsv(),
                export.rowCount()
        );
        return new ExportSelectionTableResult(export.summary(), artifact);
    }

    private SelectionTableExport buildSelectionTableExport(
            SelectionAiService.StoredSelectionData selection,
            String datasetId,
            List<String> endpointIds,
            DecompositionAiService.DecompositionExportView decomposition,
            boolean includeSmiles,
            boolean includeFields,
            boolean includeSubjectMeasurementDates
    ) throws Exception {
        List<SelectionMember> members = selection.members();
        boolean includeEndpoints = endpointIds != null && !endpointIds.isEmpty();
        List<String> exportSubjectIds = subjectIdsFromSelectionMembers(members);
        Map<String, Map<String, PrismEndpointValue>> endpointValues = includeEndpoints
                ? endpointValuesBySubjectAndEndpoint(datasetId, exportSubjectIds, endpointIds)
                : Map.of();
        Map<String, SubjectMeasurementDates> subjectMeasurementDates = includeSubjectMeasurementDates
                ? subjectMeasurementDates(datasetId, exportSubjectIds, allEndpointIds(datasetId))
                : Map.of();
        List<String> fieldKeys = includeFields
                ? members.stream()
                        .flatMap(member -> member.fields().keySet().stream())
                        .distinct()
                        .sorted()
                        .toList()
                : List.of();
        Map<String, String> fieldColumns = safeColumnNames(fieldKeys, "field_");
        List<String> decompositionPaths = decomposition == null ? List.of() : decomposition.terminalPaths();
        Map<String, String> decompositionColumnBases = safeColumnNames(decompositionPaths, "decomp_");

        List<String> columns = new ArrayList<>();
        columns.add("structure_id");
        columns.add("repository_id");
        columns.add("subject_id");
        columns.add("label");
        if (includeSmiles) {
            columns.add("canonical_smiles");
        }
        for (String fieldKey : fieldKeys) {
            columns.add(fieldColumns.get(fieldKey));
        }
        if (includeSubjectMeasurementDates) {
            columns.add("subject_first_measurement");
            columns.add("subject_last_measurement");
            columns.add("subject_measurement_endpoint_count");
        }
        if (includeEndpoints) {
            columns.addAll(List.of(
                    "endpoint_id",
                    "result_type",
                    "numeric_state",
                    "value",
                    "lower",
                    "upper",
                    "n",
                    "raw_value_ids",
                    "first_measurement",
                    "last_measurement",
                    "details_json"
            ));
        }
        if (decomposition != null) {
            columns.add("decomposition_evaluation_id");
            columns.add("decomposition_status");
            columns.add("decomposition_root_rule");
            columns.add("decomposition_terminal_paths");
            for (String path : decompositionPaths) {
                String base = decompositionColumnBases.get(path);
                columns.add(base + "_label");
                columns.add(base + "_fragment_id");
                columns.add(base + "_fragment_smiles");
            }
        }

        StringBuilder builder = new StringBuilder();
        appendTsvRow(builder, columns);
        int rowCount = 0;
        for (SelectionMember member : members) {
            if (includeEndpoints) {
                String subjectId = member.fields().get("prism.subject_id");
                Map<String, PrismEndpointValue> byEndpoint = subjectId == null ? Map.of() : endpointValues.getOrDefault(subjectId, Map.of());
                for (String endpointId : endpointIds) {
                    PrismEndpointValue value = byEndpoint.get(endpointId);
                    if (value == null) {
                        continue;
                    }
                    SubjectMeasurementDates dates = includeSubjectMeasurementDates ? subjectMeasurementDates.getOrDefault(subjectId, SubjectMeasurementDates.empty()) : null;
                    appendTsvRow(builder, exportRow(selection.summary().repositoryId(), member, fieldKeys, includeSmiles, dates, value, decomposition, decompositionPaths));
                    rowCount++;
                }
            } else {
                String subjectId = member.fields().get("prism.subject_id");
                SubjectMeasurementDates dates = includeSubjectMeasurementDates ? subjectMeasurementDates.getOrDefault(subjectId, SubjectMeasurementDates.empty()) : null;
                appendTsvRow(builder, exportRow(selection.summary().repositoryId(), member, fieldKeys, includeSmiles, dates, null, decomposition, decompositionPaths));
                rowCount++;
            }
        }
        ExportSelectionTableSummary summary = new ExportSelectionTableSummary(
                selection.summary(),
                datasetId,
                endpointIds == null ? List.of() : endpointIds,
                decomposition == null ? null : decomposition.evaluationId(),
                members.size(),
                rowCount,
                columns.size(),
                columns
        );
        return new SelectionTableExport(summary, builder.toString(), rowCount);
    }

    private List<String> exportRow(
            String repositoryId,
            SelectionMember member,
            List<String> fieldKeys,
            boolean includeSmiles,
            SubjectMeasurementDates subjectMeasurementDates,
            PrismEndpointValue endpointValue,
            DecompositionAiService.DecompositionExportView decomposition,
            List<String> decompositionPaths
    ) throws Exception {
        List<String> row = new ArrayList<>();
        row.add(member.structureId());
        row.add(repositoryId);
        row.add(member.fields().get("prism.subject_id"));
        row.add(member.label());
        if (includeSmiles) {
            row.add(member.canonicalSmiles());
        }
        for (String fieldKey : fieldKeys) {
            row.add(member.fields().get(fieldKey));
        }
        if (subjectMeasurementDates != null) {
            row.add(subjectMeasurementDates.firstMeasurement());
            row.add(subjectMeasurementDates.lastMeasurement());
            row.add(String.valueOf(subjectMeasurementDates.endpointCount()));
        }
        if (endpointValue != null) {
            appendEndpointColumns(row, endpointValue);
        }
        if (decomposition != null) {
            DecompositionAiService.DecompositionExportMolecule molecule = decomposition.molecules().get(member.structureId());
            row.add(decomposition.evaluationId());
            row.add(molecule == null ? "" : molecule.status());
            row.add(molecule == null ? "" : molecule.rootRule());
            row.add(molecule == null ? "" : String.join("|", molecule.terminalPaths()));
            for (String path : decompositionPaths) {
                DecompositionAiService.DecompositionExportFragment fragment = molecule == null ? null : molecule.fragments().get(path);
                row.add(fragment == null ? "" : fragment.label());
                row.add(fragment == null ? "" : fragment.fragmentId());
                row.add(fragment == null ? "" : fragment.fragmentSmiles());
            }
        }
        return row;
    }

    private void appendEndpointColumns(List<String> row, PrismEndpointValue value) throws Exception {
        EndpointResult result = value.result();
        row.add(value.endpointId());
        row.add(result == null || result.getType() == null ? "" : result.getType().name());
        if (result instanceof NumericResult numeric) {
            row.add(numeric.getState() == null ? "" : numeric.getState().name());
            row.add(valueString(numeric.getMean()));
            row.add(valueString(numeric.getLower()));
            row.add(valueString(numeric.getUpper()));
        } else {
            row.add("");
            row.add("");
            row.add("");
            row.add("");
        }
        row.add(result == null || result.getN() == null ? "" : result.getN().toString());
        row.add(result == null || result.getRawValueIds() == null ? "" : String.join("|", result.getRawValueIds()));
        row.add(result == null ? "" : result.getFirstMeasurement());
        row.add(result == null ? "" : result.getLastMeasurement());
        row.add(result == null || result.getDetails() == null || result.getDetails().isEmpty() ? "" : mapper.writeValueAsString(result.getDetails()));
    }

    private List<String> allEndpointIds(String datasetId) {
        return prism.listEndpoints(datasetId).stream()
                .map(PrismEndpointSummary::endpointId)
                .toList();
    }

    private Map<String, SubjectMeasurementDates> subjectMeasurementDates(String datasetId, List<String> subjectIds, List<String> endpointIds) {
        if (subjectIds.isEmpty() || endpointIds.isEmpty()) {
            return Map.of();
        }
        Map<String, SubjectMeasurementDateBuilder> builders = new LinkedHashMap<>();
        for (PrismEndpointValue value : prism.getEndpointValues(datasetId, subjectIds, endpointIds)) {
            EndpointResult result = value.result();
            if (result == null) {
                continue;
            }
            Instant first = parseOptionalMeasurementInstant(result.getFirstMeasurement());
            Instant last = parseOptionalMeasurementInstant(result.getLastMeasurement());
            if (first == null && last == null) {
                continue;
            }
            builders.computeIfAbsent(value.subjectId(), ignored -> new SubjectMeasurementDateBuilder())
                    .add(result.getFirstMeasurement(), first, result.getLastMeasurement(), last);
        }
        Map<String, SubjectMeasurementDates> aggregates = new LinkedHashMap<>();
        for (Map.Entry<String, SubjectMeasurementDateBuilder> entry : builders.entrySet()) {
            aggregates.put(entry.getKey(), entry.getValue().build());
        }
        return Map.copyOf(aggregates);
    }

    private static MeasurementDateFilter measurementDateFilter(ObjectNode args, String dateFieldArgumentName) {
        String field = normalizeMeasurementDateField(optionalString(args, dateFieldArgumentName, "last"), dateFieldArgumentName);
        String afterText = optionalString(args, "measured_after", null);
        String beforeText = optionalString(args, "measured_before", null);
        boolean requireMeasuredDate = optionalBoolean(args, "require_measured_date", afterText != null || beforeText != null);
        return new MeasurementDateFilter(
                field,
                parseMeasurementDateBound(afterText, true),
                parseMeasurementDateBound(beforeText, false),
                afterText,
                beforeText,
                requireMeasuredDate);
    }

    private static MeasurementDateFilter subjectMeasurementDateFilter(ObjectNode args) {
        String field = normalizeMeasurementDateField(optionalString(args, "subject_date_field", "last"), "subject_date_field");
        String afterText = optionalString(args, "measured_after", null);
        String beforeText = optionalString(args, "measured_before", null);
        return new MeasurementDateFilter(
                field,
                parseMeasurementDateBound(afterText, true),
                parseMeasurementDateBound(beforeText, false),
                afterText,
                beforeText,
                true);
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
        }
        catch (RuntimeException exception) {
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
        }
        catch (RuntimeException exception) {
            throw new ChemOperationException("invalid_measurement_date_filter", "Invalid measurement date in Prism endpoint value: " + value, exception);
        }
    }

    private Map<String, Map<String, PrismEndpointValue>> endpointValuesBySubjectAndEndpoint(
            String datasetId,
            List<String> subjectIds,
            List<String> endpointIds
    ) {
        if (subjectIds.isEmpty() || endpointIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, PrismEndpointValue>> result = new LinkedHashMap<>();
        for (PrismEndpointValue value : prism.getEndpointValues(datasetId, subjectIds, endpointIds)) {
            result.computeIfAbsent(value.subjectId(), ignored -> new LinkedHashMap<>())
                    .put(value.endpointId(), value);
        }
        return result;
    }

    private static Map<String, String> safeColumnNames(List<String> rawNames, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String rawName : rawNames) {
            String safe = safeColumnName(prefix + rawName);
            int count = counts.merge(safe, 1, Integer::sum);
            result.put(rawName, count == 1 ? safe : safe + "_" + count);
        }
        return result;
    }

    private static String safeColumnName(String rawName) {
        String safe = (rawName == null ? "" : rawName.trim().toLowerCase()).replaceAll("[^a-z0-9]+", "_");
        safe = safe.replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "column" : safe;
    }

    private static void appendTsvRow(StringBuilder builder, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append('\t');
            }
            builder.append(tsvValue(values.get(i)));
        }
        builder.append('\n');
    }

    private static String tsvValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String valueString(Double value) {
        return value == null ? "" : value.toString();
    }

    private Object summarizePrismRowSetByColumns(ObjectNode args) {
        PrismRowSetColumnSummary response = prism.summarizeRowSetByColumns(
                requiredString(args, "session_id"),
                requiredString(args, "row_set_id"),
                stringList(args, "column_ids"),
                optionalDouble(args, "threshold", null),
                optionalString(args, "threshold_direction", "gte"),
                optionalInt(args, "top_values_limit", 10)
        );
        return maybeFile(
                args,
                "summarize_prism_row_set_by_columns",
                response,
                new PrismRowSetColumnArtifactSummary(response.rowSet(), response.columnIds().size()),
                response.columns().size()
        );
    }

    private Object summarizePrismGroupingByColumns(ObjectNode args) {
        PrismGroupingColumnSummary response = prism.summarizeGroupingByColumns(
                requiredString(args, "session_id"),
                requiredString(args, "grouping_id"),
                stringList(args, "column_ids"),
                optionalBoolean(args, "include_singletons", false),
                optionalInt(args, "offset", 0),
                optionalInt(args, "limit", 50),
                optionalDouble(args, "threshold", null),
                optionalString(args, "threshold_direction", "gte"),
                optionalInt(args, "top_values_limit", 10)
        );
        return maybeFile(
                args,
                "summarize_prism_grouping_by_columns",
                response,
                new PrismGroupingColumnArtifactSummary(
                        response.grouping(),
                        response.columnIds().size(),
                        response.totalGroups(),
                        response.returnedGroups(),
                        response.offset(),
                        response.limit(),
                        response.includeSingletons()),
                response.returnedGroups()
        );
    }

    private Object summarizeSelectionByEndpoint(ObjectNode args) {
        EndpointSelectionSummary response = buildSelectionEndpointSummary(
                requiredString(args, "selection_id"),
                requiredString(args, "dataset_id"),
                stringList(args, "endpoint_ids"),
                optionalDouble(args, "threshold", null),
                optionalString(args, "threshold_direction", "gte")
        );
        return maybeFile(
                args,
                "summarize_selection_by_endpoint",
                response,
                new EndpointSelectionArtifactSummary(response.selection(), response.datasetId(), response.endpoints().size()),
                response.endpoints().size()
        );
    }

    private EndpointSelectionSummary buildSelectionEndpointSummary(
            String selectionId,
            String datasetId,
            List<String> endpointIds,
            Double threshold,
            String thresholdDirection
    ) {
        SelectionAiService.StoredSelectionData data = selections.selectionData(selectionId);
        List<String> subjectIds = subjectIdsFromSelectionMembers(data.members());
        List<EndpointSummaryRow> rows = endpointIds.stream()
                .map(endpointId -> endpointStats(datasetId, endpointId, subjectIds, data.members().size(), threshold, thresholdDirection))
                .toList();
        return new EndpointSelectionSummary(data.summary(), datasetId, rows);
    }

    private Object summarizeClustersByEndpoint(ObjectNode args) {
        boolean includeSingletons = optionalBoolean(args, "include_singletons", false);
        int offset = Math.max(0, optionalInt(args, "offset", 0));
        int limit = Math.max(1, optionalInt(args, "limit", 50));
        EndpointClusterSummary full = buildClusterEndpointSummary(
                requiredString(args, "clustering_id"),
                requiredString(args, "dataset_id"),
                requiredString(args, "endpoint_id"),
                includeSingletons,
                optionalDouble(args, "threshold", null),
                optionalString(args, "threshold_direction", "gte")
        );
        EndpointClusterSummary response = full.page(offset, limit);
        return maybeFile(
                args,
                "summarize_clusters_by_endpoint",
                full,
                new EndpointClusterArtifactSummary(
                        full.clusteringId(),
                        full.repositoryId(),
                        full.datasetId(),
                        full.endpointId(),
                        full.totalClusters(),
                        response.returnedClusters(),
                        response.offset(),
                        response.limit(),
                        full.includeSingletons()),
                full.totalClusters(),
                response
        );
    }

    private EndpointClusterSummary buildClusterEndpointSummary(
            String clusteringId,
            String datasetId,
            String endpointId,
            boolean includeSingletons,
            Double threshold,
            String thresholdDirection
    ) {
        String repositoryId = clusterings.repositoryId(clusteringId);
        List<EndpointClusterSummaryRow> rows = new ArrayList<>();
        for (SimilarityCluster cluster : clusterings.clusters(clusteringId)) {
            if (!includeSingletons && cluster.size() == 1) {
                continue;
            }
            List<StructureRecord> records = clusterings.clusterMemberRecords(clusteringId, cluster.clusterId());
            EndpointSummaryRow stats = endpointStats(datasetId, endpointId, subjectIdsFromRecords(records), records.size(), threshold, thresholdDirection);
            String representativeSmiles = repositories.getStructure(new StructureRef(repositoryId, cluster.representativeStructureId())).record().canonicalSmiles();
            rows.add(new EndpointClusterSummaryRow(
                    cluster.clusterId(),
                    cluster.size(),
                    cluster.representativeStructureId(),
                    cluster.representativeLabel(),
                    representativeSmiles,
                    stats
            ));
        }
        rows.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return new EndpointClusterSummary(clusteringId, repositoryId, datasetId, endpointId, includeSingletons, rows.size(), rows.size(), 0, rows.size(), rows);
    }

    private EndpointSummaryRow endpointStats(
            String datasetId,
            String endpointId,
            List<String> subjectIds,
            int requestedSubjectCount,
            Double threshold,
            String thresholdDirection
    ) {
        String direction = normalizeThresholdDirection(thresholdDirection);
        List<Double> values = new ArrayList<>();
        for (PrismEndpointValue value : prism.getEndpointValues(datasetId, subjectIds, List.of(endpointId))) {
            if (value.result() instanceof NumericResult numeric
                    && numeric.getState() == NumericState.VALUE
                    && numeric.getMean() != null) {
                values.add(numeric.getMean());
            }
        }
        Collections.sort(values);
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
        return new EndpointSummaryRow(
                endpointId,
                requestedSubjectCount,
                subjectIds.size(),
                values.size(),
                requestedSubjectCount - values.size(),
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
        );
    }

    private Object decompositionFragmentSummary(ObjectNode args) {
        Object response = decompositionFragmentSummaryPayload(args);
        Integer rowCount = response instanceof DecompositionAiService.DecompositionFragmentSummaryView detailed
                ? detailed.rows().size()
                : ((CompactDecompositionFragmentSummary) response).rows().size();
        Object summary = response instanceof DecompositionAiService.DecompositionFragmentSummaryView detailed
                ? new FragmentSummaryArtifactSummary(detailed.evaluationId(), detailed.totalRows(), detailed.rows().size(), true)
                : new FragmentSummaryArtifactSummary(
                        ((CompactDecompositionFragmentSummary) response).evaluationId(),
                        ((CompactDecompositionFragmentSummary) response).totalRows(),
                        ((CompactDecompositionFragmentSummary) response).rows().size(),
                        false
                );
        return maybeFile(args, "get_decomposition_fragment_summary", response, summary, rowCount);
    }

    private Object decompositionFragmentSummaryPayload(ObjectNode args) {
        DecompositionAiService.DecompositionFragmentSummaryView summary = decompositions.getFragmentSummary(
                requiredString(args, "evaluation_id"),
                optionalInt(args, "offset", 0),
                optionalInt(args, "limit", 100)
        );
        if (optionalBoolean(args, "include_details", false)) {
            return summary;
        }
        int exampleLimit = Math.max(1, optionalInt(args, "example_limit", 1));
        List<CompactFragmentSummaryRow> rows = summary.rows().stream()
                .map(row -> new CompactFragmentSummaryRow(
                        row.path(),
                        row.label(),
                        row.totalSupport(),
                        row.distinctFragmentCount(),
                        row.singletonCount(),
                        row.examples().stream()
                                .limit(exampleLimit)
                                .map(example -> new CompactFragmentExample(example.structureId(), example.fragmentSmiles()))
                                .toList()
                ))
                .toList();
        return new CompactDecompositionFragmentSummary(summary.evaluationId(), summary.totalRows(), rows);
    }

    private Object decompositionFragmentHistogram(ObjectNode args) {
        String evaluationId = requiredString(args, "evaluation_id");
        String path = optionalString(args, "path", null);
        String label = optionalString(args, "label", null);
        int offset = Math.max(0, optionalInt(args, "offset", 0));
        int limit = Math.min(1000, Math.max(1, optionalInt(args, "limit", 50)));
        int exampleLimit = Math.max(1, optionalInt(args, "example_limit", 3));
        String datasetId = optionalString(args, "dataset_id", null);
        String endpointId = optionalString(args, "endpoint_id", null);
        if ((datasetId == null || datasetId.isBlank()) != (endpointId == null || endpointId.isBlank())) {
            throw new ChemOperationException("invalid_arguments", "dataset_id and endpoint_id must be provided together for fragment endpoint stats.");
        }
        Double threshold = optionalDouble(args, "threshold", null);
        String thresholdDirection = optionalString(args, "threshold_direction", "gte");

        DecompositionAiService.DecompositionFragmentHistogramView responseView = decompositions.getFragmentHistogram(
                evaluationId,
                path,
                label,
                offset,
                limit,
                exampleLimit);
        FragmentHistogramToolResult response = fragmentHistogramToolResult(responseView, datasetId, endpointId, threshold, thresholdDirection);
        String outputTarget = normalizeOutputTarget(optionalString(args, "output_target", "response"));
        if ("response".equals(outputTarget)) {
            return response;
        }

        DecompositionAiService.DecompositionFragmentHistogramView fullView = decompositions.getFragmentHistogram(
                evaluationId,
                path,
                label,
                0,
                Integer.MAX_VALUE,
                exampleLimit);
        FragmentHistogramToolResult full = fragmentHistogramToolResult(fullView, datasetId, endpointId, threshold, thresholdDirection);
        FragmentHistogramArtifactSummary summary = new FragmentHistogramArtifactSummary(
                full.evaluationId(),
                full.repositoryId(),
                full.path(),
                full.label(),
                endpointId,
                full.totalFragments(),
                response.returnedFragments(),
                response.offset(),
                response.limit());
        McpArtifactService.ArtifactRecord artifact = writeJsonArtifact(args, "get_decomposition_fragment_histogram", full, full.totalFragments());
        return new ArtifactOutputResult(summary, artifact);
    }

    private FragmentHistogramToolResult fragmentHistogramToolResult(
            DecompositionAiService.DecompositionFragmentHistogramView view,
            String datasetId,
            String endpointId,
            Double threshold,
            String thresholdDirection
    ) {
        List<FragmentHistogramToolRow> rows = view.rows().stream()
                .map(row -> {
                    EndpointSummaryRow endpoint = endpointId == null || endpointId.isBlank()
                            ? null
                            : endpointStats(
                                    datasetId,
                                    endpointId,
                                    subjectIdsFromStructureIds(view.repositoryId(), row.structureIds()),
                                    row.structureIds().size(),
                                    threshold,
                                    thresholdDirection);
                    return new FragmentHistogramToolRow(
                            row.fragmentId(),
                            row.fragmentSmiles(),
                            row.support(),
                            row.exampleStructureIds(),
                            endpoint);
                })
                .toList();
        return new FragmentHistogramToolResult(
                view.evaluationId(),
                view.repositoryId(),
                view.path(),
                view.label(),
                view.totalFragments(),
                view.returnedFragments(),
                view.offset(),
                view.limit(),
                rows);
    }

    private Object maybeFile(ObjectNode args, String sourceTool, Object responsePayload, Object summary, Integer rowCount) {
        return maybeFile(args, sourceTool, responsePayload, summary, rowCount, responsePayload);
    }

    private Object maybeFile(ObjectNode args, String sourceTool, Object artifactPayload, Object summary, Integer rowCount, Object responsePayload) {
        String outputTarget = normalizeOutputTarget(optionalString(args, "output_target", "response"));
        if ("response".equals(outputTarget)) {
            return responsePayload;
        }
        McpArtifactService.ArtifactRecord artifact = writeJsonArtifact(args, sourceTool, artifactPayload, rowCount);
        return new ArtifactOutputResult(summary, artifact);
    }

    private McpArtifactService.ArtifactRecord writeJsonArtifact(ObjectNode args, String sourceTool, Object payload, Integer rowCount) {
        String format = optionalString(args, "format", "json").trim().toLowerCase();
        if (!"json".equals(format)) {
            throw new ChemOperationException("unsupported_artifact_format", "Only json artifact output is supported.");
        }
        return artifacts.writeJson(
                sourceTool,
                optionalString(args, "output_name", null),
                optionalBoolean(args, "overwrite", false),
                payload,
                rowCount
        );
    }

    private static String normalizeOutputTarget(String outputTarget) {
        String normalized = outputTarget == null || outputTarget.isBlank() ? "response" : outputTarget.trim().toLowerCase();
        if (!"response".equals(normalized) && !"file".equals(normalized)) {
            throw new ChemOperationException("invalid_output_target", "output_target must be response or file.");
        }
        return normalized;
    }

    private static List<String> subjectIdsFromSelectionMembers(List<SelectionMember> members) {
        return members.stream()
                .map(member -> member.fields().get("prism.subject_id"))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> subjectIdsFromRecords(List<StructureRecord> records) {
        return records.stream()
                .map(record -> record.fields().get("prism.subject_id"))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private List<String> subjectIdsFromStructureIds(String repositoryId, List<String> structureIds) {
        return structureIds.stream()
                .map(structureId -> repositories.getStructure(new StructureRef(repositoryId, structureId)).record().fields().get("prism.subject_id"))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private static String normalizeThresholdDirection(String thresholdDirection) {
        String normalized = thresholdDirection == null || thresholdDirection.isBlank() ? "gte" : thresholdDirection.trim().toLowerCase();
        if (!"gte".equals(normalized) && !"lte".equals(normalized)) {
            throw new ChemOperationException("invalid_threshold_direction", "threshold_direction must be gte or lte.");
        }
        return normalized;
    }

    private static Double percentile(List<Double> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        if (sortedValues.size() == 1) {
            return sortedValues.getFirst();
        }
        double index = quantile * (sortedValues.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sortedValues.get(lower);
        }
        double fraction = index - lower;
        return sortedValues.get(lower) * (1.0 - fraction) + sortedValues.get(upper) * fraction;
    }

    private DecompositionConfig decompositionConfig(ObjectNode args) {
        JsonNode configNode = args.get("config");
        if (configNode != null && !configNode.isNull()) {
            if (!configNode.isObject()) {
                throw new ChemOperationException("invalid_arguments", "Argument config must be an object.");
            }
            return mapper.convertValue(configNode, DecompositionConfig.class);
        }
        String configJson = optionalString(args, "config_json", null);
        if (configJson == null || configJson.isBlank()) {
            throw new ChemOperationException("invalid_arguments", "Either config or config_json is required.");
        }
        try {
            return mapper.readValue(configJson, DecompositionConfig.class);
        } catch (Exception e) {
            throw new ChemOperationException("invalid_decomposition_config", "Could not parse decomposition config: " + e.getMessage(), e);
        }
    }

    private Object inspectStructure(ObjectNode args) throws Exception {
        StructureInspection inspection = inspections.inspectStructure(structureRef(args));
        String format = optionalString(args, "format", "compact");
        if ("json".equals(format)) {
            return inspection;
        }
        if (!"compact".equals(format)) {
            throw new ChemOperationException("invalid_format", "inspect_structure format must be compact or json.");
        }
        return new StructureInspectionText(inspection, compactRenderer.render(inspection));
    }

    private void add(List<McpToolDefinition> tools, String name, String description, Map<String, Object> schema, ToolHandler handler) {
        tools.add(new McpToolDefinition(name, description, schema));
        handlers.put(name, handler);
    }

    private RepositoryRecord repository(String repositoryId) {
        return repositories.listRepositories().stream()
                .filter(record -> record.repositoryId().equals(repositoryId))
                .findFirst()
                .orElseThrow(() -> new ChemOperationException("repository_not_found", "Repository " + repositoryId + " does not exist."));
    }

    private static StructureRef structureRef(ObjectNode args) {
        return new StructureRef(requiredString(args, "repository_id"), requiredString(args, "structure_id"));
    }

    private static Map<String, Object> atomSchema(String idName) {
        return schema(
                required("repository_id", "structure_id", idName),
                prop("repository_id", "string", "Repository ID."),
                prop("structure_id", "string", "Structure ID."),
                prop(idName, "string", "Addressable atom or bond ID."));
    }

    private static Map<String, Object> schema(Object... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Required requiredEntry) {
                required.addAll(requiredEntry.names());
            } else if (entry instanceof Property property) {
                properties.put(property.name(), property.schema());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Required required(String... names) {
        return new Required(List.of(names));
    }

    private static Property prop(String name, String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return new Property(name, schema);
    }

    private static Property arrayProp(String name, String itemType, String description) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", itemType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        schema.put("description", description);
        return new Property(name, schema);
    }

    private static Property moleculeArrayProp() {
        Map<String, Object> itemProperties = new LinkedHashMap<>();
        itemProperties.put("title", Map.of("type", "string", "description", "Optional molecule title."));
        itemProperties.put("mode", Map.of("type", "string", "description", "molecule or fragment; defaults to molecule."));
        itemProperties.put("structure", Map.of("type", "string", "description", "SMILES for molecule mode or SMARTS for fragment mode."));
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "object");
        items.put("properties", itemProperties);
        items.put("required", List.of("structure"));
        items.put("additionalProperties", false);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        schema.put("description", "Ordered molecule documents to add.");
        return new Property("molecules", schema);
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

    private static Integer optionalInteger(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asInt();
    }

    private static Long optionalLong(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.canConvertToLong()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an integer.");
        }
        return node.asLong();
    }

    private Map<String, Object> optionalObjectMap(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an object.");
        }
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private static boolean optionalBoolean(ObjectNode args, String name, boolean defaultValue) {
        Boolean value = optionalNullableBoolean(args, name);
        return value == null ? defaultValue : value;
    }

    private static Boolean optionalNullableBoolean(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private static double requiredDouble(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            throw new ChemOperationException("invalid_arguments", "Missing required argument: " + name);
        }
        if (!node.isNumber()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a number.");
        }
        return node.asDouble();
    }

    private static Double optionalDouble(ObjectNode args, String name, Double defaultValue) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (!node.isNumber()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be a number.");
        }
        return node.asDouble();
    }

    private static List<String> optionalStringList(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        return stringList(args, name);
    }

    private static List<String> stringList(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || !node.isArray()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an array of strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new ChemOperationException("invalid_arguments", "Argument " + name + " must contain only strings.");
            }
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static List<PrismMoleculeInput> moleculeInputs(ObjectNode args) {
        JsonNode node = args.get("molecules");
        if (node == null || !node.isArray()) {
            throw new ChemOperationException("invalid_arguments", "Argument molecules must be an array of objects.");
        }
        ArrayList<PrismMoleculeInput> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!(item instanceof ObjectNode object)) {
                throw new ChemOperationException("invalid_arguments", "Argument molecules must contain only objects.");
            }
            result.add(new PrismMoleculeInput(
                    optionalString(object, "title", null),
                    optionalString(object, "mode", "molecule"),
                    requiredString(object, "structure")
            ));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> stringMap(ObjectNode args, String name) {
        JsonNode node = args.get(name);
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new ChemOperationException("invalid_arguments", "Argument " + name + " must be an object.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText()));
        return Map.copyOf(result);
    }

    record ToolCallResult(String text, JsonNode structuredContent, boolean isError) {}

    private record StructureInspectionText(StructureInspection inspection, String text) {}

    private record StructureScope(String repositoryId, String baseSelectionId, List<StructureRecord> candidates) {}

    private record SubjectMeasurementDates(
            String firstMeasurement,
            String lastMeasurement,
            int endpointCount,
            Instant firstInstant,
            Instant lastInstant
    ) {
        static SubjectMeasurementDates empty() {
            return new SubjectMeasurementDates("", "", 0, null, null);
        }
    }

    private static final class SubjectMeasurementDateBuilder {
        private String firstMeasurement;
        private String lastMeasurement;
        private Instant firstInstant;
        private Instant lastInstant;
        private int endpointCount;

        private void add(String firstText, Instant first, String lastText, Instant last) {
            endpointCount++;
            if (first != null && (firstInstant == null || first.isBefore(firstInstant))) {
                firstInstant = first;
                firstMeasurement = firstText;
            }
            if (last != null && (lastInstant == null || last.isAfter(lastInstant))) {
                lastInstant = last;
                lastMeasurement = lastText;
            }
        }

        private SubjectMeasurementDates build() {
            return new SubjectMeasurementDates(
                    firstMeasurement == null ? "" : firstMeasurement,
                    lastMeasurement == null ? "" : lastMeasurement,
                    endpointCount,
                    firstInstant,
                    lastInstant);
        }
    }

    private record MeasurementDateFilter(
            String field,
            Instant measuredAfter,
            Instant measuredBefore,
            String measuredAfterText,
            String measuredBeforeText,
            boolean requireMeasuredDate
    ) {
        boolean hasBounds() {
            return measuredAfter != null || measuredBefore != null;
        }

        boolean matches(EndpointResult result) {
            if (!hasBounds()) {
                return true;
            }
            Instant instant = selectedInstant(result);
            if (instant == null) {
                return !requireMeasuredDate;
            }
            return matchesInstant(instant);
        }

        boolean matches(SubjectMeasurementDates dates) {
            if (!hasBounds()) {
                return true;
            }
            Instant instant = dates == null ? null : ("first".equals(field) ? dates.firstInstant() : dates.lastInstant());
            if (instant == null) {
                return false;
            }
            return matchesInstant(instant);
        }

        String sourceText() {
            List<String> parts = new ArrayList<>();
            if (measuredAfterText != null && !measuredAfterText.isBlank()) {
                parts.add(field + "_measurement >= " + measuredAfterText.trim());
            }
            if (measuredBeforeText != null && !measuredBeforeText.isBlank()) {
                parts.add(field + "_measurement <= " + measuredBeforeText.trim());
            }
            return String.join(" and ", parts);
        }

        private boolean matchesInstant(Instant instant) {
            return (measuredAfter == null || !instant.isBefore(measuredAfter))
                    && (measuredBefore == null || !instant.isAfter(measuredBefore));
        }

        private Instant selectedInstant(EndpointResult result) {
            if (result == null) {
                return null;
            }
            return parseOptionalMeasurementInstant("first".equals(field) ? result.getFirstMeasurement() : result.getLastMeasurement());
        }
    }


    private record ToolGuide(String topic, String markdown) {}

    private record ArtifactOutputResult(Object summary, McpArtifactService.ArtifactRecord artifact) {}

    private record ExportSelectionTableResult(ExportSelectionTableSummary summary, McpArtifactService.ArtifactRecord artifact) {}

    private record ExportSelectionTableSummary(
            SelectionAiService.SelectionRecord selection,
            String datasetId,
            List<String> endpointIds,
            String decompositionEvaluationId,
            int selectedStructureCount,
            int rowCount,
            int columnCount,
            List<String> columns
    ) {}

    private record SelectionTableExport(
            ExportSelectionTableSummary summary,
            String tsv,
            int rowCount
    ) {}

    private record SubstructureSearchArtifactSummary(
            Object query,
            Object scope,
            Object summary,
            String outputMode,
            int offset,
            int limit,
            SelectionAiService.SelectionRecord selection
    ) {}

    private record ClusterMembersArtifactSummary(
            String clusteringId,
            String repositoryId,
            String clusterId,
            int totalMembers,
            int returnedMembers,
            SelectionAiService.SelectionRecord selection
    ) {}

    private record SelectionMembersArtifactSummary(
            SelectionAiService.SelectionRecord selection,
            int returnedMembers
    ) {}

    private record EndpointSelectionArtifactSummary(
            SelectionAiService.SelectionRecord selection,
            String datasetId,
            int endpointCount
    ) {}

    private record PrismRowSetColumnArtifactSummary(
            PrismRowSetSummary rowSet,
            int columnCount
    ) {}

    private record PrismGroupingColumnArtifactSummary(
            PrismGroupingSummary grouping,
            int columnCount,
            int totalGroups,
            int returnedGroups,
            int offset,
            int limit,
            boolean includeSingletons
    ) {}

    private record EndpointClusterArtifactSummary(
            String clusteringId,
            String repositoryId,
            String datasetId,
            String endpointId,
            int totalClusters,
            int returnedClusters,
            int offset,
            int limit,
            boolean includeSingletons
    ) {}

    private record FragmentSummaryArtifactSummary(
            String evaluationId,
            int totalRows,
            int returnedRows,
            boolean detailed
    ) {}

    private record FragmentHistogramArtifactSummary(
            String evaluationId,
            String repositoryId,
            String path,
            String label,
            String endpointId,
            int totalFragments,
            int returnedFragments,
            int offset,
            int limit
    ) {}

    private record SubstructureSearchToolResult(
            Object query,
            Object scope,
            Object summary,
            String outputMode,
            int offset,
            int limit,
            SelectionAiService.SelectionRecord selection,
            List<SubstructureSearchMatch> matches
    ) {}

    private record ClusterMembersToolResult(
            SimilarityClusteringAiService.ClusterMembersView cluster,
            SelectionAiService.SelectionRecord selection
    ) {}

    private record EndpointSelectionSummary(
            SelectionAiService.SelectionRecord selection,
            String datasetId,
            List<EndpointSummaryRow> endpoints
    ) {}

    private record EndpointClusterSummary(
            String clusteringId,
            String repositoryId,
            String datasetId,
            String endpointId,
            boolean includeSingletons,
            int totalClusters,
            int returnedClusters,
            int offset,
            int limit,
            List<EndpointClusterSummaryRow> clusters
    ) {
        EndpointClusterSummary page(int requestedOffset, int requestedLimit) {
            int safeOffset = Math.max(0, requestedOffset);
            int safeLimit = Math.min(1000, Math.max(1, requestedLimit));
            int from = Math.min(safeOffset, clusters.size());
            int to = Math.min(from + safeLimit, clusters.size());
            return new EndpointClusterSummary(
                    clusteringId,
                    repositoryId,
                    datasetId,
                    endpointId,
                    includeSingletons,
                    totalClusters,
                    to - from,
                    safeOffset,
                    safeLimit,
                    List.copyOf(clusters.subList(from, to)));
        }
    }

    private record EndpointClusterSummaryRow(
            String clusterId,
            int size,
            String representativeStructureId,
            String representativeLabel,
            String representativeSmiles,
            EndpointSummaryRow endpoint
    ) {}

    private record EndpointSummaryRow(
            String endpointId,
            int subjectCount,
            int mappedSubjectCount,
            int measuredCount,
            int missingCount,
            Double mean,
            Double median,
            Double q1,
            Double q3,
            Double min,
            Double max,
            Double threshold,
            String thresholdDirection,
            Integer thresholdHitCount,
            Double thresholdHitRate
    ) {}

    private record CompactDecompositionFragmentSummary(
            String evaluationId,
            int totalRows,
            List<CompactFragmentSummaryRow> rows
    ) {}

    private record CompactFragmentSummaryRow(
            String path,
            String label,
            int totalSupport,
            int distinctFragmentCount,
            int singletonCount,
            List<CompactFragmentExample> examples
    ) {}

    private record CompactFragmentExample(String structureId, String fragmentSmiles) {}

    private record FragmentHistogramToolResult(
            String evaluationId,
            String repositoryId,
            String path,
            String label,
            int totalFragments,
            int returnedFragments,
            int offset,
            int limit,
            List<FragmentHistogramToolRow> rows
    ) {}

    private record FragmentHistogramToolRow(
            String fragmentId,
            String fragmentSmiles,
            int support,
            List<String> exampleStructureIds,
            EndpointSummaryRow endpoint
    ) {}

    private record Required(List<String> names) {}

    private record Property(String name, Map<String, Object> schema) {}

}
