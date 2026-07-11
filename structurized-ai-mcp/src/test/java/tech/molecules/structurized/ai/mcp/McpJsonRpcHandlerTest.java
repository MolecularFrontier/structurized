package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void initializeReturnsServerCapabilities() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}} ");

        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("2024-11-05", response.at("/result/protocolVersion").asText());
        assertEquals("structurized-ai-mcp", response.at("/result/serverInfo/name").asText());
        assertTrue(response.at("/result/capabilities/tools").isObject());
    }

    @Test
    void initializedNotificationHasNoResponse() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        String response = handler.handleJson("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertNull(response);
    }

    @Test
    void toolsListExposesCoreChemistryTools() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = response.at("/result/tools");

        assertEquals(23, tools.size());
        assertTrue(hasTool(tools, "register_structure"));
        assertTrue(hasTool(tools, "inspect_structure"));
        assertTrue(hasTool(tools, "search_substructure"));
        assertTrue(hasTool(tools, "cut_bonds"));
        assertTrue(hasTool(tools, "open_prism_dataset"));
        assertTrue(hasTool(tools, "materialize_prism_subject_set"));
        assertEquals("object", tools.get(0).at("/inputSchema/type").asText());
    }

    @Test
    void canRegisterInspectAndSearchThroughToolCalls() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode register = call(handler, request(1, "register_structure", "{\"smiles\":\"c1ccncc1\",\"structure_id\":\"pyridine\",\"label\":\"Pyridine\"}"));
        assertEquals("pyridine", register.at("/result/structuredContent/structureId").asText());

        JsonNode inspect = call(handler, request(2, "inspect_structure", "{\"repository_id\":\"session\",\"structure_id\":\"pyridine\"}"));
        assertTrue(inspect.at("/result/content/0/text").asText().contains("STRUCTURE session:pyridine"));
        assertEquals(6, inspect.at("/result/structuredContent/atoms").size());

        JsonNode search = call(handler, request(3, "search_substructure", "{\"query\":\"c1ccncc1\",\"query_type\":\"smiles\"}"));
        assertEquals(1, search.at("/result/structuredContent/summary/matchingStructures").asInt());
        assertEquals("pyridine", search.at("/result/structuredContent/matches/0/structureId").asText());
    }


    @Test
    void canOpenPrismMaterializeSearchAndFetchEndpointValues() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();
        Path dataset = prismDataset();
        String path = dataset.toString().replace("\\", "\\\\");

        JsonNode open = call(handler, request(10, "open_prism_dataset", "{\"path\":\"" + path + "\",\"dataset_id\":\"demo\",\"label\":\"Demo\"}"));
        assertEquals("demo", open.at("/result/structuredContent/datasetId").asText());
        assertEquals(2, open.at("/result/structuredContent/structureSubjectCount").asInt());

        JsonNode sets = call(handler, request(11, "list_prism_subject_sets", "{\"dataset_id\":\"demo\"}"));
        assertTrue(sets.at("/result/structuredContent").toString().contains("series:Kinase:A"));

        JsonNode materialized = call(handler, request(12, "materialize_prism_subject_set", "{\"dataset_id\":\"demo\",\"subject_set_id\":\"series:Kinase:A\"}"));
        String repositoryId = materialized.at("/result/structuredContent/repositoryId").asText();
        assertEquals("prism:demo:series:Kinase:A", repositoryId);
        assertEquals(2, materialized.at("/result/structuredContent/structuresImported").asInt());

        JsonNode search = call(handler, request(13, "search_substructure", "{\"query\":\"c1ccncc1\",\"repository_ids\":[\"" + repositoryId + "\"]}"));
        assertEquals("CMP-001", search.at("/result/structuredContent/matches/0/structureId").asText());

        JsonNode values = call(handler, request(14, "get_prism_endpoint_values", "{\"dataset_id\":\"demo\",\"subject_ids\":[\"CMP-001\"],\"endpoint_ids\":[\"pIC50\"]}"));
        assertEquals("pIC50", values.at("/result/structuredContent/0/endpointId").asText());
        assertEquals(7.2, values.at("/result/structuredContent/0/result/mean").asDouble());
    }

    @Test
    void toolChemistryFailuresReturnMcpToolErrorResult() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, request(1, "inspect_atom", "{\"repository_id\":\"session\",\"structure_id\":\"missing\",\"atom_id\":\"a1\"}"));

        assertFalse(response.has("error"));
        assertTrue(response.at("/result/isError").asBoolean());
        assertEquals("structure_not_found", response.at("/result/structuredContent/code").asText());
    }

    @Test
    void unknownJsonRpcMethodReturnsProtocolError() throws Exception {
        McpJsonRpcHandler handler = McpJsonRpcHandler.createDefault();

        JsonNode response = call(handler, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown\",\"params\":{}}");

        assertEquals(-32601, response.at("/error/code").asInt());
        assertEquals("method_not_found", response.at("/error/data/code").asText());
    }


    private Path prismDataset() throws Exception {
        Path dir = tempDir.resolve("prism-tsv");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("endpoints.prism.tsv"), String.join("\n",
                "endpoint_id\tname\tpath\tdatatype\tendpoint_type\tevaluation_mode\tunit\tscale",
                "pIC50\tpIC50\tassay/pIC50\tNUMERIC\tMEASURED\tIMMEDIATE\tpIC50\tLOG",
                ""
        ));
        Files.writeString(dir.resolve("subjects.prism.tsv"), String.join("\n",
                "subject_id\tstructure_id\tbatch_id\tproject\tseries\tsmiles",
                "CMP-001\tS-001\tB-001\tKinase\tA\tc1ccncc1",
                "CMP-002\tS-002\tB-002\tKinase\tA\tCCN",
                ""
        ));
        Files.writeString(dir.resolve("values.prism.tsv"), String.join("\n",
                "subject_id\tendpoint_id\tstate\tmean\tn",
                "CMP-001\tpIC50\tVALUE\t7.2\t3",
                "CMP-002\tpIC50\tVALUE\t6.1\t1",
                ""
        ));
        return dir;
    }

    private JsonNode call(McpJsonRpcHandler handler, String request) throws Exception {
        String response = handler.handleJson(request);
        assertNotNull(response);
        return mapper.readTree(response);
    }

    private static String request(int id, String toolName, String argsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + argsJson + "}}";
    }

    private static boolean hasTool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.get("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
