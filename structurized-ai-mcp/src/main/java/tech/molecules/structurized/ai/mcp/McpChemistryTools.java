package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import tech.molecules.structurized.ai.model.StructureRef;
import tech.molecules.structurized.ai.model.SubstructureSearchRequest;
import tech.molecules.structurized.ai.render.CompactStructureRenderer;
import tech.molecules.structurized.ai.repository.InMemoryStructureRepositoryService;
import tech.molecules.structurized.ai.repository.StructureRepositoryService;
import tech.molecules.structurized.ai.search.OclStructureSearchService;
import tech.molecules.structurized.ai.search.StructureSearchService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class McpChemistryTools {
    private final ObjectMapper mapper;
    private final StructureRepositoryService repositories;
    private final StructureInspectionService inspections;
    private final StructureSearchService searches;
    private final CompactStructureRenderer compactRenderer = new CompactStructureRenderer();
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final List<McpToolDefinition> tools;

    private McpChemistryTools(ObjectMapper mapper, StructureRepositoryService repositories) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.inspections = new OclStructureInspectionService(repositories);
        this.searches = new OclStructureSearchService(repositories);
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
        add(result, "search_substructure", "Searches repositories for a SMILES or supported SMARTS substructure and returns target atom mappings.", schema(
                required("query"),
                prop("query", "string", "SMILES or SMARTS query."),
                prop("query_type", "string", "smiles or smarts."),
                arrayProp("repository_ids", "string", "Optional repository scope."),
                prop("component_scope", "string", "all or largest."),
                prop("max_results", "integer", "Maximum matching structures returned."),
                prop("max_matches_per_structure", "integer", "Maximum mappings per structure."),
                prop("include_atom_mappings", "boolean", "Whether to include atom mappings.")),
                args -> searches.searchSubstructure(new SubstructureSearchRequest(
                        requiredString(args, "query"),
                        optionalString(args, "query_type", "smiles"),
                        optionalStringList(args, "repository_ids"),
                        optionalString(args, "component_scope", "all"),
                        optionalInt(args, "max_results", 100),
                        optionalInt(args, "max_matches_per_structure", 1),
                        optionalBoolean(args, "include_atom_mappings", true))));
        return result;
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

    private record Required(List<String> names) {}

    private record Property(String name, Map<String, Object> schema) {}

    @FunctionalInterface
    private interface ToolHandler {
        Object call(ObjectNode args) throws Exception;
    }
}
