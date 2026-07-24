package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.molecules.structurized.ai.clustering.SimilarityClusteringAiService;
import tech.molecules.structurized.ai.decomposition.DecompositionAiService;
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
import tech.molecules.structurized.ai.prism.InMemoryPrismBridgeService;
import tech.molecules.structurized.ai.prism.MaterializePrismSubjectSetRequest;
import tech.molecules.structurized.ai.prism.OpenPrismDatasetRequest;
import tech.molecules.structurized.ai.prism.PrismBridgeService;
import tech.molecules.structurized.ai.render.CompactStructureRenderer;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;
import tech.molecules.structurized.clustering.SimilarityCluster;
import tech.molecules.structurized.prism.result.NumericResult;
import tech.molecules.structurized.prism.result.NumericState;
import tech.molecules.structurized.decomposition.DecompositionConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class McpChemistryTools {
    private final ObjectMapper mapper;
    private final StructureRepositoryService repositories;
    private final StructureInspectionService inspections;
    private final StructureSearchService searches;
    private final PrismBridgeService prism;
    private final McpArtifactService artifacts;
    private final SelectionAiService selections;
    private final SimilarityClusteringAiService clusterings;
    private final DecompositionAiService decompositions;
    private final CompactStructureRenderer compactRenderer = new CompactStructureRenderer();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final List<McpToolDefinition> tools;

    private McpChemistryTools(ObjectMapper mapper, StructureRepositoryService repositories) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.inspections = new OclStructureInspectionService(repositories);
        this.searches = new OclStructureSearchService(repositories);
        this.prism = new InMemoryPrismBridgeService(repositories);
        this.artifacts = new McpArtifactService(mapper);
        this.selections = new SelectionAiService(repositories);
        this.clusterings = new SimilarityClusteringAiService(repositories);
        this.decompositions = new DecompositionAiService(repositories);
        this.tools = List.copyOf(registerTools());
    }

    static McpChemistryTools createDefault(ObjectMapper mapper) {
        return new McpChemistryTools(mapper, new InMemoryStructureRepositoryService());
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
                prop("topic", "string", "overview, payload_hygiene, prism_workflow, clustering_workflow, decomposition_rules, or artifact_output.")),
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
        add(result, "open_prism_dataset", "Loads a PRISM TSV bundle as an in-memory dataset session.", schema(
                required("path"),
                prop("path", "string", "Path to a PRISM TSV bundle directory."),
                prop("dataset_id", "string", "Optional dataset ID."),
                prop("label", "string", "Optional display label.")),
                args -> prism.openDataset(new OpenPrismDatasetRequest(
                        Path.of(requiredString(args, "path")),
                        optionalString(args, "dataset_id", null),
                        optionalString(args, "label", null))));
        add(result, "list_prism_datasets", "Lists loaded PRISM dataset sessions.", schema(),
                args -> prism.listDatasets());
        add(result, "get_prism_dataset_info", "Returns counts, subject sets, and endpoint summaries for one PRISM dataset.", schema(
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
        add(result, "summarize_clusters_by_endpoint", "Summarizes one numeric Prism endpoint for each cluster without returning member IDs.", schema(
                required("clustering_id", "dataset_id", "endpoint_id"),
                prop("clustering_id", "string", "Stored clustering ID."),
                prop("dataset_id", "string", "Loaded Prism dataset ID."),
                prop("endpoint_id", "string", "Numeric Prism endpoint ID."),
                prop("include_singletons", "boolean", "Whether to include singleton clusters."),
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
        add(result, "evaluate_decomposition", "Evaluates a decomposition config against molecules and reports coverage, no-match, non-unique, and invalid-assignment outcomes.", schema(
                required("config_id", "repository_id"),
                prop("evaluation_id", "string", "Optional evaluation ID."),
                prop("config_id", "string", "Stored decomposition config ID."),
                prop("repository_id", "string", "Repository ID to evaluate."),
                arrayProp("structure_ids", "string", "Optional selected structure IDs.")),
                args -> decompositions.evaluate(
                        optionalString(args, "evaluation_id", null),
                        requiredString(args, "config_id"),
                        requiredString(args, "repository_id"),
                        optionalStringList(args, "structure_ids")));
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
        return result;
    }


    private ToolGuide toolGuide(ObjectNode args) {
        String topic = optionalString(args, "topic", "overview");
        String normalized = topic == null || topic.isBlank() ? "overview" : topic.trim().toLowerCase();
        return new ToolGuide(normalized, switch (normalized) {
            case "overview" -> """
                    # Structurized MCP Guide
                    Start compact: use counts, summaries, selections, and endpoint aggregations before requesting row-level detail.
                    Main flows: open_prism_dataset -> materialize_prism_subject_set -> cluster_structures -> get_clustering -> summarize_clusters_by_endpoint; search_substructure(create_selection:true) -> summarize_selection_by_endpoint; create_decomposition_config -> evaluate_decomposition -> get_decomposition_failures.
                    Use output_target:file for large drill-downs and list_artifacts/get_artifact_info to recover artifact paths.
                    """;
            case "payload_hygiene" -> """
                    # Payload Hygiene
                    search_substructure defaults to output_mode:count. Request output_mode:ids for compact rows and output_mode:full only when atom mappings are needed.
                    get_clustering and get_cluster are summaries; get_cluster_members and get_selection_members are paged drill-down tools.
                    Prefer create_selection:true plus summarize_selection_by_endpoint when analyzing endpoint distributions for search hits or cluster members.
                    """;
            case "prism_workflow" -> """
                    # Prism Workflow
                    Open a TSV bundle with open_prism_dataset, inspect endpoints and subject sets, then materialize a subject set into a chemistry repository.
                    Use repository IDs returned by materialize_prism_subject_set for structure search, clustering, and decomposition evaluation.
                    Endpoint summaries use Prism subject IDs preserved in materialized structure fields.
                    """;
            case "clustering_workflow" -> """
                    # Clustering Workflow
                    Run cluster_structures with SkelSpheres threshold around 0.75-0.85 for rough chemotype neighborhoods.
                    Use get_clustering for representative-led summaries, then summarize_clusters_by_endpoint to compare endpoint distributions without member payloads.
                    Use get_cluster_members with create_selection:true for a cluster-specific selection, or output_target:file for a larger member list.
                    """;
            case "decomposition_rules" -> """
                    # Decomposition Rules
                    Config shape is {version, rules:[{id,labelToSplit,smarts,atomLabels,enabled}]}. labelToSplit:null targets the root molecule; child rules target output labels.
                    atomLabels maps zero-based SMARTS query atom indices to output fragment labels. These keys are not SMARTS atom-map numbers like :1 or :2.
                    Bonds between differently labeled adjacent matched atoms are cut. After cuts, every resulting component must contain exactly one label type; unlabeled atoms are absorbed into their connected labeled component.
                    Amide example: for [C:1](=O)[NX3:2], label the carbon and nitrogen query atoms, e.g. {"0":"acyl","2":"amine"}; leave oxygen unlabeled. The common {"1":"acyl","2":"amine"} labels oxygen and nitrogen and leaves carbon unlabeled, producing one component with multiple label types.
                    validate_decomposition_config checks schema, SMARTS compilation, label index range, and query-graph label partitioning. evaluate_decomposition is still required for molecule-specific matches, ambiguity, and coverage.
                    """;
            case "artifact_output" -> """
                    # Artifact Output
                    Large-output tools accept output_target:response or output_target:file. File mode writes JSON under a server-managed artifact directory and returns an artifact receipt instead of the full payload.
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
        EndpointClusterSummary response = buildClusterEndpointSummary(
                requiredString(args, "clustering_id"),
                requiredString(args, "dataset_id"),
                requiredString(args, "endpoint_id"),
                optionalBoolean(args, "include_singletons", true),
                optionalDouble(args, "threshold", null),
                optionalString(args, "threshold_direction", "gte")
        );
        return maybeFile(
                args,
                "summarize_clusters_by_endpoint",
                response,
                new EndpointClusterArtifactSummary(response.clusteringId(), response.repositoryId(), response.datasetId(), response.endpointId(), response.clusters().size()),
                response.clusters().size()
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
        return new EndpointClusterSummary(clusteringId, repositoryId, datasetId, endpointId, rows);
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

    private Object maybeFile(ObjectNode args, String sourceTool, Object responsePayload, Object summary, Integer rowCount) {
        String outputTarget = normalizeOutputTarget(optionalString(args, "output_target", "response"));
        if ("response".equals(outputTarget)) {
            return responsePayload;
        }
        McpArtifactService.ArtifactRecord artifact = writeJsonArtifact(args, sourceTool, responsePayload, rowCount);
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

    private record ToolGuide(String topic, String markdown) {}

    private record ArtifactOutputResult(Object summary, McpArtifactService.ArtifactRecord artifact) {}

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

    private record EndpointClusterArtifactSummary(
            String clusteringId,
            String repositoryId,
            String datasetId,
            String endpointId,
            int clusterCount
    ) {}

    private record FragmentSummaryArtifactSummary(
            String evaluationId,
            int totalRows,
            int returnedRows,
            boolean detailed
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
            List<EndpointClusterSummaryRow> clusters
    ) {}

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

    private record Required(List<String> names) {}

    private record Property(String name, Map<String, Object> schema) {}

    @FunctionalInterface
    private interface ToolHandler {
        Object call(ObjectNode args) throws Exception;
    }
}
