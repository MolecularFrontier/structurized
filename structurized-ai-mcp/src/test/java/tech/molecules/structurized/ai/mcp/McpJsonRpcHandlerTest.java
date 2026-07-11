package tech.molecules.structurized.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

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

        assertEquals(14, tools.size());
        assertTrue(hasTool(tools, "register_structure"));
        assertTrue(hasTool(tools, "inspect_structure"));
        assertTrue(hasTool(tools, "search_substructure"));
        assertTrue(hasTool(tools, "cut_bonds"));
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
